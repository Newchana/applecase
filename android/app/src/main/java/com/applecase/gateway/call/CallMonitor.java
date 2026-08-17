package com.applecase.gateway.call;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.applecase.gateway.net.MqttClient;
import com.applecase.gateway.net.Protocol;
import com.applecase.gateway.sim.SimMonitor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 来电/去电状态监听(双卡感知)。
 *
 * 关键点(实测踩坑,Q968 ums9620 Android 13):
 * - PHONE_STATE 广播不带 subscription/slot extra(只有 state + incoming_number),
 *   无法直接判断通话在哪张卡上。
 * - 因此采用双通道:
 *   1) 每张卡独立的通话状态监听:TelephonyManager.createForSubscriptionId(subId) 上注册
 *      CallStateListener(API 31+)/ PhoneStateListener(以下)—— 只报告本卡的通话状态,
 *      这是卡槽归属的权威来源,驱动状态机;
 *   2) PHONE_STATE 广播仅作号码补充(RINGING 的来电号码;本 ROM 去电也带号码)。
 * - 号码在部分 ROM 上是第二条 RINGING 广播才带出,发布前聚合等待 600ms。
 *
 * 状态机(每张卡独立):
 *   IDLE→RINGING   : CALL_INCOMING + CALL_STATE(ringing)
 *   RINGING→OFFHOOK: CALL_STATE(active)(来电接听)
 *   IDLE→OFFHOOK   : CALL_STATE(active, dir=out)(本机/远程拨出)
 *   *→IDLE         : CALL_STATE(ended;若响铃后未接则为 missed)
 */
public class CallMonitor {

    private static final String TAG = "AC.CallRx";
    /** 号码新鲜期:广播拿到的号码只在这段时间内视为与监听事件相关。 */
    private static final long NUMBER_FRESH_MS = 5000;

    private final Context context;
    private final MqttClient mqtt;
    private final SimMonitor simMonitor;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 通话接通回调(参数 slot;去电 OFFHOOK 或来电接听)。 */
    private java.util.function.IntConsumer onCallActive;
    /** 通话结束回调(ended/missed)。 */
    private Runnable onCallEnd;

    public void setOnCallActive(java.util.function.IntConsumer cb) { this.onCallActive = cb; }
    public void setOnCallEnd(Runnable cb) { this.onCallEnd = cb; }

    private BroadcastReceiver numberReceiver;      // 只补号码
    private final Map<Integer, Object> callListeners = new HashMap<>(); // subId → 监听器

    /** 广播侧捕获的最近号码(及时间),供监听侧取用。 */
    private volatile String latestNumber = "";
    private volatile long latestNumberAt = 0;

    /** CALL_PLACE 时由 GatewayService 告知的去电号码(广播不一定带,兜底)。 */
    private volatile String pendingOutNumber = "";
    private volatile int pendingOutSlot = -1;
    private volatile long pendingOutAt = 0;

    /** 记录一笔待匹配的去电(响应 CALL_PLACE 时调用)。 */
    public void noteOutgoing(String number, int slot) {
        pendingOutNumber = number;
        pendingOutSlot = slot;
        pendingOutAt = System.currentTimeMillis();
        latestNumber = number;          // 监听侧 freshNumber 也能拿到
        latestNumberAt = pendingOutAt;
    }

    /** 每张卡的通话上下文(subId → ctx)。 */
    private static class CallCtx {
        String callId = "";
        String from = "";
        int slot = 0;
        boolean ringing = false;   // 响过铃
        boolean answered = false;  // 接通过(OFFHOOK 过)
        boolean outgoing = false;  // 去电(没响铃直接 OFFHOOK)
        boolean incomingPublished = false;
    }
    private final Map<Integer, CallCtx> calls = new HashMap<>();

    public CallMonitor(Context context, MqttClient mqtt, SimMonitor simMonitor) {
        this.context = context;
        this.mqtt = mqtt;
        this.simMonitor = simMonitor;
    }

    public void start() {
        // 1) 号码补充通道(受保护系统广播,只能系统发,RECEIVER_EXPORTED 无被伪造风险)
        numberReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                if (number != null && !number.isEmpty()) {
                    latestNumber = number;
                    latestNumberAt = System.currentTimeMillis();
                    // 若当前有未发布的 RINGING,补上号码立即发布
                    for (Map.Entry<Integer, CallCtx> e : calls.entrySet()) {
                        CallCtx ctx = e.getValue();
                        if (ctx.ringing && !ctx.incomingPublished) {
                            ctx.from = number;
                            ctx.incomingPublished = true;
                            publishIncoming(ctx);
                        }
                    }
                }
            }
        };
        ContextCompat.registerReceiver(context, numberReceiver,
                new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED);

        // 2) 每张卡的通话状态监听(状态机权威来源)
        armCallStateListeners();
        Log.i(TAG, "call monitor armed");
    }

    /** 按当前在用的订阅注册 per-sub 监听。 */
    private void armCallStateListeners() {
        List<Integer> subIds = simMonitor.activeSubIds();
        for (Integer subId : subIds) {
            if (callListeners.containsKey(subId)) continue;
            TelephonyManager tm = simMonitor.tmForSub(subId);
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    SubCallCallback cb = new SubCallCallback(subId);
                    tm.registerTelephonyCallback(context.getMainExecutor(), cb);
                    callListeners.put(subId, cb);
                } else {
                    SubCallListener pl = new SubCallListener(subId);
                    tm.listen(pl, PhoneStateListener.LISTEN_CALL_STATE);
                    callListeners.put(subId, pl);
                }
            } catch (Exception e) {
                Log.w(TAG, "register call listener sub=" + subId + " failed: " + e.getMessage());
            }
        }
    }

    public void stop() {
        main.removeCallbacksAndMessages(null);
        if (numberReceiver != null) {
            try { context.unregisterReceiver(numberReceiver); } catch (Exception ignored) {}
            numberReceiver = null;
        }
        for (Map.Entry<Integer, Object> e : callListeners.entrySet()) {
            try {
                TelephonyManager tm = simMonitor.tmForSub(e.getKey());
                if (e.getValue() instanceof TelephonyCallback) {
                    tm.unregisterTelephonyCallback((TelephonyCallback) e.getValue());
                } else {
                    tm.listen((PhoneStateListener) e.getValue(), PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception ignored) {}
        }
        callListeners.clear();
    }

    // ============ 状态机(per-sub 监听驱动) ============

    private void onCallStateChanged(int subId, int state) {
        int slot = simMonitor.slotForSubId(subId);
        if (slot < 0) slot = 0;
        CallCtx ctx = calls.get(subId);
        if (ctx == null) {
            ctx = new CallCtx();
            ctx.slot = slot;
            calls.put(subId, ctx);
        }

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                if (!ctx.ringing) {
                    ctx.ringing = true;
                    ctx.answered = false;
                    ctx.outgoing = false;
                    ctx.callId = "call-" + System.currentTimeMillis();
                    ctx.from = freshNumber();
                    if (!ctx.from.isEmpty()) {
                        ctx.incomingPublished = true;
                        publishIncoming(ctx);
                    } else {
                        // 部分 ROM 号码稍后才到,聚合等待
                        final CallCtx fCtx = ctx;
                        main.postDelayed(() -> {
                            if (fCtx.incomingPublished || !fCtx.ringing) return;
                            fCtx.from = freshNumber();
                            fCtx.incomingPublished = true;
                            publishIncoming(fCtx);
                        }, 600);
                    }
                }
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (ctx.ringing) {
                    ctx.answered = true;
                    if (ctx.from.isEmpty()) ctx.from = freshNumber();
                    publishState(ctx, "active", "in");
                    notifyActive(slot);
                } else if (!ctx.answered) {
                    // 没响铃直接摘机 = 去电(本机拨号或 CALL_PLACE)
                    ctx.answered = true;
                    ctx.outgoing = true;
                    ctx.from = freshNumber();
                    if (ctx.from.isEmpty()) maybePendingOut(ctx, slot);
                    if (ctx.callId.isEmpty()) ctx.callId = "call-" + System.currentTimeMillis();
                    publishState(ctx, "active", "out");
                    notifyActive(slot);
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                if (ctx.ringing || ctx.answered) {
                    // 极短响铃就挂断:先补发 CALL_INCOMING,再发 missed,客户端才能关联
                    if (ctx.ringing && !ctx.incomingPublished) {
                        ctx.from = freshNumber();
                        ctx.incomingPublished = true;
                        publishIncoming(ctx);
                    }
                    if (ctx.from.isEmpty()) maybePendingOut(ctx, slot);
                    String endState = (ctx.ringing && !ctx.answered) ? "missed" : "ended";
                    publishState(ctx, endState, ctx.outgoing ? "out" : "in");
                    if (onCallEnd != null) {
                        try { onCallEnd.run(); } catch (Exception e) { Log.w(TAG, "onCallEnd cb: " + e.getMessage()); }
                    }
                }
                calls.remove(subId); // 一通电话结束,清上下文
                break;

            default:
                break;
        }
    }

    private void notifyActive(int slot) {
        if (onCallActive != null) {
            try { onCallActive.accept(slot); } catch (Exception e) { Log.w(TAG, "onCallActive cb: " + e.getMessage()); }
        }
    }

    private String freshNumber() {
        long now = System.currentTimeMillis();
        return (now - latestNumberAt <= NUMBER_FRESH_MS) ? latestNumber : "";
    }

    /** 兜底:去电号码从 CALL_PLACE 记录里取(卡槽需匹配,10s 内有效)。 */
    private void maybePendingOut(CallCtx ctx, int slot) {
        if (System.currentTimeMillis() - pendingOutAt <= 10_000 && pendingOutSlot == slot) {
            ctx.from = pendingOutNumber;
        }
    }

    // ============ 发布 ============

    private void publishIncoming(CallCtx ctx) {
        Log.i(TAG, "CALL_INCOMING slot=" + ctx.slot + " from=" + ctx.from);
        try {
            JSONObject data = new JSONObject();
            data.put("id", ctx.callId);
            data.put("slot", ctx.slot);
            data.put("from", ctx.from);
            data.put("ts", System.currentTimeMillis());
            mqtt.publishEvent("event/call/incoming", Protocol.envelope(Protocol.T_CALL_INCOMING, data));
        } catch (JSONException e) {
            Log.w(TAG, "build CALL_INCOMING failed: " + e.getMessage());
        }
        publishState(ctx, "ringing", "in");
    }

    /** dir 为附加字段(协议前向兼容):in=来电 out=去电。 */
    private void publishState(CallCtx ctx, String state, String dir) {
        Log.i(TAG, "CALL_STATE slot=" + ctx.slot + " state=" + state + " dir=" + dir + " from=" + ctx.from);
        try {
            JSONObject data = new JSONObject();
            data.put("ref", ctx.callId);
            data.put("state", state);   // ringing | active | ended | missed
            data.put("slot", ctx.slot);
            data.put("from", ctx.from);
            data.put("dir", dir);
            Protocol.Message m = Protocol.envelope(Protocol.T_CALL_STATE, ctx.callId, data);
            mqtt.publishEvent("event/call/state", m);
        } catch (JSONException e) {
            Log.w(TAG, "build CALL_STATE failed: " + e.getMessage());
        }
    }

    // ============ 监听器实现 ============

    /** API 31+ per-sub 通话状态(只报本卡)。 */
    private class SubCallCallback extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        private final int subId;
        SubCallCallback(int subId) { this.subId = subId; }
        @Override public void onCallStateChanged(int state) {
            CallMonitor.this.onCallStateChanged(subId, state);
        }
    }

    /** API <31 per-sub 通话状态(只报本卡)。 */
    private class SubCallListener extends PhoneStateListener {
        private final int subId;
        SubCallListener(int subId) { this.subId = subId; }
        @Override public void onCallStateChanged(int state, String phoneNumber) {
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                latestNumber = phoneNumber;
                latestNumberAt = System.currentTimeMillis();
            }
            CallMonitor.this.onCallStateChanged(subId, state);
        }
    }
}
