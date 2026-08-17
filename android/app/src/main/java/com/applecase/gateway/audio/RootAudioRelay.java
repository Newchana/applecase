package com.applecase.gateway.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * root 特权音频中继(阶段三语音桥的特权侧)。
 *
 * 为什么需要它:AudioRecord 源 VOICE_CALL 要 signature|privileged 级 CAPTURE_AUDIO_OUTPUT,
 * 普通 App(pm grant 拒绝、priv-app 挂载在本 ROM 上因 FBE 数据目录问题失败)都拿不到;
 * 而 root(uid 0)进程的 binder 权限校验天然放行 —— 这正是 README 阶段三规划
 * "用 root 特权进程解决" 的落地。
 *
 * 运行方式(GatewayService 通过 su 拉起):
 *   CLASSPATH=<本APK路径> app_process /system/bin \
 *       com.applecase.gateway.audio.RootAudioRelay <本机TCP端口>
 *
 * 行为:连上 App 侧(VoiceBridge 的 localhost TCP 监听)后全双工管道:
 *   AudioRecord(VOICE_CALL) → TCP → App → UDP → iPhone   (下行:对方说话)
 *   iPhone → UDP → App → TCP → AudioTrack(INCALL_MUSIC)   (上行:你说话,混入通话)
 * 任一方向断开即退出(由 App 侧重启管理)。
 */
public class RootAudioRelay {

    private static final String TAG = "AC.Relay";
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_BYTES = 640;   // 20ms PCM16 单声道
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 51830;
        Log.i(TAG, "root audio relay starting, target tcp 127.0.0.1:" + port
                + " uid=" + android.os.Process.myUid());

        AudioRecord recorder = null;
        Socket sock = null;
        try {
            // ---- 下行:VOICE_CALL 捕获(root 下 CAPTURE_AUDIO_OUTPUT 校验放行) ----
            int minRec = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minRec, FRAME_BYTES * 4));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord UNINITIALIZED even as root, state=" + recorder.getState());
                return;
            }

            // ---- 上行:FIFO → tinyplay → ALSA FE_ST_VOICE_PCM_P(card0 dev53) ----
            // 此 ROM 的策略引擎不给任何应用路由 INCALL_MUSIC(FLAG 全位扫描+USAGE_MEDIA 均无效,
            // AudioFlinger Frames written 恒 0),但 VBC 的 Voice PCM Play 前端设备空闲,
            // root 直接写 ALSA 实测可混入通话上行(440Hz 环回对比度 4x,2026-08-17)。
            java.io.OutputStream upOut = openAlsaUplink();
            if (upOut == null) {
                Log.e(TAG, "ALSA uplink fifo failed");
                return;
            }

            // ---- 连 App 侧 ----
            sock = new Socket();
            sock.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
            final InputStream in = sock.getInputStream();
            final OutputStream out = sock.getOutputStream();
            final AudioRecord rec = recorder;
            final Socket socketRef = sock;

            Log.i(TAG, "connected to app, piping audio");

            // 上行管道:TCP → FIFO(tinyplay 消费)
            Thread up = new Thread(() -> {
                byte[] buf = new byte[FRAME_BYTES];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        upOut.write(buf, 0, n);
                        upOut.flush();
                    }
                } catch (Exception e) {
                    Log.i(TAG, "uplink pipe end: " + e.getMessage());
                }
                exitWhenDone(socketRef);
            }, "relay-up");

            // 下行管道:AudioRecord → TCP
            Thread down = new Thread(() -> {
                byte[] buf = new byte[FRAME_BYTES];
                try {
                    rec.startRecording();
                    while (!socketRef.isClosed()) {
                        int n = rec.read(buf, 0, FRAME_BYTES);
                        if (n > 0) out.write(buf, 0, n);
                    }
                } catch (Exception e) {
                    Log.i(TAG, "downlink pipe end: " + e.getMessage());
                }
                exitWhenDone(socketRef);
            }, "relay-down");

            up.setDaemon(true);
            down.setDaemon(true);
            up.start();
            down.start();
            up.join();
            down.join();

        } catch (Throwable t) {
            Log.e(TAG, "relay failed: " + t);
        } finally {
            try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
            if (tinyplayProc != null) {
                tinyplayProc.destroy();
                try { Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "pkill tinyplay; rm -f " + FIFO}).waitFor(); } catch (Exception ignored) {}
            }
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            Log.i(TAG, "relay exited");
        }
    }

    /** FIFO 路径与 tinyplay 进程。 */
    private static Process tinyplayProc;
    private static final String FIFO = "/data/local/tmp/ac_uplink.fifo";

    /**
     * 起 tinyplay 读 FIFO 灌 ALSA(card0/dev53=FE_ST_VOICE_PCM_P),返回 FIFO 写端。
     * 先写一个"无限长"的 WAV 头,tinyplay 解析后持续把后续 PCM 写进声卡。
     */
    private static java.io.OutputStream openAlsaUplink() {
        try {
            // 1) 清掉旧 fifo;su 环境下 mkfifo 偶发 Interrupted system call,重试
            new java.io.File(FIFO).delete();
            for (int i = 0; i < 5; i++) {
                try {
                    new ProcessBuilder("mkfifo", FIFO).start().waitFor();
                } catch (Exception ignored) {}
                if (new java.io.File(FIFO).exists()) break;
                Thread.sleep(150);
            }
            if (!new java.io.File(FIFO).exists()) {
                Log.e(TAG, "mkfifo failed");
                return null;
            }
            // 2) 直接起 tinyplay(不经 sh;读端打开会阻塞直到写端,内核配对)
            tinyplayProc = new ProcessBuilder("/data/local/tmp/ta/tinyplay", FIFO, "-D", "0", "-d", "53")
                    .redirectErrorStream(true).start();
            new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(tinyplayProc.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) Log.i(TAG, "tinyplay: " + l);
                } catch (Exception ignored) {}
                Log.i(TAG, "tinyplay exited rc=" + tinyplayProc.exitValue());
            }).start();
            // 3) 开写端(带 5s 看门狗,防 reader 没起导致 relay 卡死)
            final java.util.concurrent.atomic.AtomicReference<java.io.FileOutputStream> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread opener = new Thread(() -> {
                try { ref.set(new java.io.FileOutputStream(FIFO)); } catch (Exception ignored) {}
            });
            opener.setDaemon(true);
            opener.start();
            opener.join(5000);
            java.io.FileOutputStream f = ref.get();
            if (f == null) {
                tinyplayProc.destroy();
                Log.e(TAG, "fifo open timeout (tinyplay not reading?)");
                return null;
            }
            // WAV 头:RIFF,PCM16,16k,单声道,data 长度极大(tinyplay 读到 EOF 为止)
            int rate = SAMPLE_RATE;
            byte[] h = new byte[44];
            int le = 0x0;
            h[0]='R';h[1]='I';h[2]='F';h[3]='F';
            putLe(h, 4, 4, 0x7FFFFFE7);           // RIFF size(偏移4,不能覆盖 RIFF 魔数)
            h[8]='W';h[9]='A';h[10]='V';h[11]='E';
            h[12]='f';h[13]='m';h[14]='t';h[15]=' ';
            putLe(h, 16, 4, 16);                  // fmt chunk size
            putLe(h, 20, 2, 1);                   // PCM
            putLe(h, 22, 2, 1);                   // mono
            putLe(h, 24, 4, rate);
            putLe(h, 28, 4, rate * 2);
            putLe(h, 32, 2, 2);
            putLe(h, 34, 2, 16);
            h[36]='d';h[37]='a';h[38]='t';h[39]='a';
            putLe(h, 40, 4, 0x7FFFFFCF);          // data size(巨大)
            f.write(h);
            Log.i(TAG, "ALSA uplink fifo ready (tinyplay dev53)");
            return f;
        } catch (Exception e) {
            Log.e(TAG, "openAlsaUplink failed: " + e);
            return null;
        }
    }

    private static void putLe(byte[] b, int off, int len, long v) {
        for (int i = 0; i < len; i++) b[off + i] = (byte) ((v >> (8 * i)) & 0xFF);
    }

    private static void exitWhenDone(Socket s) {
        try { s.close(); } catch (Exception ignored) {}
        // 关闭后两个管道线程的阻塞调用都会失败退出
    }

    /** FLAG_INCALL_MUSIC 为 @hide,反射取位值,失败兜底 0x800。 */
    private static int incallMusicFlag() {
        try {
            java.lang.reflect.Field f = AudioAttributes.class.getDeclaredField("FLAG_INCALL_MUSIC");
            f.setAccessible(true);
            int v = f.getInt(null);
            Log.i(TAG, "FLAG_INCALL_MUSIC = 0x" + Integer.toHexString(v));
            return v;
        } catch (Throwable t) {
            Log.w(TAG, "reflect FLAG_INCALL_MUSIC failed, fallback 0x800: " + t);
            return 0x800;
        }
    }
}
