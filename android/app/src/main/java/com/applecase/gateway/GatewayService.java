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

import com.applecase.gateway.net.MqttClient;
import com.applecase.gateway.net.Protocol;
import com.applecase.gateway.net.UdpAudioSocket;
import com.applecase.gateway.sms.SmsReceiver;
import com.applecase.gateway.sms.SmsSentReceiver;
import com.applecase.gateway.sms.SmsSender;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.function.Consumer;

/**
 * 网关主服务。前台服务,持有:
 * - MqttClient:指令/事件通道
 * - UdpAudioSocket:音频通道占位
 * - SmsSender:发短信
 * - SmsSentReceiver:动态注册,捕获发送结果
 * - LocalSmsRelay:接收 SmsReceiver 转来的本地短信事件,转为 MQTT SMS_IN 推送
 *
 * 生命周期:onCreate 建各模块并连 MQTT;onDestroy 反注册并断连。
 */
public class GatewayService extends Service {

    private static final String TAG = "AC.Gateway";
    private static final String CHANNEL_ID = "gateway_service";
    private static final int NOTIF_ID = 1;

    // 配置(骨架期硬编码,阶段二改为从 SharedPreferences/资源读取)
    private static final String BROKER_HOST = "10.147.17.1";   // ← 改成你的 broker ZeroTier IP
    private static final int BROKER_PORT = 1883;
    private static final String MQTT_USER = "applecase";
    private static final String MQTT_PASS = "change-me";

    private MqttClient mqtt;
    private SmsSender smsSender;
    private UdpAudioSocket udpAudio;

    // 动态注册的 receivers(需在 onDestroy 反注册)
    private BroadcastReceiver smsSentReceiver;
    private BroadcastReceiver localSmsRelay;

    private java.util.concurrent.ScheduledExecutorService heartbeatExec;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "GatewayService onCreate");

        String deviceId = resolveDeviceId();
        Protocol.setPresharedKey(BuildConfig.PRESHARED_KEY);

        mqtt = new MqttClient(deviceId, BROKER_HOST, BROKER_PORT, MQTT_USER, MQTT_PASS);
        smsSender = new SmsSender(this, mqtt);
        udpAudio = new UdpAudioSocket(UdpAudioSocket.DEFAULT_PORT);

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
        udpAudio.start();
        Log.i(TAG, "all modules started");
    }

    /** 处理来自客户端的指令(SMS_SEND / CALL_* / AUDIO_CTRL)。 */
    private void handleCommand(Protocol.Message msg) {
        Log.i(TAG, "cmd received: " + msg);
        try {
            switch (msg.type) {
                case Protocol.T_SMS_SEND:
                    handleSmsSend(msg);
                    break;
                default:
                    // 阶段一不支持的指令,回 UNSUPPORTED
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
        int slot = d.optInt("simSlot", 0);
        smsSender.send(to, body, slot, msg.id);
        // 立即回 ack 表示已受理,实际结果走 SMS_OUT
        ack(msg, true, null, null);
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
            JSONArray sims = new JSONArray();
            // 骨架期:简单填两张卡占位,阶段二从 TelephonyManager 读真实状态
            data.put("sims", sims);
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

    private String resolveDeviceId() {
        // 优先用 Build.SERIAL 或 ANDROID_ID;骨架期兜底用固定值
        try {
            String s = android.os.Build.SERIAL;
            if (s != null && !s.isEmpty() && !"unknown".equals(s)) return "Q968-" + s;
        } catch (Exception ignored) {}
        return BuildConfig.DEFAULT_DEVICE_ID;
    }

    /** 尝试获取 ZeroTier 接口(zt*)的 IP。 */
    private String getZeroTierIp() {
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
        if (heartbeatExec != null) heartbeatExec.shutdownNow();
        udpAudio.stop();
        if (localSmsRelay != null) LocalBroadcastManager.getInstance(this).unregisterReceiver(localSmsRelay);
        if (smsSentReceiver != null) unregisterReceiver(smsSentReceiver);
        if (mqtt != null) mqtt.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
