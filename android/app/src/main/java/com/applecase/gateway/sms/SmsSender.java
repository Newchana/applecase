package com.applecase.gateway.sms;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

import com.applecase.gateway.net.MqttClient;
import com.applecase.gateway.net.Protocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 短信发送器。接收 SMS_SEND 指令 → 发短信 → 回 SMS_OUT 回执。
 *
 * 关键点(调研结论):
 * - SmsManager.sendTextMessage 持有 SEND_SMS 即可发,不要求是默认 SMS app。
 * - 发出的短信不会写入系统短信库(那需要默认 SMS app role),但通话本身成功。
 * - 双卡:Android 5.1+ 用 SubscriptionManager 获取 SmsManager,指定 subscriptionId。
 *
 * 回执机制:用 sentIntent / deliveryIntent 两个 PendingIntent 捕获"发送结果"和"送达结果",
 * 通过 SmsSentReceiver(动态注册)接收后转为 SMS_OUT 消息。
 */
public class SmsSender {

    private static final String TAG = "AC.SmsTx";
    public static final String ACTION_SMS_SENT = "com.applecase.gateway.SMS_SENT";
    public static final String ACTION_SMS_DELIVERED = "com.applecase.gateway.SMS_DELIVERED";
    public static final String EXTRA_REF = "ref";       // 原指令消息 id,用于关联回执
    public static final String EXTRA_RESULT = "result"; // 结果码

    private final Context context;
    private final MqttClient mqtt;

    public SmsSender(Context context, MqttClient mqtt) {
        this.context = context;
        this.mqtt = mqtt;
    }

    /**
     * 发送一条短信(响应 SMS_SEND 指令)。
     * @param to 收件人号码
     * @param body 短信内容
     * @param simSlot 卡槽(0/1)
     * @param ref 原指令消息 id,用于回执关联
     */
    public void send(String to, String body, int simSlot, String ref) {
        try {
            SmsManager sm = resolveSmsManager(simSlot);

            // 长短信分段
            ArrayList<String> parts = sm.divideMessage(body);
            if (parts.isEmpty()) {
                sendOutResult(ref, "failed", "EMPTY_BODY");
                return;
            }

            // 为每段建立 sent / delivered PendingIntent,带 ref 便于关联
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();
            // 用同一 ref,SmsSentReceiver 收到任一即代表结果(简化:多段用最后一次判)
            for (int i = 0; i < parts.size(); i++) {
                sentIntents.add(buildIntent(ACTION_SMS_SENT, ref, i, parts.size()));
                deliveredIntents.add(buildIntent(ACTION_SMS_DELIVERED, ref, i, parts.size()));
            }

            sm.sendMultipartTextMessage(to, null, parts, sentIntents, deliveredIntents);
            Log.i(TAG, "sent SMS to=" + to + " slot=" + simSlot + " parts=" + parts.size());

        } catch (SecurityException se) {
            Log.e(TAG, "no SEND_SMS permission: " + se.getMessage());
            sendOutResult(ref, "failed", "NO_PERMISSION");
        } catch (Exception e) {
            Log.e(TAG, "send failed: " + e.getMessage());
            sendOutResult(ref, "failed", "SEND_FAILED");
        }
    }

    private PendingIntent buildIntent(String action, String ref, int partIdx, int partTotal) {
        Intent i = new Intent(action);
        i.setPackage(context.getPackageName());
        i.putExtra(EXTRA_REF, ref);
        i.putExtra("partIdx", partIdx);
        i.putExtra("partTotal", partTotal);
        // 多段共享同一 action,需用唯一 requestCode 区分 PendingIntent
        int reqCode = (ref + ":" + partIdx).hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, reqCode, i, flags);
    }

    /** 根据卡槽获取对应 subscription 的 SmsManager。 */
    private SmsManager resolveSmsManager(int simSlot) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Android 12+:SmsManager 直接接受 subscriptionId 构造
            try {
                android.telephony.SubscriptionManager sm =
                        (android.telephony.SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    java.util.List<android.telephony.SubscriptionInfo> subs =
                            sm.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (android.telephony.SubscriptionInfo si : subs) {
                            if (si.getSimSlotIndex() == simSlot) {
                                return context.getSystemService(SmsManager.class)
                                        .createForSubscriptionId(si.getSubscriptionId());
                            }
                        }
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "no READ_PHONE_STATE, fallback default SmsManager");
            }
        }
        return context.getSystemService(SmsManager.class);
    }

    /** 由 SmsSentReceiver 调用,根据结果码发 SMS_OUT 回执。 */
    public void onSentResult(String ref, int resultCode) {
        boolean ok = (resultCode == android.app.Activity.RESULT_OK);
        sendOutResult(ref, ok ? "sent" : "failed", ok ? null : mapSentError(resultCode));
    }

    public void onDeliveredResult(String ref) {
        sendOutResult(ref, "delivered", null);
    }

    private String mapSentError(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "GENERIC_FAILURE";
            case SmsManager.RESULT_ERROR_NO_SERVICE: return "NO_SERVICE";
            case SmsManager.RESULT_ERROR_NULL_PDU: return "NULL_PDU";
            case SmsManager.RESULT_ERROR_RADIO_OFF: return "RADIO_OFF";
            default: return "ERROR_" + code;
        }
    }

    private void sendOutResult(String ref, String status, String error) {
        try {
            JSONObject data = new JSONObject();
            data.put("ref", ref);
            data.put("status", status);   // sent | delivered | failed
            if (error != null) data.put("error", error);
            Protocol.Message out = Protocol.envelope(Protocol.T_SMS_OUT, ref, data);
            mqtt.publishEvent("event/sms/out", out);
            Log.i(TAG, "SMS_OUT ref=" + ref + " status=" + status + (error != null ? " err=" + error : ""));
        } catch (JSONException e) {
            Log.w(TAG, "build SMS_OUT failed: " + e.getMessage());
        }
    }
}
