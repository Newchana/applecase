package com.applecase.gateway.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 短信发送结果接收器(动态注册)。
 * 捕获 SmsSender 发出的 sentIntent / deliveryIntent 回调,
 * 转交给 SmsSender 生成 SMS_OUT 回执。
 */
public class SmsSentReceiver extends BroadcastReceiver {

    public interface Callback {
        void onSent(String ref, int resultCode);
        void onDelivered(String ref);
    }

    private final Callback callback;

    public SmsSentReceiver(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String ref = intent.getStringExtra(SmsSender.EXTRA_REF);
        if (ref == null) return;

        if (SmsSender.ACTION_SMS_SENT.equals(action)) {
            callback.onSent(ref, getResultCode());
        } else if (SmsSender.ACTION_SMS_DELIVERED.equals(action)) {
            callback.onDelivered(ref);
        }
    }
}
