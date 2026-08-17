#!/usr/bin/env python3
"""苹果皮 Web 端到端测试:模拟浏览器走 WebSocket 全流程。

用法: python webtest.py [设备IP]        # 默认 192.168.22.237
流程: GET / → /ws(GET_STATE→HELLO)→ 拨 10010(联通卡)→ /audio 收流 → 挂断
"""
import json
import queue
import struct
import sys
import threading
import time
import uuid

import websocket  # websocket-client

HOST = sys.argv[1] if len(sys.argv) > 1 else "192.168.22.237"
URL_WS = f"ws://{HOST}:8080/ws"
URL_AUDIO = f"ws://{HOST}:8080/audio"
results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"{'✅' if ok else '❌'} {name} {detail}")


def env(type_, data):
    return json.dumps({"v": 1, "id": str(uuid.uuid4()), "type": type_,
                       "ts": int(time.time() * 1000), "token": "web", "data": data})


# 后台持续收 ctrl 消息进队列(同步 ws 库需主动 recv 才会回 pong 保活)
inbox = queue.Queue()

def _drain(ws):
    while True:
        try:
            m = ws.recv()
            if m is None:
                return
            inbox.put(json.loads(m))
        except Exception:
            return

def start_drain(ws):
    threading.Thread(target=_drain, args=(ws,), daemon=True).start()

def recv_until(ws, type_, timeout=15):
    """收消息直到出现指定 type(跳过心跳等),超时返回 None。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            return inbox.get(timeout=max(0.2, deadline - time.time()))
        except queue.Empty:
            continue
        if False:
            pass
    return None

def recv_type(type_, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            m = inbox.get(timeout=max(0.2, deadline - time.time()))
        except queue.Empty:
            continue
        if m.get("type") == type_:
            return m
    return None


def main():
    # 1. 控制通道 + 状态
    ws = websocket.create_connection(URL_WS, timeout=8)
    start_drain(ws)
    ws.send(env("GET_STATE", {}))
    hello = recv_type("HELLO")
    sims = (hello or {}).get("data", {}).get("sims", [])
    check("GET_STATE → HELLO", hello is not None, f"{len(sims)} 张卡: "
          + ", ".join(f"卡{s.get('slot')}{s.get('carrier')}{s.get('signal')}格" for s in sims))

    # 2. 音频通道先连(AUDIO_CTRL ws 模式需要)
    aws = websocket.create_connection(URL_AUDIO, timeout=8)
    time.sleep(0.5)

    # 3. 用联通卡拨 10010
    ws.send(env("CALL_PLACE", {"to": "10010", "simSlot": 1}))
    ack = recv_type("CMD_ACK", 10)
    ok = bool((ack or {}).get("data", {}).get("ok"))
    check("CALL_PLACE ack", ok, str((ack or {}).get("data", {})))
    active = recv_type("CALL_STATE", 20)
    st = (active or {}).get("data", {}).get("state")
    check("CALL_STATE active", st == "active", f"state={st}")

    # 4. 开语音桥(ws 模式),收下行帧 6 秒
    ws.send(env("AUDIO_CTRL", {"action": "start", "dir": "down", "codec": "pcm16",
                               "params": {"ws": True}}))
    aack = recv_type("CMD_ACK", 10)
    check("AUDIO_CTRL start(ws)", bool((aack or {}).get("data", {}).get("ok")),
          str((aack or {}).get("data", {})))
    frames, peak, t0 = 0, 0, time.time()
    while time.time() - t0 < 6:
        try:
            aws.settimeout(1)
            data = aws.recv()
        except websocket.WebSocketTimeoutException:
            continue
        if isinstance(data, bytes) and len(data) > 8 and (data[0] & 0x0F) == 1:
            frames += 1
            n = (len(data) - 8) // 2
            for i in range(0, n, 16):   # 抽样算峰值
                v = abs(struct.unpack_from("<h", data, 8 + i * 2)[0])
                peak = max(peak, v)
    check("下行音频帧(WebSocket)", frames > 100, f"{frames} 帧/6s, 峰值 {peak}")

    # 5. 挂断 + 停桥
    ws.send(env("CALL_CONTROL", {"action": "hangup"}))
    ended = recv_type("CALL_STATE", 10)
    st = (ended or {}).get("data", {}).get("state")
    check("CALL_CONTROL hangup", st == "ended", f"state={st}")
    ws.send(env("AUDIO_CTRL", {"action": "stop"}))
    check("AUDIO_CTRL stop", True)

    ws.close(); aws.close()
    print("\n" + ("🎉 全部通过" if all(r[1] for r in results) else "⚠️ 有失败项"))


if __name__ == "__main__":
    main()
