package com.applecase.gateway.net;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 协议消息封装与编解码。对应 docs/PROTOCOL.md 的通用信封格式。
 *
 * 信封:{ v, id, ref?, type, ts, token, data }
 * 所有 MQTT payload 均为本类的 JSON 序列化形式。
 */
public class Protocol {

    public static final int VERSION = 1;

    // 消息类型(见 PROTOCOL.md 4.2)
    public static final String T_HELLO        = "HELLO";
    public static final String T_HEARTBEAT    = "HEARTBEAT";
    public static final String T_BYE          = "BYE";
    public static final String T_SMS_SEND     = "SMS_SEND";   // CL->GW
    public static final String T_SMS_IN       = "SMS_IN";     // GW->CL
    public static final String T_SMS_OUT      = "SMS_OUT";    // GW->CL 发送回执
    public static final String T_CMD_ACK      = "CMD_ACK";
    // 阶段二/三占位类型
    public static final String T_CALL_INCOMING = "CALL_INCOMING";
    public static final String T_CALL_STATE    = "CALL_STATE";
    public static final String T_CALL_PLACE    = "CALL_PLACE";
    public static final String T_CALL_CONTROL  = "CALL_CONTROL";
    public static final String T_SIM_STATE     = "SIM_STATE";
    public static final String T_AUDIO_CTRL    = "AUDIO_CTRL";
    public static final String T_HISTORY      = "HISTORY";   // GW→Web:历史记录(短信/通话)

    /** 鉴权密钥,由 GatewayService 启动时注入(取自 BuildConfig.PRESHARED_KEY)。 */
    private static String presharedKey = "dev-secret-change-me";

    public static void setPresharedKey(String key) {
        if (key != null && !key.isEmpty()) presharedKey = key;
    }

    /** 计算鉴权 token = SHA256(presharedKey) 的十六进制。 */
    public static String computeToken() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(presharedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 一定存在,不会到这
            return "";
        }
    }

    /** 校验收到的消息 token。骨架期:与本端计算的 token 相等则通过。 */
    public static boolean verifyToken(String token) {
        return token != null && token.equals(computeToken());
    }

    // ============ 构造消息 ============

    /** 构造一条带通用信封的消息。data 已是构造好的 JSONObject。 */
    public static Message envelope(String type, JSONObject data) {
        return envelope(type, null, data);
    }

    public static Message envelope(String type, String ref, JSONObject data) {
        Message m = new Message();
        m.v = VERSION;
        m.id = UUID.randomUUID().toString();
        m.ref = ref;
        m.type = type;
        m.ts = System.currentTimeMillis();
        m.token = computeToken();
        m.data = data;
        return m;
    }

    // ============ 解析消息 ============

    /** 从 JSON 字符串解析为 Message。格式错误抛 JSONException。 */
    public static Message parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Message m = new Message();
        m.v = o.optInt("v", 0);
        m.id = o.optString("id", "");
        m.ref = o.optString("ref", null);
        m.type = o.optString("type", "");
        m.ts = o.optLong("ts", 0);
        m.token = o.optString("token", "");
        m.data = o.optJSONObject("data");
        return m;
    }

    /** Message 序列化为发送用 JSON 字符串。 */
    public static String toJson(Message m) {
        JSONObject o = new JSONObject();
        try {
            o.put("v", m.v);
            o.put("id", m.id);
            if (m.ref != null) o.put("ref", m.ref);
            o.put("type", m.type);
            o.put("ts", m.ts);
            o.put("token", m.token);
            if (m.data != null) o.put("data", m.data);
        } catch (JSONException e) {
            // JSONObject.put 不会失败
        }
        return o.toString();
    }

    /** 简单的消息对象。 */
    public static class Message {
        public int v;
        public String id;
        public String ref;   // 可空,仅回执类带
        public String type;
        public long ts;
        public String token;
        public JSONObject data;

        public boolean isType(String t) { return t.equals(type); }
        @Override public String toString() { return type + "(" + id + ")"; }
    }
}
