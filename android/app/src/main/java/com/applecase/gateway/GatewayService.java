package com.applecase.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.applecase.gateway.audio.VoiceBridge;
import com.applecase.gateway.call.CallController;
import com.applecase.gateway.call.CallMonitor;
import com.applecase.gateway.net.MqttClient;
import com.applecase.gateway.net.Protocol;
import com.applecase.gateway.net.WebMqttClient;
import com.applecase.gateway.web.WebServer;
import com.applecase.gateway.sim.SimMonitor;
import com.applecase.gateway.sms.SmsReceiver;
import com.applecase.gateway.sms.SmsSentReceiver;
import com.applecase.gateway.sms.SmsSender;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.function.Consumer;

/**
 * 网关主服务。前台服务,持有:
 * - MqttClient:指令/事件通道
 * - UdpAudioSocket:音频通道占位(阶段三填实)
 * - SmsSender / SmsSentReceiver / LocalSmsRelay:短信收发链路
 * - SimMonitor:双卡状态/信号(阶段二)
 * - CallMonitor / CallController:来电监听、拨号与通话控制(阶段二)
 *
 * 生命周期:onCreate 建各模块并连 MQTT;onDestroy 反注册并断连。
 */
public class GatewayService extends Service {

    private static final String TAG = "AC.Gateway";
    private static final String CHANNEL_ID = "gateway_service";
    private static final int NOTIF_ID = 1;

    private MqttClient mqtt;
    private SmsSender smsSender;
    private VoiceBridge voiceBridge;
    private SimMonitor simMonitor;
    private CallMonitor callMonitor;
    private CallController callController;
    private Config config;
    private WebServer webServer;
    private String deviceId;

    // 动态注册的 receivers(需在 onDestroy 反注册)
    private BroadcastReceiver smsSentReceiver;
    private BroadcastReceiver localSmsRelay;

    private java.util.concurrent.ScheduledExecutorService heartbeatExec;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "GatewayService onCreate");

        config = new Config(this);
        String deviceId = config.deviceId();
        Protocol.setPresharedKey(BuildConfig.PRESHARED_KEY);

        this.deviceId = deviceId;

        // Web 服务(阶段四):浏览器直接访问 http://<本机IP>:8080,不依赖 broker
        webServer = new WebServer(this, this::handleWebCommand);
        webServer.setAudioSink(frame -> voiceBridge.feedUplink(frame));
        try { webServer.start(); } catch (Exception e) {
            Log.w(TAG, "web server start failed: " + e.getMessage());
        }

        // MQTT + 事件扇出:所有事件(短信/来电/SIM/ACK)同步广播给 Web 客户端
        mqtt = new WebMqttClient(deviceId, config.brokerHost(), config.brokerPort(),
                config.mqttUser(), config.mqttPass(), webServer::broadcast);
        smsSender = new SmsSender(this, mqtt);
        voiceBridge = new VoiceBridge(this);
        simMonitor = new SimMonitor(this, mqtt);
        // 本机号码:卡上多不存 MSISDN,读用户在配置里手动填的
        simMonitor.setNumberResolver(config::simNumber);
        callMonitor = new CallMonitor(this, mqtt, simMonitor);
        callController = new CallController(this, simMonitor);
        // App 发起的通话:接通即把 Q968 本机哑巴化(声音全走 iPhone 桥),结束恢复
        callMonitor.setOnCallEnd(callController::restoreLocal);

        // 1. 注册短信发送结果 receiver(动态)
        smsSentReceiver = new SmsSentReceiver(new SmsSentReceiver.Callback() {
            @Override public void onSent(String ref, int resultCode) { smsSender.onSentResult(ref, resultCode); }
            @Override public void onDelivered(String ref) { smsSender.onDeliveredResult(ref); }
        });
        IntentFilter sentFilter = new IntentFilter();
        sentFilter.addAction(SmsSender.ACTION_SMS_SENT);
        sentFilter.addAction(SmsSender.ACTION_SMS_DELIVERED);
        registerReceiver(smsSentReceiver, sentFilter);

        // 2. 注册本地短信事件 relay(SmsReceiver → 本服务 → MQTT)
        localSmsRelay = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SmsReceiver.ACTION_SMS_RECEIVED.equals(intent.getAction())) {
                    forwardIncomingSms(intent);
                }
            }
        };
        IntentFilter localFilter = new IntentFilter(SmsReceiver.ACTION_SMS_RECEIVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(localSmsRelay, localFilter);

        // 3. 配置 MQTT 消息处理(收到客户端指令)
        mqtt.setMessageHandler(this::handleCommand);
        mqtt.setOnConnectCallback(() -> {
            // 连上后立即发布 HELLO retained 状态
            publishHello(deviceId);
            startHeartbeat(deviceId);
        });

        startForeground(NOTIF_ID, buildNotification("苹果皮网关启动中"));
        mqtt.connect();
        simMonitor.start();
        callMonitor.start();
        Log.i(TAG, "all modules started");
    }

    /** 处理来自客户端的指令(SMS_SEND / CALL_* / AUDIO_CTRL)。 */
    private void handleCommand(Protocol.Message msg) {
        Log.i(TAG, "cmd received: " + msg);
        // 防线:GW→CL 方向的事件类消息不是指令,静默忽略(防回环,不该出现在 cmd 主题上)
        if (Protocol.T_CMD_ACK.equals(msg.type) || Protocol.T_SMS_OUT.equals(msg.type)
                || Protocol.T_SMS_IN.equals(msg.type) || Protocol.T_HELLO.equals(msg.type)
                || Protocol.T_HEARTBEAT.equals(msg.type) || Protocol.T_BYE.equals(msg.type)) {
            Log.w(TAG, "event-type message on cmd topic, ignore: " + msg.type);
            return;
        }
        try {
            switch (msg.type) {
                case Protocol.T_SMS_SEND:
                    handleSmsSend(msg);
                    break;
                case Protocol.T_CALL_PLACE:
                    handleCallPlace(msg);
                    break;
                case Protocol.T_CALL_CONTROL:
                    handleCallControl(msg);
                    break;
                case Protocol.T_AUDIO_CTRL:
                    handleAudioCtrl(msg);
                    break;
                case "GET_STATE":
                    // Web 客户端上线/刷新拉当前状态(HELLO 经扇出回到 Web)
                    publishHello(this.deviceId);
                    break;
                default:
                    // 本版本不支持的指令,回 UNSUPPORTED
                    ack(msg, false, "UNSUPPORTED", null);
                    Log.w(TAG, "unsupported cmd type: " + msg.type);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleCommand error: " + e.getMessage());
            ack(msg, false, "BAD_REQUEST", e.getMessage());
        }
    }

    private void handleSmsSend(Protocol.Message msg) throws JSONException {
        JSONObject d = msg.data;
        String to = d.getString("to");
        String body = d.getString("body");
        int slot = d.optInt("simSlot", config.defaultSimSlot());
        smsSender.send(to, body, slot, msg.id);
        // 立即回 ack 表示已受理,实际结果走 SMS_OUT
        ack(msg, true, null, null);
    }

    /** CALL_PLACE:{to, simSlot?} → 用指定卡拨号。 */
    private void handleCallPlace(Protocol.Message msg) throws JSONException {
        JSONObject d = msg.data;
        String to = d.getString("to");
        int slot = d.optInt("simSlot", config.defaultSimSlot());
        // 先记录去电号码(广播不一定带),再拨
        callMonitor.noteOutgoing(to, slot);
        String err = callController.place(to, slot);
        if (err == null) {
            Log.i(TAG, "call placed to=" + to + " slot=" + slot);
            // 苹果皮形态:呼叫一发出就掐掉本机声音(响铃/彩铃也不能漏),结束恢复
            callController.silenceLocal();
            ack(msg, true, null, null);
        } else {
            ack(msg, false, err, "slot=" + slot + " to=" + to);
        }
    }

    /**
     * AUDIO_CTRL:{action:"start"|"stop", dir:"up"|"down"|"both", codec:"pcm16",
     *             params:{ip:"客户端IP", port:客户端UDP端口}} → 语音桥接控制。
     * 成功 ack data 里带回实际 codec 与 GW 的 UDP 端口。
     */
    private void handleAudioCtrl(Protocol.Message msg) throws JSONException {
        JSONObject d = msg.data;
        String action = d.getString("action");
        if ("start".equals(action)) {
            String dir = d.optString("dir", "both");
            String codec = d.optString("codec", VoiceBridge.CODEC);
            if (!VoiceBridge.CODEC.equals(codec)) {
                ack(msg, false, "UNSUPPORTED", "codec " + codec + ", only " + VoiceBridge.CODEC);
                return;
            }
            JSONObject params = d.optJSONObject("params");
            boolean wsMode = params != null && params.optBoolean("ws", false);
            String err;
            String transport;
            if (wsMode) {
                // Web 客户端:音频走 WebSocket(浏览器不能发裸 UDP)
                transport = "ws";
                fi.iki.elonen.NanoWSD.WebSocket aws = webServer.pollAudioSocket();
                err = aws != null ? voiceBridge.startWeb(dir, aws) : "audio websocket not connected";
            } else {
                transport = "udp";
                String ip = params != null ? params.optString("ip", null) : null;
                int port = params != null ? params.optInt("port", 0) : 0;
                err = voiceBridge.start(dir, ip, port);
            }
            if (err == null) {
                JSONObject ok = new JSONObject();
                try {
                    ok.put("codec", VoiceBridge.CODEC);
                    ok.put("transport", transport);
                    if (!wsMode) ok.put("port", VoiceBridge.UDP_PORT);
                } catch (JSONException ignored) {}
                ackData(msg, ok);
            } else {
                ack(msg, false, "SEND_FAILED", err);
            }
        } else if ("stop".equals(action)) {
            voiceBridge.stop();
            ack(msg, true, null, null);
        } else {
            ack(msg, false, "BAD_REQUEST", "unknown action: " + action);
        }
    }

    /** CALL_CONTROL:{action:"answer"|"hangup"|"mute", on?} → 通话控制。 */
    private void handleCallControl(Protocol.Message msg) throws JSONException {
        JSONObject d = msg.data;
        String action = d.getString("action");
        String err;
        switch (action) {
            case "answer":
                err = callController.answer();
                if (err == null) callController.silenceLocal();
                break;
            case "hangup":
                err = callController.hangup();
                break;
            case "mute":
                err = callController.mute(d.optBoolean("on", true));
                break;
            default:
                ack(msg, false, "BAD_REQUEST", "unknown action: " + action);
                return;
        }
        if (err == null) ack(msg, true, null, null);
        else ack(msg, false, err, action);
    }

    /** Web 控制通道入口(/ws):与 MQTT 指令共用处理逻辑,回执经扇出回到 Web。 */
    private void handleWebCommand(Protocol.Message msg) {
        handleCommand(msg);
    }

    /** 收到 SmsReceiver 转发的短信,封装成 SMS_IN 发布。 */
    private void forwardIncomingSms(Intent intent) {
        try {
            JSONObject data = new JSONObject();
            data.put("id", "sms-" + System.currentTimeMillis());
            data.put("slot", intent.getIntExtra(SmsReceiver.EXTRA_SLOT, 0));
            data.put("from", intent.getStringExtra(SmsReceiver.EXTRA_FROM));
            data.put("body", intent.getStringExtra(SmsReceiver.EXTRA_BODY));
            data.put("ts", intent.getLongExtra(SmsReceiver.EXTRA_TIMESTAMP, System.currentTimeMillis()));
            Protocol.Message m = Protocol.envelope(Protocol.T_SMS_IN, data);
            mqtt.publishEvent("event/sms/in", m);
            Log.i(TAG, "SMS_IN published");
        } catch (JSONException e) {
            Log.w(TAG, "build SMS_IN failed: " + e.getMessage());
        }
    }

    // ============ HELLO / 心跳 ============

    private void publishHello(String deviceId) {
        try {
            JSONObject data = new JSONObject();
            data.put("deviceId", deviceId);
            data.put("fw", BuildConfig.VERSION_NAME);
            data.put("ip", getZeroTierIp());
            // 阶段二:真实双卡状态(卡槽/运营商/状态/信号档位)
            data.put("sims", simMonitor.getSimsJson());
            Protocol.Message hello = Protocol.envelope(Protocol.T_HELLO, data);
            mqtt.publishRetained("status", hello);
            Log.i(TAG, "HELLO published (retained)");
        } catch (Exception e) {
            Log.w(TAG, "publishHello failed: " + e.getMessage());
        }
    }

    private void startHeartbeat(String deviceId) {
        if (heartbeatExec != null && !heartbeatExec.isShutdown()) return;
        heartbeatExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        heartbeatExec.scheduleAtFixedRate(() -> {
            if (!mqtt.isConnected()) return;
            try {
                JSONObject data = new JSONObject();
                data.put("uptime", android.os.SystemClock.elapsedRealtime() / 1000);
                Protocol.Message hb = Protocol.envelope(Protocol.T_HEARTBEAT, data);
                mqtt.publishEvent("heartbeat", hb);
            } catch (Exception e) {
                Log.w(TAG, "heartbeat failed: " + e.getMessage());
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ============ 工具方法 ============

    private void ack(Protocol.Message req, boolean ok, String error, String detail) {
        try {
            JSONObject data = new JSONObject();
            data.put("ref", req.id);
            data.put("ok", ok);
            if (error != null) data.put("error", error);
            if (detail != null) data.put("detail", detail);
            Protocol.Message ack = Protocol.envelope(Protocol.T_CMD_ACK, req.id, data);
            mqtt.publishEvent("cmd/ack", ack);
        } catch (JSONException e) {
            Log.w(TAG, "build ack failed: " + e.getMessage());
        }
    }

    /** ack 成功时直接带 data 的变体(如 AUDIO_CTRL 返回实际 codec/端口)。 */
    private void ackData(Protocol.Message req, JSONObject data) {
        try {
            data.put("ref", req.id);
            data.put("ok", true);
            Protocol.Message ack = Protocol.envelope(Protocol.T_CMD_ACK, req.id, data);
            mqtt.publishEvent("cmd/ack", ack);
        } catch (Exception e) {
            Log.w(TAG, "build ackData failed: " + e.getMessage());
        }
    }

    /** 尝试获取 ZeroTier 接口(zt*)的 IP。 */    private String getZeroTierIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                String name = ni.getName();
                if (name != null && (name.startsWith("zt") || name.contains("zerotier"))) {
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (!a.isLoopbackAddress() && a.getHostAddress().contains(".")) {
                            return a.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    // ============ 前台服务通知 ============

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "网关服务", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent main = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("苹果皮网关")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被杀后自动重启
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "GatewayService onDestroy");
        // 优雅下线:BYE + 清 retained 状态,客户端立刻感知离线
        try {
            JSONObject bye = new JSONObject();
            bye.put("reason", "service destroyed");
            mqtt.publishRetained("status", Protocol.envelope(Protocol.T_BYE, bye));
        } catch (Exception ignored) {}
        if (heartbeatExec != null) heartbeatExec.shutdownNow();
        if (webServer != null) webServer.stopServer();
        voiceBridge.stop();
        callMonitor.stop();
        simMonitor.stop();
        if (localSmsRelay != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(localSmsRelay);
        if (smsSentReceiver != null) unregisterReceiver(smsSentReceiver);
        mqtt.clearRetained("status");
        mqtt.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
