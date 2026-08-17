#!/system/bin/sh
# 苹果皮网关保活脚本(Magisk service.sh,late_start 服务阶段执行)
#
# 职责:
# 1. 等待系统启动完成后拉起网关前台服务(比 App 内 BootReceiver 可靠,
#    root 身份不受后台启动限制)
# 2. 加入 Doze 白名单
# 3. 看门狗:进程不在了就补拉(老人机厂商省电策略激进,这是短信不漏收的生命线)
#
# 日志:/data/local/tmp/applecase-keepalive.log

PKG=com.applecase.gateway
SVC="$PKG/.GatewayService"
LOG=/data/local/tmp/applecase-keepalive.log

exec >> "$LOG" 2>&1
echo "==== $(date) service.sh start ===="

# 1) 等系统起来(最多 4 分钟)
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do
    sleep 2
    i=$((i + 1))
done
sleep 15   # 再等 telephony/wifi 就绪

# 2) Doze 白名单
dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 && echo "doze whitelist added"

start_gw() {
    # 应用若处于 stopped 状态(刚安装/被强停),显式组件启动会同时解除 stopped
    am start-foreground-service -n "$SVC" >/dev/null 2>&1 \
        && echo "$(date) started $SVC" \
        || echo "$(date) start $SVC FAILED"
}

# 3) 首次拉起
start_gw

# 4) 看门狗:每 60s 检查一次
(
    while true; do
        sleep 60
        if ! pidof "$PKG" >/dev/null 2>&1; then
            echo "$(date) watchdog: process gone, restarting"
            start_gw
        fi
    done
) &
echo "==== $(date) watchdog armed ===="
