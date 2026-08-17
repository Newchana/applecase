package com.applecase.gateway.call;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.applecase.gateway.sim.SimMonitor;

import java.util.List;

/**
 * 拨号与通话控制(双卡可选卡)。
 *
 * 关键点(调研结论):
 * - 双卡选卡拨号:TelecomManager.callCapablePhoneAccounts() 每张卡一个 PhoneAccountHandle,
 *   placeCall 时放进 EXTRA_PHONE_ACCOUNT_HANDLE 即用指定卡拨出。
 *   handle → 卡槽 的映射:createForPhoneAccountHandle(handle).getSubscriptionId() 得到 subId,
 *   再对 SubscriptionManager 的 slotIndex。映射失败则按列表顺序兜底。
 * - 用 TelecomManager.placeCall() 而非 startActivity(ACTION_CALL):后者在 Android 10+ 的
 *   后台服务里受"后台启动 Activity"限制,placeCall 走 telecom 通道不受限。
 * - 接听 acceptRingingCall / 挂断 endCall 均需 ANSWER_PHONE_CALLS 运行时权限;
 *   静音 setMicrophoneMute 需 MODIFY_AUDIO_SETTINGS(普通权限,manifest 声明即可)。
 */
public class CallController {

    private static final String TAG = "AC.CallTx";

    private final Context context;
    private final SimMonitor simMonitor;
    private final TelecomManager telecom;

    public CallController(Context context, SimMonitor simMonitor) {
        this.context = context;
        this.simMonitor = simMonitor;
        this.telecom = context.getSystemService(TelecomManager.class);
    }

    /** 结果码:成功或错误名(对应协议错误码)。 */
    public static final String OK = null;
    public static final String ERR_NO_PHONE_ACCOUNT = "NO_SIM";
    public static final String ERR_CALL_FAILED = "SEND_FAILED";
    public static final String ERR_NO_PERMISSION = "AUTH_FAIL";

    /**
     * 用指定卡拨号(响应 CALL_PLACE)。
     * @param to 号码
     * @param slot 卡槽 0/1;传 -1 或无效值用系统默认卡
     * @return null=已受理;否则为错误码
     */
    public String place(String to, int slot) {
        try {
            Uri uri = Uri.fromParts("tel", to, null);
            Bundle extras = new Bundle();
            PhoneAccountHandle handle = resolvePhoneAccount(slot);
            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);
            } else if (slot >= 0) {
                // 明确要求选卡但找不到对应账号
                return ERR_NO_PHONE_ACCOUNT;
            }
            telecom.placeCall(uri, extras);
            Log.i(TAG, "placeCall to=" + to + " slot=" + slot + " handle=" + handle);
            return OK;
        } catch (SecurityException e) {
            Log.e(TAG, "no CALL_PHONE permission: " + e.getMessage());
            return ERR_NO_PERMISSION;
        } catch (Exception e) {
            Log.e(TAG, "placeCall failed: " + e.getMessage());
            return ERR_CALL_FAILED;
        }
    }

    /** 接听响铃中的来电。 */
    public String answer() {
        try {
            telecom.acceptRingingCall();
            Log.i(TAG, "acceptRingingCall");
            return OK;
        } catch (SecurityException e) {
            Log.e(TAG, "no ANSWER_PHONE_CALLS: " + e.getMessage());
            return ERR_NO_PERMISSION;
        } catch (Exception e) {
            Log.e(TAG, "acceptRingingCall failed: " + e.getMessage());
            return ERR_CALL_FAILED;
        }
    }

    /** 挂断当前通话。 */
    public String hangup() {
        try {
            boolean ended = telecom.endCall();
            Log.i(TAG, "endCall ok=" + ended);
            return ended ? OK : ERR_CALL_FAILED;
        } catch (SecurityException e) {
            Log.e(TAG, "no ANSWER_PHONE_CALLS: " + e.getMessage());
            return ERR_NO_PERMISSION;
        } catch (Exception e) {
            Log.e(TAG, "endCall failed: " + e.getMessage());
            return ERR_CALL_FAILED;
        }
    }

    /** 麦克风静音开关(本端静音,不是保持通话)。 */
    public String mute(boolean on) {
        try {
            android.media.AudioManager am = context.getSystemService(android.media.AudioManager.class);
            am.setMicrophoneMute(on);
            Log.i(TAG, "setMicrophoneMute=" + on);
            return OK;
        } catch (Exception e) {
            Log.e(TAG, "setMicrophoneMute failed: " + e.getMessage());
            return ERR_CALL_FAILED;
        }
    }

    // ============ 本机哑巴化(苹果皮形态:声音全走 iPhone 桥) ============

    private boolean silenced = false;
    private boolean armSilence = false;   // App 拨号/接听时置位:这通电话要把本机哑巴化
    private int savedVoiceCallVolume = -1;

    /** 标记下一通(正在建立的)通话做本机静音 —— 仅 App 发起的操作会调用。 */
    public void armSilenceNextCall() {
        armSilence = true;
        Log.i(TAG, "arm silence for next call");
    }

    /** 接通时消费标志;返回是否应静音。 */
    public boolean consumeArmSilence() {
        boolean v = armSilence;
        armSilence = false;
        return v;
    }

    /**
     * 通话中把 Q968 本机"哑巴化":通话扬声器音量归零 + 麦克风静音。
     * 下行(对方声音)由 VOICE_CALL 捕获送 iPhone 播;上行由 iPhone 经 INCALL_MUSIC 注入,
     * 麦克风静音作用于 ADC 路径,不影响 PCM 混入通路(实测验证)。
     * 幂等:重复调用安全。
     */
    public void silenceLocal() {
        if (silenced) return;
        try {
            android.media.AudioManager am = context.getSystemService(android.media.AudioManager.class);
            savedVoiceCallVolume = am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL);
            am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, 0, 0);
            am.setMicrophoneMute(true);
            silenced = true;
            Log.i(TAG, "local silenced (voice-call volume " + savedVoiceCallVolume + " -> 0, mic muted)");
        } catch (Exception e) {
            Log.w(TAG, "silenceLocal failed: " + e.getMessage());
        }
    }

    /** 通话结束恢复本机声音。幂等。 */
    public void restoreLocal() {
        if (!silenced) return;
        try {
            android.media.AudioManager am = context.getSystemService(android.media.AudioManager.class);
            am.setMicrophoneMute(false);
            if (savedVoiceCallVolume >= 0) {
                am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL,
                        savedVoiceCallVolume, 0);
            }
            Log.i(TAG, "local restored (volume=" + savedVoiceCallVolume + ")");
        } catch (Exception e) {
            Log.w(TAG, "restoreLocal failed: " + e.getMessage());
        }
        silenced = false;
        savedVoiceCallVolume = -1;
    }

    public boolean isSilenced() { return silenced; }

    // ============ slot → PhoneAccountHandle 映射 ============

    /**
     * 把卡槽号解析成对应的 PhoneAccountHandle。
     * 1) 遍历 callCapablePhoneAccounts,用 createForPhoneAccountHandle(handle).getSubscriptionId()
     *    拿 subId,与 SubscriptionManager 的 slotIndex 匹配(可靠路径);
     * 2) 匹配不到按列表顺序兜底(slot 0 → 第一个,slot 1 → 第二个);
     * 3) slot<0 返回 null(系统默认卡)。
     */
    private PhoneAccountHandle resolvePhoneAccount(int slot) {
        if (slot < 0) return null;
        List<PhoneAccountHandle> accounts = telecom.getCallCapablePhoneAccounts();
        if (accounts == null || accounts.isEmpty()) return null;

        TelephonyManager defaultTm = context.getSystemService(TelephonyManager.class);
        SubscriptionManager subManager = context.getSystemService(SubscriptionManager.class);
        try {
            List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
            if (subs != null) {
                for (PhoneAccountHandle h : accounts) {
                    TelephonyManager t = defaultTm.createForPhoneAccountHandle(h);
                    if (t == null) continue;
                    int subId = t.getSubscriptionId();
                    for (SubscriptionInfo si : subs) {
                        if (si.getSubscriptionId() == subId && si.getSimSlotIndex() == slot) {
                            return h;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "slot→handle precise mapping failed, fallback to order: " + e.getMessage());
        }

        // 兜底:按顺序
        return slot < accounts.size() ? accounts.get(slot) : null;
    }
}
