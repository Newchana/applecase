#!/usr/bin/env bash
# 苹果皮网关 —— 编译并部署到 Q968
# 用法: ./scripts/deploy-android.sh
# 依赖: adb(在 PATH 中), JDK 17

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../android" && pwd)"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.applecase.gateway"

# 找 adb
ADB=""
for candidate in \
    "$(command -v adb 2>/dev/null)" \
    "/c/Users/j1352/AppData/Local/Microsoft/WinGet/Packages/Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe/scrcpy-win64-v4.0/adb.exe" \
    "/c/Users/j1352/AppData/Local/Android/Sdk/platform-tools/adb.exe"; do
    if [ -n "$candidate" ] && [ -x "$candidate" -o -f "$candidate" ]; then ADB="$candidate"; break; fi
done
if [ -z "$ADB" ]; then echo "ERROR: adb 未找到"; exit 1; fi
export MSYS_NO_PATHCONV=1

echo "==== [1/4] 检查设备 ===="
"$ADB" devices -l | grep -q "device product" || { echo "ERROR: 无设备连接"; exit 1; }

echo "==== [2/4] 编译 debug APK ===="
cd "$PROJECT_DIR"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-17}"
./gradlew.bat assembleDebug --no-daemon -q
[ -f "$APK" ] || { echo "ERROR: APK 编译失败"; exit 1; }
echo "    APK: $(du -h "$APK" | cut -f1)"

echo "==== [3/4] 安装到设备 ===="
"$ADB" install -r "$APK" 2>&1 | tail -2

echo "==== [4/4] 授权 + 启动 ===="
# 权限(老人机不好操作授权弹窗,用 pm grant 直接授予)
"$ADB" shell "pm grant $PKG android.permission.RECEIVE_SMS" 2>/dev/null || true
"$ADB" shell "pm grant $PKG android.permission.READ_SMS" 2>/dev/null || true
"$ADB" shell "pm grant $PKG android.permission.SEND_SMS" 2>/dev/null || true
"$ADB" shell "pm grant $PKG android.permission.READ_PHONE_STATE" 2>/dev/null || true
"$ADB" shell "pm grant $PKG android.permission.CALL_PHONE" 2>/dev/null || true
"$ADB" shell "pm grant $PKG android.permission.ANSWER_PHONE_CALLS" 2>/dev/null || true
"$ADB" shell "pm grant $PKG android.permission.READ_CALL_LOG" 2>/dev/null || true
"$ADB" shell "pm grant $PKG android.permission.POST_NOTIFICATIONS" 2>/dev/null || true
# 电池优化白名单(防止 Doze 杀后台)
"$ADB" shell "dumpsys deviceidle whitelist +$PKG" 2>/dev/null || true

# 启动 MainActivity(它会触发前台服务)
"$ADB" shell "am start -n $PKG/.MainActivity"
echo ""
echo "完成。看 logcat 验证: $ADB logcat -s AC.Gateway AC.Mqtt AC.SmsRx AC.SmsTx AC.Sim AC.CallRx AC.CallTx"

echo "提示:装保活模块(开机自启+看门狗)运行 ./scripts/install-keepalive.sh"
