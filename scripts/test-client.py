#!/usr/bin/env python3
"""苹果皮端到端测试客户端(PC 模拟 iPhone)。

用法:
  python test-client.py watch           # 订阅 applecase/# 看所有事件
  python test-client.py sms <to> <body> [slot]   # 发 SMS_SEND 指令
  python test-client.py call <number> [slot]     # 发 CALL_PLACE 指令
  python test-client.py ctrl <answer|hangup|mute> [on]  # 发 CALL_CONTROL
  python test-client.py audio down [秒]    # 开下行桥:收 GW 抓到的通话声,存 out.wav 并分析
  python test-client.py audio tx <wav>     # 开上行桥:把 16k/mono/16bit wav 灌进通话上行
  python test-client.py audio stop         # 停止桥接

鉴权 token = sha256(preshared_key),与网关 BuildConfig.PRESHARED_KEY 一致。
"""
import hashlib
import json
import socket
import struct
import sys
import time
import uuid
import wave

import paho.mqtt.client as mqtt

BROKER = "192.168.22.15"
PORT = 1883
USER = "applecase"
PASS = "change-me"
KEY = "dev-secret-change-me"
DEVICE = "Q968-default"


AUDIO_UDP_PORT = 51821   # PC 侧收发音频的 UDP 端口


def my_ip():
    """本机到 broker 方向的出口 IP(USB 网段)。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((BROKER, 9))
        return s.getsockname()[0]
    finally:
        s.close()


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
    elif mode == "audio":
        sub = sys.argv[2] if len(sys.argv) > 2 else "down"
        c.subscribe("applecase/#", 1)
        if sub == "stop":
            c.publish(f"applecase/{DEVICE}/cmd", json.dumps(envelope("AUDIO_CTRL", {"action": "stop"})), 1)
            print("AUDIO_CTRL stop sent")
            c.loop_start(); time.sleep(3); c.loop_stop()
        elif sub == "down":
            secs = int(sys.argv[3]) if len(sys.argv) > 3 else 10
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.bind(("0.0.0.0", AUDIO_UDP_PORT))
            sock.settimeout(1)
            cmd = envelope("AUDIO_CTRL", {"action": "start", "dir": "down", "codec": "pcm16",
                                          "params": {"ip": my_ip(), "port": AUDIO_UDP_PORT}})
            c.publish(f"applecase/{DEVICE}/cmd", json.dumps(cmd), 1)
            print(f"AUDIO_CTRL start(down) sent, listening UDP :{AUDIO_UDP_PORT} for {secs}s ...")
            c.loop_start()
            pcm = bytearray()
            frames = 0
            t0 = time.time()
            while time.time() - t0 < secs:
                try:
                    data, addr = sock.recvfrom(2048)
                except socket.timeout:
                    continue
                if len(data) <= 8:
                    continue
                if (data[0] >> 4) != 1 or (data[0] & 0x0F) != 1:
                    continue
                pcm += data[8:]
                frames += 1
            c.loop_stop()
            sock.close()
            stop = envelope("AUDIO_CTRL", {"action": "stop"})
            c.publish(f"applecase/{DEVICE}/cmd", json.dumps(stop), 1)
            time.sleep(0.5)
            # 统计
            import array
            samples = array.array("h", bytes(pcm[:len(pcm) // 2 * 2]))
            peak = max((abs(x) for x in samples), default=0)
            nonzero = sum(1 for x in samples[::20] if abs(x) > 300)
            sampled = max(1, len(samples[::20]))
            out = "out.wav"
            with wave.open(out, "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
                w.writeframes(bytes(pcm))
            print(f"frames={frames} bytes={len(pcm)} peak={peak} nonSilent~{nonzero * 100 // sampled}% -> {out}")
        elif sub == "tx":
            wavf = sys.argv[3]
            with wave.open(wavf, "rb") as w:
                assert w.getnchannels() == 1 and w.getframerate() == 16000 and w.getsampwidth() == 2,                     "需要 16kHz/单声道/16bit wav"
                pcm = w.readframes(w.getnframes())
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            cmd = envelope("AUDIO_CTRL", {"action": "start", "dir": "up", "codec": "pcm16",
                                          "params": {"ip": my_ip(), "port": AUDIO_UDP_PORT}})
            c.publish(f"applecase/{DEVICE}/cmd", json.dumps(cmd), 1)
            print(f"AUDIO_CTRL start(up) sent, streaming {len(pcm)} bytes to {BROKER}:51820 ...")
            c.loop_start()
            seq = 0
            frame = 640
            for off in range(0, len(pcm), frame):
                chunk = pcm[off:off + frame]
                hdr = struct.pack(">B", (1 << 4) | 2) + struct.pack(">I", seq)[1:] +                       struct.pack(">I", int(time.time() * 1000) & 0x7FFFFFFF)
                sock.sendto(hdr + chunk, (BROKER, 51820))
                seq += 1
                time.sleep(0.02)
            time.sleep(1)
            stop = envelope("AUDIO_CTRL", {"action": "stop"})
            c.publish(f"applecase/{DEVICE}/cmd", json.dumps(stop), 1)
            c.loop_stop()
            sock.close()
            print(f"sent {seq} uplink frames")
        else:
            print("audio 子命令: down / tx <wav> / stop")
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
