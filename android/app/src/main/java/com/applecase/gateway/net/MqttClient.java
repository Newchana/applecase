package com.applecase.gateway.net;

import android.util.Log;

import com.applecase.gateway.BuildConfig;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * MQTT 指令/事件通道(基于 Eclipse Paho)。
 *
 * 设计:
 * - MqttAsyncClient 异步,回调式 API。
 * - 自动重连:connectOptions.setAutomaticReconnect(true),Paho 内置指数退避。
 * - 订阅 deviceId 命名空间下的命令 topic,统一回调 onMessage。
 * - 发布 QoS 1 保证送达。
 *
 * Topic 规范见 docs/PROTOCOL.md 第 3 节。
 */
public class MqttClient implements MqttCallbackExtended {

    private static final String TAG = "AC.Mqtt";

    private final String deviceId;
    private final String brokerHost;
    private final int brokerPort;
    private final String username;
    private final String password;
    private final String base; // applecase/{deviceId}/

    private MqttAsyncClient client;
    private volatile boolean connected = false;
    private Consumer<Protocol.Message> messageHandler;
    private Runnable onConnectCallback;
    /** 首连重试器:Paho 的 automaticReconnect 只在首次连接成功后才生效, 首连失败需要自己兜底重试。 */
    private java.util.concurrent.ScheduledExecutorService firstConnectRetry;
    private volatile boolean closed = false;

    public MqttClient(String deviceId, String brokerHost, int brokerPort,
                      String username, String password) {
        this.deviceId = deviceId;
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
        this.username = username;
        this.password = password;
        this.base = "applecase/" + deviceId + "/";
        Protocol.setPresharedKey(BuildConfig.PRESHARED_KEY);
    }

    public void setMessageHandler(Consumer<Protocol.Message> handler) {
        this.messageHandler = handler;
    }

    public void setOnConnectCallback(Runnable cb) { this.onConnectCallback = cb; }

    public boolean isConnected() { return connected; }

    /** 连接 broker。 */
    public void connect() {
        try {
            String uri = "tcp://" + brokerHost + ":" + brokerPort;
            String clientId = "gw-" + deviceId + "-" + UUID.randomUUID().toString().substring(0, 8);
            client = new MqttAsyncClient(uri, clientId, new MemoryPersistence());
            client.setCallback(this);

            client.connect(opts(), null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken asyncActionToken) {
                    Log.i(TAG, "connected to " + uri);
                }
                @Override public void onFailure(IMqttToken asyncActionToken, Throwable e) {
                    Log.w(TAG, "connect failed: " + e.getMessage() + " (will retry)");
                    connected = false;
                    scheduleFirstConnectRetry();
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "connect setup failed: " + e.getMessage());
            scheduleFirstConnectRetry();
        }
    }

    /** 首连失败后的兜底重试:每 10s 一次,连上或 close 后停。 */
    private void scheduleFirstConnectRetry() {
        if (firstConnectRetry != null || closed) return;
        firstConnectRetry = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        firstConnectRetry.scheduleWithFixedDelay(() -> {
            if (closed || connected || client == null) {
                if (firstConnectRetry != null) firstConnectRetry.shutdown();
                firstConnectRetry = null;
                return;
            }
            try {
                Log.i(TAG, "retrying first connect...");
                client.connect(opts(), null, new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken t) { /* connectComplete 回调接手 */ }
                    @Override public void onFailure(IMqttToken t, Throwable e) {
                        Log.w(TAG, "retry connect failed: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "retry connect error: " + e.getMessage());
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private MqttConnectOptions opts() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1); // 默认 3.1("MQIsdp")部分 broker 拒收
        opts.setAutomaticReconnect(true);   // 断线后自动重连(仅对已成功连过的会话)
        opts.setCleanSession(true);
        opts.setConnectionTimeout(8);       // 须小于首连重试间隔,避免"已在连接中"撞车
        opts.setKeepAliveInterval(30);
        opts.setUserName(username);
        opts.setPassword(password.toCharArray());
        opts.setMaxInflight(20);
        return opts;
    }

    // ============ MqttCallbackExtended ============

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        // reconnect=true 表示是自动重连成功,需重新订阅
        connected = true;
        Log.i(TAG, "connectComplete (reconnect=" + reconnect + ")");
        subscribeCommands();
        if (onConnectCallback != null) onConnectCallback.run();
    }

    @Override
    public void connectionLost(Throwable cause) {
        connected = false;
        Log.w(TAG, "connection lost: " + (cause == null ? "null" : cause.getMessage()));
        // Paho 自动重连会触发 connectComplete
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String json = new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
            Protocol.Message msg = Protocol.parse(json);
            if (!Protocol.verifyToken(msg.token)) {
                Log.w(TAG, "auth fail, drop " + msg.type + " from " + topic);
                return;
            }
            if (messageHandler != null) messageHandler.accept(msg);
        } catch (Exception e) {
            Log.w(TAG, "parse message failed: " + e.getMessage());
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS 1/2 投递完成,骨架期不处理
    }

    // ============ 订阅 ============

    private void subscribeCommands() {
        // 注意:必须精确订阅 "cmd" 主题,不能用 "cmd/#" 通配 ——
        // 否则自己发布的 cmd/ack 会被自己收到,形成 UNSUPPORTED 回执死循环(实测踩坑)。
        String filter = base + "cmd";
        try {
            client.subscribe(filter, 1, new IMqttMessageListener() {
                @Override public void messageArrived(String topic, MqttMessage message) {
                    MqttClient.this.messageArrived(topic, message);
                }
            });
            Log.i(TAG, "subscribed " + filter);
        } catch (MqttException e) {
            Log.w(TAG, "subscribe failed: " + e.getMessage());
        }
    }

    // ============ 发布 API ============

    /** 发布事件消息到指定 sub-topic(如 event/sms/in)。 */
    public void publishEvent(String subTopic, Protocol.Message msg) {
        publish(base + subTopic, msg, false);
    }

    /** 发布 retained 状态(如 status)。 */
    public void publishRetained(String subTopic, Protocol.Message msg) {
        publish(base + subTopic, msg, true);
    }

    private void publish(String fullTopic, Protocol.Message msg, boolean retained) {
        if (client == null || !connected) {
            Log.w(TAG, "not connected, drop publish to " + fullTopic);
            return;
        }
        try {
            MqttMessage m = new MqttMessage(Protocol.toJson(msg).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            m.setQos(1);
            m.setRetained(retained);
            client.publish(fullTopic, m);
        } catch (MqttException e) {
            Log.w(TAG, "publish to " + fullTopic + " failed: " + e.getMessage());
        }
    }

    /** 清除 retained 状态(优雅下线)。 */
    public void clearRetained(String subTopic) {
        if (client == null || !connected) return;
        try {
            MqttMessage m = new MqttMessage(new byte[0]);
            m.setRetained(true);
            client.publish(base + subTopic, m);
        } catch (MqttException ignored) {}
    }

    public void disconnect() {
        connected = false;
        closed = true;
        if (firstConnectRetry != null) {
            firstConnectRetry.shutdownNow();
            firstConnectRetry = null;
        }
        if (client != null) {
            try {
                if (client.isConnected()) client.disconnectForcibly(2000);
                client.close();
            } catch (MqttException ignored) {}
        }
    }
}
