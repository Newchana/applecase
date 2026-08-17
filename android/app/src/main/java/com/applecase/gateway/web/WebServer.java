package com.applecase.gateway.web;

import android.content.Context;
import android.util.Log;

import com.applecase.gateway.net.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * 内嵌 Web 服务器(Web 客户端)。
 *
 * 两个实例共用一个 WebHub:
 * - HTTP  :8080  —— 基础可用(短信/电话控制、下行收听)
 * - HTTPS :8443  —— 自签证书(assets/web/server.p12);麦克风需要"安全上下文",
 *                    HTTP 下浏览器禁用 getUserMedia,语音对讲必须走这个
 *
 * 通道:
 * - GET /      单页客户端(assets/web/index.html)
 * - WS  /ws    控制:JSON 信封与 MQTT 协议一致(免 token);服务端推全部事件
 * - WS  /audio 音频:二进制帧(8 字节头 + PCM16,同 UDP 帧格式)
 */
public class WebServer extends NanoWSD {

    private static final String TAG = "AC.Web";
    public static final int HTTP_PORT = 8080;
    public static final int HTTPS_PORT = 8443;
    private static final char[] KEYSTORE_PASS = "applecase".toCharArray();

    private final Context context;
    private final WebHub hub;
    private final boolean secure;

    /** 控制通道指令回调(→ GatewayService.handleWebCommand)。 */
    public interface CommandSink { void onWebCommand(Protocol.Message msg); }
    /** 上行音频帧回调(→ VoiceBridge.feedUplink)。 */
    public interface AudioSink { void accept(byte[] frame); }

    /** hub 调 keepalive 用的窄接口。 */
    interface WebSocketLike { void pingIfOpen(byte[] payload) throws IOException; }

    public WebServer(Context context, WebHub hub, int port, boolean secure) {
        super(port);
        this.context = context;
        this.hub = hub;
        this.secure = secure;
    }

    /** HTTPS 实例启动前调用:加载自签 PKCS12。 */
    public void enableSecure() throws Exception {
        try (InputStream in = context.getAssets().open("web/server.p12")) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(in, KEYSTORE_PASS);
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory
                    .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, KEYSTORE_PASS);
            makeSecure(NanoHTTPD.makeSSLSocketFactory(ks, kmf), null);
        }
    }

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
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "<a href='/'>back</a>");
    }

    private String readIndex() {
        try (InputStream in = context.getAssets().open("web/index.html")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (IOException e) {
            return "<h1>applecase web</h1><p>index.html read failed: " + e.getMessage() + "</p>";
        }
    }

    // ============ 控制通道 /ws ============

    class CtrlWs extends WebSocket implements WebSocketLike {
        private java.util.concurrent.ScheduledFuture<?> ka;

        CtrlWs(IHTTPSession h) { super(h); }

        @Override protected void onOpen() {
            hub.ctrlClients.add(this);
            ka = hub.armKeepalive(this);
            Log.i(TAG, "ctrl + (" + hub.ctrlClients.size() + ")" + (secure ? " [tls]" : ""));
        }

        @Override protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            if (ka != null) ka.cancel(false);
            hub.ctrlClients.remove(this);
        }

        @Override protected void onMessage(WebSocketFrame frame) {
            try {
                byte[] p = frame.getBinaryPayload();
                if (p == null || p.length == 0) return;
                Protocol.Message msg = Protocol.parse(new String(p, StandardCharsets.UTF_8));
                WebServer.CommandSink sink = hub.commandSink();
                if (sink != null) sink.onWebCommand(msg);
            } catch (Exception e) {
                try {
                    send("{\"type\":\"CMD_ACK\",\"data\":{\"ok\":false,\"error\":\"BAD_REQUEST\",\"detail\":\""
                            + (e.getMessage() == null ? "parse" : e.getMessage()) + "\"}}");
                } catch (Exception ignored) {}
            }
        }

        @Override protected void onPong(WebSocketFrame pong) {}
        @Override protected void onException(IOException e) { hub.ctrlClients.remove(this); }

        @Override public void pingIfOpen(byte[] payload) throws IOException {
            if (isOpen()) ping(payload);
        }
    }

    // ============ 音频通道 /audio ============

    class AudioWs extends WebSocket implements WebSocketLike {
        private java.util.concurrent.ScheduledFuture<?> ka;

        AudioWs(IHTTPSession h) { super(h); }

        @Override protected void onOpen() {
            hub.bindAudio(this);
            ka = hub.armKeepalive(this);
            Log.i(TAG, "audio client connected" + (secure ? " [tls]" : ""));
        }

        @Override protected void onClose(WebSocketFrame.CloseCode code, String reason, boolean remote) {
            if (ka != null) ka.cancel(false);
            hub.unbindAudioIf(this);
        }

        @Override protected void onMessage(WebSocketFrame frame) {
            byte[] payload = frame.getBinaryPayload();
            AudioSink s = hub.audioSink();
            if (payload != null && payload.length > 8 && s != null) {
                try { s.accept(payload); } catch (Exception ignored) {}
            }
        }

        @Override protected void onPong(WebSocketFrame pong) {}
        @Override protected void onException(IOException e) { hub.unbindAudioIf(this); }

        @Override public void pingIfOpen(byte[] payload) throws IOException {
            if (isOpen()) ping(payload);
        }
    }
}
