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
        AudioTrack track = null;
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

            // ---- 上行:INCALL_MUSIC → TELEPHONY_TX ----
            AudioAttributes.Builder ab = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setFlags(incallMusicFlag());
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            int minTrk = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(ab.build(), fmt, Math.max(minTrk, FRAME_BYTES * 8),
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack UNINITIALIZED");
                return;
            }

            // ---- 连 App 侧 ----
            sock = new Socket();
            sock.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
            final InputStream in = sock.getInputStream();
            final OutputStream out = sock.getOutputStream();
            final AudioRecord rec = recorder;
            final AudioTrack trk = track;
            final Socket socketRef = sock;

            Log.i(TAG, "connected to app, piping audio");

            // 上行管道:TCP → AudioTrack
            Thread up = new Thread(() -> {
                byte[] buf = new byte[FRAME_BYTES];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        trk.write(buf, 0, n, AudioTrack.WRITE_BLOCKING);
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
            try { if (track != null) track.release(); } catch (Exception ignored) {}
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            Log.i(TAG, "relay exited");
        }
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
