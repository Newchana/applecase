package com.applecase.gateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 网关配置(SharedPreferences 持久化,阶段二起替代硬编码)。
 *
 * 优先级:SharedPreferences 里的用户配置 > 代码默认值。
 * MainActivity 的配置界面写入;GatewayService 启动时读取。
 * deviceId 特殊:保存值为空时回退到 BuildConfig.DEFAULT_DEVICE_ID。
 */
public class Config {

    private static final String PREFS = "applecase";

    private static final String K_BROKER_HOST = "broker_host";
    private static final String K_BROKER_PORT = "broker_port";
    private static final String K_MQTT_USER  = "mqtt_user";
    private static final String K_MQTT_PASS  = "mqtt_pass";
    private static final String K_DEVICE_ID  = "device_id";
    private static final String K_DEFAULT_SLOT = "default_sim_slot";
    private static final String K_SIM0_NUMBER = "sim0_number";
    private static final String K_SIM1_NUMBER = "sim1_number";

    // 默认值(与阶段一硬编码一致,保证升级无感)
    public static final String DEF_BROKER_HOST = "10.147.17.1";
    public static final int    DEF_BROKER_PORT = 1883;
    public static final String DEF_MQTT_USER  = "applecase";
    public static final String DEF_MQTT_PASS  = "change-me";
    public static final int    DEF_SIM_SLOT   = 0;

    private final SharedPreferences sp;

    public Config(Context context) {
        this.sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String brokerHost() { return sp.getString(K_BROKER_HOST, DEF_BROKER_HOST); }
    public int brokerPort() { return sp.getInt(K_BROKER_PORT, DEF_BROKER_PORT); }
    public String mqttUser() { return sp.getString(K_MQTT_USER, DEF_MQTT_USER); }
    public String mqttPass() { return sp.getString(K_MQTT_PASS, DEF_MQTT_PASS); }

    /** deviceId:未配置时回退 BuildConfig 默认值。 */
    public String deviceId() {
        String id = sp.getString(K_DEVICE_ID, "");
        return (id == null || id.isEmpty()) ? BuildConfig.DEFAULT_DEVICE_ID : id;
    }

    /** 默认发短信/拨号用的卡槽(指令未带 simSlot 时用)。 */
    public int defaultSimSlot() { return sp.getInt(K_DEFAULT_SLOT, DEF_SIM_SLOT); }

    /** 卡槽对应的手动配置号码(国内 SIM 多不写 MSISDN,AT 也查不到,人工填一次)。空串=未配置。 */
    public String simNumber(int slot) {
        String n = sp.getString(slot == 1 ? K_SIM1_NUMBER : K_SIM0_NUMBER, "");
        return n == null ? "" : n.trim();
    }

    public void save(String brokerHost, int brokerPort, String user, String pass, String deviceId) {
        sp.edit()
                .putString(K_BROKER_HOST, brokerHost)
                .putInt(K_BROKER_PORT, brokerPort)
                .putString(K_MQTT_USER, user)
                .putString(K_MQTT_PASS, pass)
                .putString(K_DEVICE_ID, deviceId != null ? deviceId.trim() : "")
                .apply();
    }

    public void saveSimNumbers(String sim0, String sim1) {
        sp.edit()
                .putString(K_SIM0_NUMBER, sim0 != null ? sim0.trim() : "")
                .putString(K_SIM1_NUMBER, sim1 != null ? sim1.trim() : "")
                .apply();
    }
}
