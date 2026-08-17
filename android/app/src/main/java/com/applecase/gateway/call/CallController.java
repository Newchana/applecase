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

    /** 接听响铃中的来电:先走标准 API,不行用 root 模拟耳机键兜底。 */
    public String answer() {
        try {
            telecom.acceptRingingCall();
            Log.i(TAG, "acceptRingingCall");
            return OK;
        } catch (Exception e1) {
            // 部分 ROM 对非默认拨号应用静默失败/抛错 → root 发 HEADSETHOOK 接听
            Log.w(TAG, "acceptRingingCall failed(" + e1.getMessage() + "), fallback keyevent");
            String out = execRoot("input keyevent 79");   // KEYCODE_HEADSEHOOK
            if (out != null) {
                Log.i(TAG, "answer via keyevent 79");
                return OK;
            }
            return ERR_NO_PERMISSION;
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
    private final android.os.Handler keeper = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long KEEPER_INTERVAL_MS = 2000;
    /** 通话期间定时重申静音(HAL 可能在路由变化时重配 mixer)。 */
    private final Runnable keeperTask = new Runnable() {
        @Override public void run() {
            if (!silenced) return;
            applyMixerMute();
            keeper.postDelayed(this, KEEPER_INTERVAL_MS);
        }
    };

    /**
     * 通话中把 Q968 本机"哑巴化"(苹果皮形态):
     * - 听筒/扬声器:切断 VBC→codec 输出桥(AO Mixer AOL/AOR Switch=Off,root tinymix)。
     *   走 mixer 是因为 STREAM_VOICE_CALL 音量最小档是 1(非 0),AudioFlinger 侧压不到无声。
     *   ⚠️ 实测红线(分阶段验证,别再踩):
     *     · 'Earpiece Mute'/'VBC_DL_MUTE'/'EAR Gain'/'AO Gain' 均无效或误杀 —— Earpiece Mute 与
     *       DL_MUTE 是路由/DSP 级开关,会把 VBC 的 VOICE_CALL 数字抽头一起关掉(下行捕获断流);
     *       EAR/AO Gain 不在当前通话输出链上(置 0 听筒照响)。
     *     · ✅ AO Mixer 是 VBC 到 codec 的输出桥,关掉后听筒/喇叭全哑,数字抽头(位于其上游)不受影响
     *       (实测:277 帧满速,峰值 927,内容存活)。
     * - 麦克风:setMicrophoneMute(作用于 ADC 路径,不影响 INCALL_MUSIC 的 PCM 混入上行)。
     * 下行(对方声音)由桥送 iPhone 播;上行由 iPhone 注入。
     * 幂等:重复调用安全。
     */
    public void silenceLocal() {
        if (silenced) return;
        android.media.AudioManager am = context.getSystemService(android.media.AudioManager.class);
        try { am.setMicrophoneMute(true); } catch (Exception e) {
            Log.w(TAG, "setMicrophoneMute failed: " + e.getMessage());
        }
        applyMixerMute();
        silenced = true;
        keeper.removeCallbacks(keeperTask);
        keeper.postDelayed(keeperTask, KEEPER_INTERVAL_MS);
        Log.i(TAG, "local silenced (earpiece/speaker mixer mute + mic mute)");
    }

    private void applyMixerMute() {
        // 切断 VBC→codec 输出桥(听筒+喇叭全哑);不碰 Earpiece Mute/DL_MUTE(红线见上)
        execRoot("/system/bin/tinymix \"AO Mixer AOL Switch\" 0"
                + ";/system/bin/tinymix \"AO Mixer AOR Switch\" 0");
    }

    private void applyMixerRestore() {
        execRoot("/system/bin/tinymix \"AO Mixer AOL Switch\" 1"
                + ";/system/bin/tinymix \"AO Mixer AOR Switch\" 1");
    }

    /** 通话结束恢复本机声音。幂等。 */
    public void restoreLocal() {
        if (!silenced) return;
        silenced = false;
        keeper.removeCallbacks(keeperTask);
        android.media.AudioManager am = context.getSystemService(android.media.AudioManager.class);
        try { am.setMicrophoneMute(false); } catch (Exception ignored) {}
        // tinymix 查询输出形如 "233\tINT\t1\tEAR Gain EAR Playback Volume\t15",取末尾数字
        applyMixerRestore();
        Log.i(TAG, "local restored (AO Mixer back on)");
    }

    public boolean isSilenced() { return silenced; }

    /** su 执行 shell 命令,返回 stdout(root mixer 操作)。失败返回 null。 */
    private String execRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) out.write(buf, 0, n);
            p.waitFor();
            return out.toString().trim();
        } catch (Exception e) {
            Log.w(TAG, "execRoot failed: " + e.getMessage());
            return null;
        }
    }

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
