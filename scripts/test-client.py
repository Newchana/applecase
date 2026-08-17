#!/usr/bin/env python3
"""苹果皮端到端测试客户端(PC 模拟 iPhone)。

用法:
  python test-client.py watch           # 订阅 applecase/# 看所有事件
  python test-client.py sms <to> <body> [slot]   # 发 SMS_SEND 指令
  python test-client.py call <number> [slot]     # 发 CALL_PLACE 指令
  python test-client.py ctrl <answer|hangup|mute> [on]  # 发 CALL_CONTROL

鉴权 token = sha256(preshared_key),与网关 BuildConfig.PRESHARED_KEY 一致。
"""
import hashlib
import json
import sys
import time
import uuid

import paho.mqtt.client as mqtt

BROKER = "192.168.195.75"
PORT = 1883
USER = "applecase"
PASS = "change-me"
KEY = "dev-secret-change-me"
DEVICE = "Q968-default"


def token():
    return hashlib.sha256(KEY.encode()).hexdigest()


def envelope(t, data):
    return {
        "v": 1, "id": str(uuid.uuid4()), "type": t,
        "ts": int(time.time() * 1000), "token": token(), "data": data,
    }


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "watch"
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="cl-test-" + uuid.uuid4().hex[:8])
    c.username_pw_set(USER, PASS)

    def on_msg(_c, _u, m):
        try:
            j = json.loads(m.payload)
            print(f"[{m.topic}] type={j.get('type')} data={json.dumps(j.get('data'), ensure_ascii=False)}")
        except Exception:
            print(f"[{m.topic}] {m.payload[:200]}")

    c.on_message = on_msg
    c.connect(BROKER, PORT, 30)

    if mode == "watch":
        c.subscribe("applecase/#", 1)
        print("watching applecase/# ...")
        c.loop_forever()
    elif mode == "sms":
        to, body = sys.argv[2], sys.argv[3]
        slot = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        c.subscribe("applecase/#", 1)
        c.publish(f"applecase/{DEVICE}/cmd", json.dumps(envelope("SMS_SEND", {"to": to, "body": body, "simSlot": slot})), 1)
        print(f"SMS_SEND to={to} slot={slot} sent")
        c.loop_start(); time.sleep(8); c.loop_stop()
    elif mode == "call":
        num = sys.argv[2]
        slot = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        c.subscribe("applecase/#", 1)
        c.publish(f"applecase/{DEVICE}/cmd", json.dumps(envelope("CALL_PLACE", {"to": num, "simSlot": slot})), 1)
        print(f"CALL_PLACE to={num} slot={slot} sent")
        c.loop_start(); time.sleep(20); c.loop_stop()
    elif mode == "ctrl":
        action = sys.argv[2]
        on = (sys.argv[3] == "on") if len(sys.argv) > 3 else True
        c.subscribe("applecase/#", 1)
        c.publish(f"applecase/{DEVICE}/cmd", json.dumps(envelope("CALL_CONTROL", {"action": action, "on": on})), 1)
        print(f"CALL_CONTROL {action} on={on} sent")
        c.loop_start(); time.sleep(5); c.loop_stop()
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
