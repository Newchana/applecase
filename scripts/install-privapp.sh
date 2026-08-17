#!/usr/bin/env bash
# 把当前编译的 APK 以 priv-app 形式挂到 Q968(Magisk systemless),授予语音桥所需的
# signature|privileged 权限(CAPTURE_AUDIO_OUTPUT / MODIFY_PHONE_STATE)。
#
# 用法: ./scripts/install-privapp.sh [apk路径]     # 默认取 app/build/outputs/apk/debug/app-debug.apk
# 流程: push 模块文件 → 卸载 data 版应用 → 重启(必须,priv-app 在开机时解析授权)
#
# 注意:此后每次改代码,重新跑本脚本(会重启设备)。普通功能开发不必特权,音频联调才需要。

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK="${1:-$PROJECT_DIR/android/app/build/outputs/apk/debug/app-debug.apk}"
MOD="/data/adb/modules/applecase_privapp"
PKG="com.applecase.gateway"
ADB="${ADB:-$(command -v adb)}"
[ -n "$ADB" ] || { echo "ERROR: adb 未找到"; exit 1; }
export MSYS_NO_PATHCONV=1
command -v cygpath >/dev/null 2>&1 && APK_W=$(cygpath -m "$APK") || APK_W="$APK"

[ -f "$APK" ] || { echo "ERROR: APK 不存在: $APK(先编译)"; exit 1; }

echo "==== [1/5] 检查 root ===="
"$ADB" shell "su -c id" | grep -q "uid=0" || { echo "ERROR: 无 su 权限"; exit 1; }

echo "==== [2/5] 写入模块结构 ===="
"$ADB" shell "su -c 'mkdir -p $MOD/system/priv-app/AppleCaseGateway $MOD/system/etc/permissions'"
"$ADB" push "$APK_W" /data/local/tmp/AppleCaseGateway.apk >/dev/null
P_XML="$SCRIPT_DIR/magisk-privapp/privapp-permissions-applecase.xml"
P_PROP="$SCRIPT_DIR/magisk-privapp/module.prop"
command -v cygpath >/dev/null 2>&1 && { P_XML=$(cygpath -m "$P_XML"); P_PROP=$(cygpath -m "$P_PROP"); }
"$ADB" push "$P_XML" "$P_PROP" /data/local/tmp/ >/dev/null
"$ADB" shell "su -c 'cp /data/local/tmp/AppleCaseGateway.apk $MOD/system/priv-app/AppleCaseGateway/AppleCaseGateway.apk && chmod 644 $MOD/system/priv-app/AppleCaseGateway/AppleCaseGateway.apk && cp /data/local/tmp/privapp-permissions-applecase.xml $MOD/system/etc/permissions/ && chmod 644 $MOD/system/etc/permissions/privapp-permissions-applecase.xml && cp /data/local/tmp/module.prop $MOD/ && rm /data/local/tmp/AppleCaseGateway.apk /data/local/tmp/privapp-permissions-applecase.xml /data/local/tmp/module.prop'"

echo "==== [3/5] 卸载 data 版应用(避免与 priv-app 双装;注意会清配置) ===="
"$ADB" uninstall $PKG >/dev/null 2>&1 || true

echo "==== [4/5] 重启设备(priv-app 开机解析授权) ===="
"$ADB" reboot
echo "等待设备回来..."
until "$ADB" wait-for-device shell "getprop sys.boot_completed" 2>/dev/null | grep -q "^1$"; do sleep 3; done
sleep 10

echo "==== [5/5] 验证 ===="
"$ADB" shell "dumpsys package $PKG" | grep -E "CAPTURE_AUDIO_OUTPUT|MODIFY_PHONE_STATE" || echo "(未见权限,检查开机日志)"
echo ""
echo "完成。重写配置(如 broker 地址)后重启服务即可。"
