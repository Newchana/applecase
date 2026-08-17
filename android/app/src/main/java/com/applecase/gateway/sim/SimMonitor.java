package com.applecase.gateway.sim;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.applecase.gateway.net.MqttClient;
import com.applecase.gateway.net.Protocol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 双卡状态监控。
 *
 * 关键点(调研结论):
 * - 双卡按 subscriptionId 区分:SubscriptionManager.getActiveSubscriptionInfoList() 给出
 *   每张卡的 slotIndex/subscriptionId/carrierName。
 * - 每张卡各自的信号/服务状态:TelephonyManager.createForSubscriptionId(subId) 后在其上
 *   注册监听(API 31+ 用 TelephonyCallback,以下用 PhoneStateListener)。
 * - 信号事件按"信号档位变化"上报并节流,避免每几秒刷屏 MQTT。
 *
 * 对外:
 * - getSimsJson():HELLO 用的 sims 数组(真实读 TelephonyManager)。
 * - slotForSubId():给 CallMonitor(来电是哪张卡)/ CallController(拨号选卡)用。
 * - SIM 状态变化 → MQTT SIM_STATE 事件。
 */
public class SimMonitor {

    private static final String TAG = "AC.Sim";
    /** 同一张卡两次信号事件的最小间隔(毫秒),档位变化才发,且不高于此频率。 */
    private static final long SIGNAL_EVENT_MIN_INTERVAL_MS = 30_000;

    private final Context context;
    private final MqttClient mqtt;
    private final TelephonyManager defaultTm;
    private final SubscriptionManager subManager;

    /** 订阅信息缓存:subId → SubscriptionInfo(插拔卡/开关数据时刷新)。 */
    private final Map<Integer, SubscriptionInfo> subs = new HashMap<>();
    /** 正在监听的 per-sub 监听器,注销用。 */
    private final List<Object> registeredListeners = new ArrayList<>();
    private SubscriptionManager.OnSubscriptionsChangedListener subChangedListener;

    /** 每张卡的当前状态缓存(subId → 状态),用于变化检测与节流。 */
    private static class SlotState {
        String state = "";      // READY / ABSENT / ...
        String carrier = "";
        int level = -1;         // 信号档位 0..4
        boolean inService = false;
        long lastSignalEventAt = 0;
    }
    private final Map<Integer, SlotState> states = new HashMap<>();

    public SimMonitor(Context context, MqttClient mqtt) {
        this.context = context;
        this.mqtt = mqtt;
        this.defaultTm = context.getSystemService(TelephonyManager.class);
        this.subManager = context.getSystemService(SubscriptionManager.class);
    }

    public void start() {
        if (!hasPhoneStatePermission()) {
            Log.w(TAG, "no READ_PHONE_STATE, SIM monitor disabled");
            return;
        }
        refreshSubscriptions();
        listenSignalForAllSubs();
        // 插拔卡/双卡切换时重刷订阅列表并重建监听
        try {
            subChangedListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override public void onSubscriptionsChanged() {
                    Log.i(TAG, "subscriptions changed, re-arming");
                    stopSignalListeners();
                    refreshSubscriptions();
                    listenSignalForAllSubs();
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                subManager.addOnSubscriptionsChangedListener(context.getMainExecutor(), subChangedListener);
            } else {
                subManager.addOnSubscriptionsChangedListener(subChangedListener);
            }
        } catch (Exception e) {
            // 部分 ROM 该回调受限,拿不到也只是少了插拔热更新,不影响基础功能
            Log.w(TAG, "addOnSubscriptionsChangedListener failed: " + e.getMessage());
        }
    }

    public void stop() {
        stopSignalListeners();
        if (subChangedListener != null) {
            try { subManager.removeOnSubscriptionsChangedListener(subChangedListener); } catch (Exception ignored) {}
            subChangedListener = null;
        }
    }

    // ============ 订阅枚举与映射 ============

    private void refreshSubscriptions() {
        subs.clear();
        try {
            List<SubscriptionInfo> list = subManager.getActiveSubscriptionInfoList();
            if (list != null) {
                for (SubscriptionInfo si : list) {
                    subs.put(si.getSubscriptionId(), si);
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "no permission to list subscriptions: " + e.getMessage());
        }
        Log.i(TAG, "active subs: " + describeSubs());
    }

    /** slot(0/1) → subscriptionId;查不到返回 -1。 */
    public int subIdForSlot(int slot) {
        for (SubscriptionInfo si : subs.values()) {
            if (si.getSimSlotIndex() == slot) return si.getSubscriptionId();
        }
        return -1;
    }

    /** subscriptionId → slot(来电广播里带的是 subscription)。查不到返回 -1。 */
    public int slotForSubId(int subId) {
        SubscriptionInfo si = subs.get(subId);
        return si != null ? si.getSimSlotIndex() : -1;
    }

    /** 拿某张卡的 per-sub TelephonyManager。 */
    public TelephonyManager tmForSub(int subId) {
        try {
            if (subId >= 0) return defaultTm.createForSubscriptionId(subId);
        } catch (Exception ignored) {}
        return defaultTm;
    }

    /** 全部在用 subscriptionId 列表(按 slot 排序)。 */
    public List<Integer> activeSubIds() {
        List<Integer> ids = new ArrayList<>(subs.keySet());
        ids.sort((a, b) -> Integer.compare(
                subs.get(a).getSimSlotIndex(), subs.get(b).getSimSlotIndex()));
        return ids;
    }

    // ============ HELLO 数据 ============

    /** 卡槽 → 本机号码(手动配置来源,国内 SIM 多不写 MSISDN)。GatewayService 注入。 */
    private java.util.function.IntFunction<String> numberResolver = slot -> "";

    public void setNumberResolver(java.util.function.IntFunction<String> r) {
        this.numberResolver = r;
    }

    /** 组 HELLO 用的 sims 数组:[{slot,id,carrier,state,signal,number}]。number 为手动配置的本机号(卡上通常没存)。 */
    public JSONArray getSimsJson() {
        JSONArray arr = new JSONArray();
        if (!hasPhoneStatePermission()) return arr;
        for (Integer subId : activeSubIds()) {
            SubscriptionInfo si = subs.get(subId);
            SlotState st = stateOf(subId);
            try {
                JSONObject o = new JSONObject();
                o.put("slot", si.getSimSlotIndex());
                o.put("id", subId);
                o.put("carrier", si.getCarrierName() != null ? si.getCarrierName().toString() : "");
                o.put("state", st.state);
                o.put("signal", st.level);       // 0..4 档位
                o.put("number", numberResolver.apply(si.getSimSlotIndex()));
                arr.put(o);
            } catch (JSONException ignored) {}
        }
        return arr;
    }

    // ============ 信号监听 ============

    private void listenSignalForAllSubs() {
        for (Integer subId : activeSubIds()) {
            TelephonyManager tm = tmForSub(subId);
            final int fSubId = subId;
            if (Build.VERSION.SDK_INT >= 31) {
                // 注意:TelephonyCallback 必须显式 implements 对应 *Listener 接口,
                // 只覆写方法不会被回调(与 PhoneStateListener 不同)。
                SubTelephonyCallback cb = new SubTelephonyCallback(subId);
                try {
                    tm.registerTelephonyCallback(context.getMainExecutor(), cb);
                    registeredListeners.add(new Object[]{tm, cb});
                } catch (Exception e) {
                    Log.w(TAG, "registerTelephonyCallback sub=" + fSubId + " failed: " + e.getMessage());
                }
            } else {
                PhoneStateListener pl = new PhoneStateListener() {
                    @Override public void onSignalStrengthsChanged(SignalStrength s) {
                        onSignal(fSubId, s);
                    }
                    @Override public void onServiceStateChanged(ServiceState s) {
                        onServiceState(fSubId, s);
                    }
                };
                try {
                    tm.listen(pl, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS | PhoneStateListener.LISTEN_SERVICE_STATE);
                    registeredListeners.add(new Object[]{tm, pl});
                } catch (Exception e) {
                    Log.w(TAG, "listen sub=" + fSubId + " failed: " + e.getMessage());
                }
            }
            // 初始化该卡当前状态(getSimsJson 立即能读到,不等第一次回调)
            SlotState st = stateOf(subId);
            st.state = simStateName(tmForSub(subId).getSimState());
            SubscriptionInfo si = subs.get(subId);
            if (si != null && si.getCarrierName() != null) st.carrier = si.getCarrierName().toString();
            SignalStrength ss = tmForSub(subId).getSignalStrength();
            if (ss != null) {
                st.level = ss.getLevel();
            }
        }
    }

    private void stopSignalListeners() {
        for (Object o : registeredListeners) {
            try {
                Object[] pair = (Object[]) o;
                TelephonyManager tm = (TelephonyManager) pair[0];
                if (pair[1] instanceof TelephonyCallback) {
                    tm.unregisterTelephonyCallback((TelephonyCallback) pair[1]);
                } else {
                    tm.listen((PhoneStateListener) pair[1], PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception ignored) {}
        }
        registeredListeners.clear();
    }

    /** API 31+ 的 per-sub 信号/服务状态回调。 */
    private class SubTelephonyCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener {

        private final int subId;

        SubTelephonyCallback(int subId) { this.subId = subId; }

        @Override public void onSignalStrengthsChanged(SignalStrength s) {
            SimMonitor.this.onSignal(subId, s);
        }

        @Override public void onServiceStateChanged(ServiceState s) {
            SimMonitor.this.onServiceState(subId, s);
        }
    }

    private void onSignal(int subId, SignalStrength s) {
        SlotState st = stateOf(subId);
        int newLevel = s != null ? s.getLevel() : -1;
        boolean levelChanged = newLevel != st.level;
        st.level = newLevel;
        if (levelChanged) maybePublishSimState(subId, st, false);
    }

    private void onServiceState(int subId, ServiceState s) {
        SlotState st = stateOf(subId);
        st.inService = s != null && s.getState() == ServiceState.STATE_IN_SERVICE;
        // 服务状态变化(如脱网/漫游)立即上报,不节流
        maybePublishSimState(subId, st, true);
    }

    private void maybePublishSimState(int subId, SlotState st, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - st.lastSignalEventAt < SIGNAL_EVENT_MIN_INTERVAL_MS) return;
        st.lastSignalEventAt = now;
        publishSimState(subId, st);
    }

    /** 发布一条 SIM_STATE 事件到 applecase/{deviceId}/event/sim。 */
    private void publishSimState(int subId, SlotState st) {
        int slot = slotForSubId(subId);
        if (slot < 0) slot = 0;
        try {
            JSONObject data = new JSONObject();
            data.put("slot", slot);
            data.put("state", st.state);
            data.put("signal", st.level);
            data.put("carrier", st.carrier);
            Protocol.Message m = Protocol.envelope(Protocol.T_SIM_STATE, data);
            mqtt.publishEvent("event/sim", m);
            Log.i(TAG, "SIM_STATE slot=" + slot + " state=" + st.state + " level=" + st.level);
        } catch (JSONException e) {
            Log.w(TAG, "build SIM_STATE failed: " + e.getMessage());
        }
    }

    // ============ 工具 ============

    private SlotState stateOf(int subId) {
        SlotState st = states.get(subId);
        if (st == null) {
            st = new SlotState();
            states.put(subId, st);
        }
        return st;
    }

    private static String simStateName(int simState) {
        switch (simState) {
            case TelephonyManager.SIM_STATE_READY: return "READY";
            case TelephonyManager.SIM_STATE_ABSENT: return "ABSENT";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "PIN_REQUIRED";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "PUK_REQUIRED";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "NETWORK_LOCKED";
            // 以下为隐藏常量,用数值字面量(见 TelephonyManager 源码)
            case 6: return "NOT_READY";           // SIM_STATE_NOT_READY
            case 7: return "PERM_DISABLED";       // SIM_STATE_ABSENT_ON_PERM
            case 8: return "CARD_IO_ERROR";       // SIM_STATE_CARD_IO_ERROR
            case 9: return "CARD_RESTRICTED";     // SIM_STATE_CARD_RESTRICTED
            case 10: return "LOADED";             // SIM_STATE_LOADED
            default: return "UNKNOWN(" + simState + ")";
        }
    }

    private boolean hasPhoneStatePermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String describeSubs() {
        StringBuilder sb = new StringBuilder();
        for (Integer subId : activeSubIds()) {
            SubscriptionInfo si = subs.get(subId);
            if (sb.length() > 0) sb.append(", ");
            sb.append("{slot=").append(si.getSimSlotIndex())
              .append(" subId=").append(subId)
              .append(" carrier=").append(si.getCarrierName())
              .append("}");
        }
        return sb.length() > 0 ? sb.toString() : "(none)";
    }
}
