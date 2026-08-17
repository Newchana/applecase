#!/usr/bin/env bash
# 安装苹果皮保活 Magisk 模块到 Q968(免打包 zip,直接落盘模块目录)
# 前提:设备已 root(Magisk),adb 已连接,shell 有 su 权限
# 用法: ./scripts/install-keepalive.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/magisk-applecase"
DST="/data/adb/modules/applecase_gateway"
ADB="${ADB:-$(command -v adb)}"
[ -n "$ADB" ] || { echo "ERROR: adb 未找到"; exit 1; }
export MSYS_NO_PATHCONV=1   # Git Bash 下防止 /data/... 被转成 Windows 路径

echo "==== [1/3] 检查 root ===="
"$ADB" shell "su -c id" | grep -q "uid=0" || { echo "ERROR: adb shell 无 su 权限(请在 Magisk 授权)"; exit 1; }

echo "==== [2/3] 推送模块文件 ===="
"$ADB" shell "su -c mkdir -p $DST"
# 源路径转成 Windows 形式(MSYS_NO_PATHCONV 下 adb 不认 /d/... ;目标路径保持设备形式)
P1="$SRC/module.prop"; P2="$SRC/service.sh"
command -v cygpath >/dev/null 2>&1 && { P1=$(cygpath -m "$P1"); P2=$(cygpath -m "$P2"); }
"$ADB" push "$P1" "$P2" /data/local/tmp/ >/dev/null
"$ADB" shell "su -c 'cp /data/local/tmp/module.prop /data/local/tmp/service.sh $DST/ && chmod 755 $DST/service.sh && rm /data/local/tmp/module.prop /data/local/tmp/service.sh'"

echo "==== [3/3] 立即生效(跑一次 service.sh)===="
"$ADB" shell "su -c sh $DST/service.sh &"
sleep 2
echo ""
echo "完成。重启设备验证开机自启,看日志:"
echo "  $ADB shell su -c cat /data/local/tmp/applecase-keepalive.log"
