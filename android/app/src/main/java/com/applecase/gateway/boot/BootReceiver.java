package com.applecase.gateway.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.applecase.gateway.GatewayService;

/**
 * 开机自启接收器(阶段二启用)。
 *
 * 注意:阶段一优先用 Magisk service.sh 做开机自启(更可靠)。
 * 这个 receiver 作为兜底——但 Android 10+ 后台启动限制下,从 BOOT_COMPLETED 直接
 * startForegroundService 可能被系统拒绝。生产环境以 Magisk 模块的 post-fs-data.sh 为准。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Intent svc = new Intent(context, GatewayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
