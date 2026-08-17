package com.applecase.gateway.web;

import android.content.Context;
import android.util.Log;

import com.applecase.gateway.net.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * 内嵌 Web 服务器(阶段四:Web 客户端,替代 iOS 路线)。
 *
 * 浏览器直接访问 http://<本机IP>:8080,不依赖 MQTT broker:
 * - GET /            → 单页应用(assets/web/index.html)
 * - WS  /ws          → 控制通道(JSON,信封与 MQTT 协议一致,token 免)
 * - WS  /audio       → 音频通道(二进制,帧格式同 UDP:8字节头+PCM16)
 *   (浏览器不能发裸 UDP,语音走 WebSocket;局域网/ZeroTier 内 TCP 延迟可接受)
 *
 * 事件扇出:GatewayService 用 WebMqttClient 把所有 MQTT 事件同步广播到 /ws 客户端,
 * 因此 Web 端能收到 SMS_IN、CALL 系列事件、SIM_STATE、ACK 全部事件。
 * 指令入口:CommandSink → GatewayService.handleWebCommand(与 MQTT 共用处理逻辑)。
 */
public class WebServer extends NanoWSD {

    private static final String TAG = "AC.Web";
    public static final int PORT = 8080;

    private final Context context;
    private final CommandSink commandSink;
    private final Set<CtrlWs> ctrlClients = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 连接保活:NanoHTTPD 的 socket 读超时是 5s,WS 空闲(无上行)就会被踢;
     *  服务端定期 ping → 客户端自动回 pong → 重置超时。 */
    private final java.util.concurrent.ScheduledExecutorService keepalive =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private volatile NanoWSD.WebSocket audioClient;
    private static final byte[] PING = {1};

    /** 控制通道指令回调(→ GatewayService.handleWebCommand)。 */
    public interface CommandSink { void onWebCommand(Protocol.Message msg); }
    /** 上行音频帧回调(→ VoiceBridge.feedUplink)。 */
    public interface AudioSink { void accept(byte[] frame); }
    private AudioSink audioSink;

    public WebServer(Context context, CommandSink sink) {
        super(PORT);
        this.context = context;
        this.commandSink = sink;
    }

    public void setAudioSink(AudioSink s) { this.audioSink = s; }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        String uri = handshake.getUri();
        if (uri != null && uri.startsWith("/audio")) return new AudioWs(handshake);
        return new CtrlWs(handshake);
    }

    @Override
    protected Response serveHttp(IHTTPSession session) {
        String uri = session.getUri() == null ? "/" : session.getUri();
        if ("/".equals(uri) || uri.startsWith("/index")) {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", readIndex());
        }
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "<a href='/'>→ 首页</a>");
    }

    private String readIndex() {
        try (InputStream in = context.getAssets().open("web/index.html")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (IOException e) {
            return "<h1>applecase web</h1><p>index.html 读取失败: " + e.getMessage() + "</p>";
        }
    }

    /** 向所有控制通道客户端广播(异步执行,不阻塞音频/电话线程)。 */
    public void broadcast(Protocol.Message m) {
        final String json = Protocol.toJson(m);
        io.execute(() -> {
            for (CtrlWs ws : ctrlClients) {
                try { ws.send(json); } catch (Exception e) { ctrlClients.remove(ws); }
            }
        });
    }

    /** 当前连接的音频通道(AUDIO_CTRL start 时绑定给 VoiceBridge)。 */
    public NanoWSD.WebSocket pollAudioSocket() { return audioClient; }

    public int ctrlClientCount() { return ctrlClients.size(); }

    public void stopServer() {
        io.shutdown();
        keepalive.shutdownNow();
        stop();
        Log.i(TAG, "web server stopped");
    }

    /** 给一个连接挂上 ping 保活(2.5s 周期,断开自动停)。 */
    private java.util.concurrent.ScheduledFuture<?> armKeepalive(NanoWSD.WebSocket ws) {
        return keepalive.scheduleWithFixedDelay(() -> {
            try {
                if (ws.isOpen()) ws.ping(PING);
            } catch (Exception e) {
                // ping 失败说明连接已断,NanoWSD 会自行触发 onClose
            }
        }, 2500, 2500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ============ 控制通道 /ws ============

    private class CtrlWs extends WebSocket {
        private java.util.concurrent.ScheduledFuture<?> ka;
        CtrlWs(IHTTPSession h) { super(h); }

        @Override protected void onOpen() {
            ctrlClients.add(this);
            ka = armKeepalive(this);
            Log.i(TAG, "ctrl client + (total " + ctrlClients.size() + ")");
        }

        @Override protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            if (ka != null) ka.cancel(false);
            ctrlClients.remove(this);
            Log.i(TAG, "ctrl client - (total " + ctrlClients.size() + ")");
        }

        @Override protected void onMessage(WebSocketFrame frame) {
            try {
                byte[] p = frame.getBinaryPayload();
                if (p == null || p.length == 0) return;
                Protocol.Message msg = Protocol.parse(new String(p, StandardCharsets.UTF_8));
                commandSink.onWebCommand(msg);
            } catch (Exception e) {
                try {
                    send("{\"type\":\"CMD_ACK\",\"data\":{\"ok\":false,\"error\":\"BAD_REQUEST\",\"detail\":\""
                            + (e.getMessage() == null ? "parse" : e.getMessage()) + "\"}}");
                } catch (Exception ignored) {}
            }
        }

        @Override protected void onPong(WebSocketFrame pong) {}
        @Override protected void onException(IOException e) { ctrlClients.remove(this); }
    }

    // ============ 音频通道 /audio ============

    private class AudioWs extends WebSocket {
        private java.util.concurrent.ScheduledFuture<?> ka;
        AudioWs(IHTTPSession h) { super(h); }

        @Override protected void onOpen() {
            audioClient = this;
            ka = armKeepalive(this);
            Log.i(TAG, "audio client connected");
        }

        @Override protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            if (ka != null) ka.cancel(false);
            if (audioClient == this) audioClient = null;
            Log.i(TAG, "audio client disconnected");
        }

        @Override protected void onMessage(WebSocketFrame frame) {
            byte[] payload = frame.getBinaryPayload();
            AudioSink sink = audioSink;
            if (payload != null && payload.length > 8 && sink != null) {
                try { sink.accept(payload); } catch (Exception ignored) {}
            }
        }

        @Override protected void onPong(WebSocketFrame pong) {}
        @Override protected void onException(IOException e) {
            if (audioClient == this) audioClient = null;
        }
    }
}
