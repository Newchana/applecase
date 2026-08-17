package com.applecase.gateway.audio;

import android.content.Context;
import android.util.Log;

import fi.iki.elonen.NanoWSD;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时语音桥(阶段三核心,App 侧)。
 *
 * 架构(应对权限现实):
 *   iPhone ⇄(UDP/PCM16)⇄ 本类 ⇄(localhost TCP/裸PCM)⇄ RootAudioRelay(root 进程)
 *                                          ├ AudioRecord(VOICE_CALL)      ← 需要 CAPTURE_AUDIO_OUTPUT
 *                                          └ AudioTrack(INCALL_MUSIC→TX)
 * signature 级权限普通 App 拿不到,root 进程放行,故音频采集/注入都在中继里做。
 *
 * UDP 帧格式(8 字节头,见 docs/PROTOCOL.md 第 5 节):
 *   [0] = ver<<4 | dir(ver=1;dir:1=下行 GW→CL,2=上行 CL→GW)
 *   [1..3] = seq(24bit 大端)   [4..7] = ts(发送方毫秒)
 */
public class VoiceBridge {

    private static final String TAG = "AC.Voice";
    public static final int UDP_PORT = 51820;
    public static final int LOCAL_TCP_PORT = 51830;
    public static final String CODEC = "pcm16";

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_MS = 20;
    private static final int FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000; // 640
    private static final byte HDR_VER = 1;
    private static final byte DIR_DOWN = 1;   // GW→CL(对方说话)
    private static final byte DIR_UP = 2;     // CL→GW(你说话)

    private final Context context;

    private DatagramSocket udp;
    private ServerSocket tcpServer;
    private Socket relaySock;
    private Process relayProc;
    private Thread rxThread;      // iPhone UDP → 中继(上行)
    private Thread capThread;     // 中继 TCP → iPhone UDP(下行)
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 客户端音频地址:AUDIO_CTRL 指定,或从首个上行包自学。 */
    private volatile InetSocketAddress remote;

    // Web 模式(浏览器不能发裸 UDP,音频走 WebSocket)
    private volatile NanoWSD.WebSocket wsAudio;
    private volatile java.io.OutputStream relayOut;

    private final AtomicLong downSeq = new AtomicLong();
    private final AtomicLong statRx = new AtomicLong();
    private final AtomicLong statTx = new AtomicLong();
    private final AtomicLong statNonSilent = new AtomicLong();
    private volatile long lastStatLogAt = 0;

    public VoiceBridge(Context context) {
        this.context = context;
    }

    public boolean isRunning() { return running.get(); }

    /**
     * 启动桥接:拉起 root 中继并接通管道。
     * @param dir "up"|"down"|"both"(中继始终全双工,方向仅决定是否向客户端发帧/收帧)
     * @param clientHost 客户端 IP(null=等首个上行包自学)
     * @param clientPort 客户端 UDP 端口
     * @return null=成功;否则错误说明(回给客户端)
     */
    public synchronized String start(String dir, String clientHost, int clientPort) {
        if (running.get()) return "already running";
        wsAudio = null;
        // 注意:"both" 不包含 "down"/"up" 字面,必须整体判断(踩坑:曾致 both 模式双路全哑)
        final boolean all = dir == null || dir.equals("both");
        final boolean sendDown = all || dir.contains("down");
        final boolean recvUp = all || dir.contains("up");

        try {
            udp = new DatagramSocket(UDP_PORT);
        } catch (Exception e) {
            return "bind udp " + UDP_PORT + " failed: " + e.getMessage();
        }
        try {
            tcpServer = new ServerSocket();
            tcpServer.setReuseAddress(true);
            java.net.InetAddress lo = InetAddress.getByName("127.0.0.1");
            tcpServer.bind(new InetSocketAddress(lo, LOCAL_TCP_PORT), 1);
        } catch (Exception e) {
            cleanup();
            return "bind tcp " + LOCAL_TCP_PORT + " failed: " + e.getMessage();
        }
        if (clientHost != null && !clientHost.isEmpty() && clientPort > 0) {
            try { remote = new InetSocketAddress(InetAddress.getByName(clientHost), clientPort); }
            catch (Exception e) { cleanup(); return "bad client addr: " + clientHost + ":" + clientPort; }
        }

        // 拉起 root 中继
        String err = spawnRelay();
        if (err != null) { cleanup(); return err; }

        // 等中继回连
        try {
            tcpServer.setSoTimeout(15_000);
            relaySock = tcpServer.accept();
            relaySock.setTcpNoDelay(true);
        } catch (Exception e) {
            cleanup();
            return "relay connect timeout: " + e.getMessage();
        }

        running.set(true);
        final InputStream relayIn;
        final OutputStream relayOut;
        try {
            relayIn = relaySock.getInputStream();
            relayOut = relaySock.getOutputStream();
            this.relayOut = relayOut;
        } catch (Exception e) {
            cleanup();
            return "relay streams: " + e.getMessage();
        }

        // 下行:中继 → UDP(对方声音发给 iPhone)
        if (sendDown) {
            capThread = new Thread(() -> {
                byte[] pcm = new byte[FRAME_BYTES];
                try {
                    int n;
                    while (running.get() && (n = relayIn.read(pcm)) > 0) {
                        InetSocketAddress to = remote;
                        if (to == null) continue;   // 远端未知,先丢弃
                        sendFrame(pcm, n, to);
                        countSilence(pcm, n);
                        maybeLogStats();
                    }
                } catch (Exception e) {
                    if (running.get()) Log.w(TAG, "downlink pipe end: " + e.getMessage());
                }
            }, "voice-down");
            capThread.setDaemon(true);
            capThread.start();
        }

        // 上行:iPhone UDP → 中继(你的声音混入通话)
        if (recvUp) {
            rxThread = new Thread(() -> {
                byte[] buf = new byte[FRAME_BYTES + 8];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                while (running.get()) {
                    try {
                        udp.receive(pkt);
                    } catch (Exception e) {
                        if (running.get()) Log.w(TAG, "recv: " + e.getMessage());
                        continue;
                    }
                    int len = pkt.getLength();
                    if (len <= 8) continue;
                    if ((buf[0] >> 4 & 0x0F) != HDR_VER || (buf[0] & 0x0F) != DIR_UP) continue;
                    if (remote == null) {
                        SocketAddress sa = pkt.getSocketAddress();
                        if (sa instanceof InetSocketAddress) {
                            remote = (InetSocketAddress) sa;
                            Log.i(TAG, "learned remote from uplink: " + remote);
                        }
                    }
                    statRx.incrementAndGet();
                    try {
                        relayOut.write(buf, 8, len - 8);
                    } catch (Exception e) {
                        if (running.get()) Log.w(TAG, "uplink pipe end: " + e.getMessage());
                        break;
                    }
                    maybeLogStats();
                }
            }, "voice-up");
            rxThread.setDaemon(true);
            rxThread.start();
        }

        Log.i(TAG, "bridge started dir=" + dir + " remote=" + remote
                + " pcm16/" + SAMPLE_RATE + "Hz/" + FRAME_MS + "ms via root relay");
        return null;
    }

    public synchronized void stop() {
        running.getAndSet(false);
        cleanup();
        Log.i(TAG, "bridge stopped. stats tx=" + statTx.get() + " rx=" + statRx.get()
                + " nonSilent=" + statNonSilent.get());
    }

    /**
     * Web 客户端版启动:音频走 WebSocket(浏览器不能发裸 UDP)。
     * 下行:中继 → buildFrame → ws.send;上行:WebServer 收帧 → feedUplink 写中继。
     * 帧格式与 UDP 版一致(8 字节头 + PCM16),便于未来统一。
     */
    public synchronized String startWeb(String dir, NanoWSD.WebSocket ws) {
        if (running.get()) {
            if (wsAudio == ws) {
                return null;   // 同一客户端重复 start:幂等,直接成功
            }
            // 重启语义:换客户端了(上一页没停干净/新页面接手)先停再启
            Log.w(TAG, "bridge already running, restarting for new web client");
            stop();
        }
        statTx.set(0); statRx.set(0); statNonSilent.set(0);
        wsAudio = ws;
        final boolean all = dir == null || dir.equals("both");
        final boolean sendDown = all || dir.contains("down");
        try {
            tcpServer = new ServerSocket();
            tcpServer.setReuseAddress(true);
            tcpServer.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_TCP_PORT), 1);
        } catch (Exception e) {
            cleanup();
            return "bind tcp " + LOCAL_TCP_PORT + " failed: " + e.getMessage();
        }
        String err = spawnRelay();
        if (err != null) { cleanup(); return err; }
        try {
            tcpServer.setSoTimeout(15_000);
            relaySock = tcpServer.accept();
            relaySock.setTcpNoDelay(true);
        } catch (Exception e) {
            cleanup();
            return "relay connect timeout: " + e.getMessage();
        }
        running.set(true);
        final InputStream relayIn;
        final OutputStream relayOut;
        try {
            relayIn = relaySock.getInputStream();
            relayOut = relaySock.getOutputStream();
            this.relayOut = relayOut;
        } catch (Exception e) {
            cleanup();
            return "relay streams: " + e.getMessage();
        }
        if (sendDown) {
            capThread = new Thread(() -> {
                byte[] pcm = new byte[FRAME_BYTES];
                try {
                    int n;
                    while (running.get() && (n = relayIn.read(pcm)) > 0) {
                        if (!sendFrameWs(pcm, n)) {
                            // WS 已断(浏览器关页/刷新):彻底停桥,释放中继与录音资源
                            Log.w(TAG, "audio ws closed, stopping web bridge");
                            stop();
                            break;
                        }
                        countSilence(pcm, n);
                        maybeLogStats();
                    }
                } catch (Exception e) {
                    if (running.get()) Log.w(TAG, "ws downlink pipe end: " + e.getMessage());
                }
            }, "voice-ws-down");
            capThread.setDaemon(true);
            capThread.start();
        }
        Log.i(TAG, "bridge started (web) dir=" + dir);
        return null;
    }

    /** Web 上行:音频 WS 帧(带 8 字节头)→ 校验后写中继(→ AudioTrack 注入通话)。 */
    public void feedUplink(byte[] frame) {
        if (!running.get() || frame == null || frame.length <= 8) return;
        if ((frame[0] >> 4 & 0x0F) != HDR_VER || (frame[0] & 0x0F) != DIR_UP) return;
        java.io.OutputStream out = relayOut;
        if (out == null) return;
        try {
            out.write(frame, 8, frame.length - 8);
            statRx.incrementAndGet();
            maybeLogStats();
        } catch (Exception e) {
            Log.w(TAG, "feedUplink: " + e.getMessage());
        }
    }

    private void cleanup() {
        running.set(false);
        closeQuietly(relaySock); relaySock = null;
        closeQuietly(tcpServer); tcpServer = null;
        if (udp != null) { try { udp.close(); } catch (Exception ignored) {} udp = null; }
        killRelay();
        rxThread = null;
        capThread = null;
        remote = null;
        wsAudio = null;
        relayOut = null;
    }

    /** su 拉起 RootAudioRelay(app_process + 本 APK 做类路径)。 */
    private String spawnRelay() {
        String apk = context.getApplicationInfo().sourceDir;
        String cmd = "CLASSPATH=" + apk + " exec app_process /system/bin "
                + RootAudioRelay.class.getName() + " " + LOCAL_TCP_PORT;
        try {
            relayProc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // 吞掉 su 的输出避免管道阻塞;错误流进 logcat 由中继自己打
            new Thread(() -> { try { relayProc.getInputStream().read(new byte[4096]); } catch (Exception ignored) {} }).start();
            new Thread(() -> { try { relayProc.getErrorStream().read(new byte[4096]); } catch (Exception ignored) {} }).start();
            Log.i(TAG, "relay spawned: " + cmd);
            return null;
        } catch (Exception e) {
            killRelay();
            return "spawn relay failed(需要 root/Magisk 授权): " + e.getMessage();
        }
    }

    private void killRelay() {
        if (relayProc != null) {
            relayProc.destroy();
            relayProc = null;
        }
        // destroy 杀的是 su 壳,app_process 子进程兜底再杀一次
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pkill -f " + RootAudioRelay.class.getName()}).waitFor();
        } catch (Exception ignored) {}
    }

    // ============ 帧封装与统计 ============

    private byte[] buildFrame(byte[] pcm, int len) {
        long seq = downSeq.getAndIncrement();
        ByteBuffer bb = ByteBuffer.allocate(8 + len);
        bb.put((byte) ((HDR_VER << 4) | DIR_DOWN));
        bb.put((byte) ((seq >> 16) & 0xFF));
        bb.put((byte) ((seq >> 8) & 0xFF));
        bb.put((byte) (seq & 0xFF));
        bb.putInt((int) (System.currentTimeMillis() & 0x7FFFFFFF));
        bb.put(pcm, 0, len);
        return bb.array();
    }

    private void sendFrame(byte[] pcm, int len, InetSocketAddress to) {
        byte[] pkt = buildFrame(pcm, len);
        try {
            udp.send(new DatagramPacket(pkt, pkt.length, to.getAddress(), to.getPort()));
            statTx.incrementAndGet();
        } catch (Exception e) {
            Log.w(TAG, "send: " + e.getMessage());
        }
    }

    /** @return false=WS 已断,调用方应停止下行循环。 */
    private boolean sendFrameWs(byte[] pcm, int len) {
        NanoWSD.WebSocket ws = wsAudio;
        if (ws == null) return false;
        try {
            ws.send(buildFrame(pcm, len));
            statTx.incrementAndGet();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ws send failed: " + e.getMessage());
            return false;
        }
    }

    /** 非静默帧统计(下行是否真抓到通话声,验证用)。 */
    private void countSilence(byte[] pcm, int len) {
        int max = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
            if (v > max) max = v;
        }
        if (max > 300) statNonSilent.incrementAndGet();
    }

    private void maybeLogStats() {
        long now = System.currentTimeMillis();
        if (now - lastStatLogAt >= 5000) {
            lastStatLogAt = now;
            Log.i(TAG, "stats tx=" + statTx.get() + " rx=" + statRx.get()
                    + " nonSilent=" + statNonSilent.get() + " remote=" + remote);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) { try { c.close(); } catch (Exception ignored) {} }
    }
}
