package com.applecase.gateway.web;

import android.util.Log;

import com.applecase.gateway.net.Protocol;

import fi.iki.elonen.NanoWSD;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Web 通道共享状态(HTTP :8080 与 HTTPS :8443 两个 WebServer 实例共用):
 * 控制客户端集合、音频客户端绑定、事件广播、保活。
 */
public class WebHub {

    private static final String TAG = "AC.Web";
    private static final byte[] PING = {1};

    final Set<WebServer.CtrlWs> ctrlClients = ConcurrentHashMap.newKeySet();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 保活:NanoHTTPD socket 读超时 5s,空闲 WS 会被踢;ping→客户端回 pong 重置。 */
    final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor();

    private volatile WebServer.AudioWs audioClient;
    private volatile WebServer.CommandSink commandSink;
    private volatile WebServer.AudioSink audioSink;

    public void setCommandSink(WebServer.CommandSink s) { this.commandSink = s; }
    public void setAudioSink(WebServer.AudioSink s) { this.audioSink = s; }

    WebServer.CommandSink commandSink() { return commandSink; }
    WebServer.AudioSink audioSink() { return audioSink; }

    void bindAudio(WebServer.AudioWs ws) { audioClient = ws; }

    WebServer.AudioWs audioClient() { return audioClient; }

    void unbindAudioIf(WebServer.AudioWs ws) {
        if (audioClient == ws) audioClient = null;
    }

    /** 当前音频通道(AUDIO_CTRL start 时交给 VoiceBridge 绑定)。 */
    public NanoWSD.WebSocket pollAudioSocket() { return audioClient; }

    /** 广播一条消息给所有控制通道客户端(异步,不阻塞电话/音频线程)。 */
    public void broadcast(Protocol.Message m) {
        final String json = Protocol.toJson(m);
        io.execute(() -> {
            for (WebServer.CtrlWs ws : ctrlClients) {
                try { ws.send(json); } catch (Exception e) { ctrlClients.remove(ws); }
            }
        });
    }

    public int ctrlClientCount() { return ctrlClients.size(); }

    ScheduledFuture<?> armKeepalive(WebServer.WebSocketLike wsLike) {
        return keepalive.scheduleWithFixedDelay(() -> {
            try { wsLike.pingIfOpen(PING); } catch (Exception ignored) {}
        }, 2500, 2500, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        io.shutdown();
        keepalive.shutdownNow();
        Log.i(TAG, "web hub stopped");
    }
}
