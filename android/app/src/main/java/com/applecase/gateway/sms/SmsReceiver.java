package com.applecase.gateway.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsMessage;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 短信接收器。
 *
 * 关键点(调研结论):
 * - android.provider.Telephony.SMS_RECEIVED 是隐式广播豁免项,Android 8+ 后台限制不适用,
 *   可静态注册后台接收。
 * - Android 13 targetSdk33 动态注册需 RECEIVER_EXPORTED,但本 receiver 是 manifest 静态注册,
 *   不受该 API 限制。
 * - 高优先级(priority=999)以便在本应用内尽早处理。
 *
 * 处理方式:解析短信 → 通过 LocalBroadcast 转交 GatewayService(后者持有 MQTT 通道统一发布)。
 * 不直接在此发 MQTT,因为 BroadcastReceiver 生命周期短,且 MQTT 客户端由 Service 管理。
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "AC.SmsRx";
    public static final String ACTION_SMS_RECEIVED = "com.applecase.gateway.SMS_RECEIVED_LOCAL";
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_FROM = "from";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_TIMESTAMP = "timestamp";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        // 从 pdu 数组解析短信
        Object[] pdus = (Object[]) intent.getExtras().get("pdus");
        if (pdus == null || pdus.length == 0) return;

        String format = intent.getExtras().getString("format");
        StringBuilder body = new StringBuilder();
        String sender = null;
        long timestamp = 0;

        for (Object pdu : pdus) {
            SmsMessage msg = parseMessage((byte[]) pdu, format);
            if (msg == null) continue;
            if (sender == null) sender = msg.getOriginatingAddress();
            if (timestamp == 0) timestamp = msg.getTimestampMillis();
            body.append(msg.getDisplayMessageBody());
        }

        if (sender == null) return;

        // subscription:判断是哪张卡(双卡)。Android 5.1+ 的 SMS_RECEIVED 带 subscription 字段
        int slot = intent.getExtras().getInt("slot", -1);
        if (slot < 0) slot = 0; // 兜底

        Log.i(TAG, "SMS from=" + sender + " slot=" + slot + " body=" +
                (body.length() > 40 ? body.substring(0, 40) + "..." : body));

        // 转交 GatewayService 处理(用 LocalBroadcast 避免跨进程,发到同一 app 内)
        Intent local = new Intent(ACTION_SMS_RECEIVED);
        local.setPackage(context.getPackageName());
        local.putExtra(EXTRA_SLOT, slot);
        local.putExtra(EXTRA_FROM, sender);
        local.putExtra(EXTRA_BODY, body.toString());
        local.putExtra(EXTRA_TIMESTAMP, timestamp > 0 ? timestamp : System.currentTimeMillis());
        context.sendBroadcast(local);
    }

    private SmsMessage parseMessage(byte[] pdu, String format) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return SmsMessage.createFromPdu(pdu, format);
            } else {
                return SmsMessage.createFromPdu(pdu);
            }
        } catch (Exception e) {
            Log.w(TAG, "parse pdu failed: " + e.getMessage());
            return null;
        }
    }
}
