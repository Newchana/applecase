package com.applecase.gateway.net;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP 音频通道占位。阶段一仅验证 ZeroTier UDP 可达性 + 统计收发。
 * 真实 Opus 音频在阶段三接入(见 docs/PROTOCOL.md 第 5 节)。
 *
 * 用法:start() 启动监听线程;可 send() 发包给对端。
 */
public class UdpAudioSocket {

    private static final String TAG = "AC.UdpAudio";
    public static final int DEFAULT_PORT = 51820;

    private final int port;
    private DatagramSocket socket;
    private Thread receiveThread;
    private volatile boolean running = false;

    private final AtomicLong rxCount = new AtomicLong();
    private final AtomicLong txCount = new AtomicLong();

    public UdpAudioSocket(int port) {
        this.port = port;
    }

    /** 启动接收线程。 */
    public void start() {
        if (running) return;
        try {
            socket = new DatagramSocket(port);
            socket.setSoTimeout(0); // 阻塞接收
            running = true;
            receiveThread = new Thread(this::receiveLoop, "udp-audio-rx");
            receiveThread.setDaemon(true);
            receiveThread.start();
            Log.i(TAG, "listening on UDP :" + port);
        } catch (Exception e) {
            Log.e(TAG, "bind failed: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(pkt);
                rxCount.incrementAndGet();
                // 阶段一:仅统计,不解析。阶段三在此解析音频帧头并交给解码器。
            } catch (Exception e) {
                if (running) Log.w(TAG, "recv error: " + e.getMessage());
            }
        }
    }

    /** 发送一帧到对端。骨架期用于 ping 测试。 */
    public void send(byte[] data, InetAddress to, int port) {
        if (socket == null || !running) return;
        try {
            DatagramPacket pkt = new DatagramPacket(data, data.length, to, port);
            socket.send(pkt);
            txCount.incrementAndGet();
        } catch (Exception e) {
            Log.w(TAG, "send error: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
            socket = null;
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        Log.i(TAG, "stopped. stats rx=" + rxCount.get() + " tx=" + txCount.get());
    }

    public long getRxCount() { return rxCount.get(); }
    public long getTxCount() { return txCount.get(); }
}
