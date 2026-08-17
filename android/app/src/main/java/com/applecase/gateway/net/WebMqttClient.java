package com.applecase.gateway.net;

import java.util.function.Consumer;

/**
 * MQTT 客户端的"双路"变体:发布的每条事件除走 MQTT 外,同步扇出给 Web 客户端。
 * (Web 页面不连 broker;短信/来电/SIM 状态/回执等事件从这里直达浏览器。)
 * MQTT 未连接时 MQTT 侧丢弃,但扇出照常 —— Web 在无 broker 环境下独立可用。
 */
public class WebMqttClient extends MqttClient {

    private final Consumer<Protocol.Message> fanout;

    public WebMqttClient(String deviceId, String host, int port, String user, String pass,
                         Consumer<Protocol.Message> fanout) {
        super(deviceId, host, port, user, pass);
        this.fanout = fanout;
    }

    @Override
    public void publishEvent(String subTopic, Protocol.Message msg) {
        super.publishEvent(subTopic, msg);
        if (fanout != null) fanout.accept(msg);
    }

    @Override
    public void publishRetained(String subTopic, Protocol.Message msg) {
        super.publishRetained(subTopic, msg);
        if (fanout != null) fanout.accept(msg);
    }
}
