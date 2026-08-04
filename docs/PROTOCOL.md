# 苹果皮通信协议规范 v1

> Q968(网关)与 iPhone(客户端)之间的通信契约。所有模块按此规范实现。
> 传输层:MQTT(指令/事件,QoS 1 保证送达)+ UDP(实时音频)。
> 底层网络:ZeroTier 虚拟网络(P2P,moon 加速)。

---

## 1. 概念与角色

| 角色 | 说明 |
|---|---|
| **Gateway (GW)** | Q968 端,持有 SIM 卡,执行实际的收发短信/通话/语音桥接。是 MQTT 消息的"服务器"。 |
| **Client (CL)** | iPhone 端,发送指令、接收事件。是 MQTT 消息的"客户端"。 |
| **Broker** | MQTT 代理,可自建 mosquitto(VPS 或 ZeroTier 内一台机器)。两端都连它。 |

## 2. 身份与寻址

### 2.1 设备标识

- **deviceId**:Q968 的唯一标识。骨架期用 `Build.serial`(或自定义),格式如 `Q968-XXXX`。用于 topic 命名空间隔离。
- **clientId**:MQTT client id。GW 用 `gw-{deviceId}`,CL 用 `cl-{任意uuid}`。

### 2.2 鉴权(骨架期)

- MQTT broker 账号密码(broker 层鉴权,两端共用一个账号或分两个)。
- 应用层:每条消息带 `token` 字段 = 预共享密钥的 SHA256。骨架期用一个固定密钥(写在配置里),生产期再细化。
- **安全红线**:发短信、拨号这类高敏指令,必须校验 token,否则 broker 一旦暴露,任何人都能让你的卡发短信(有被滥用群发、涉案风险)。

## 3. MQTT Topic 规范

所有 topic 以 `applecase/` 为前缀,`{deviceId}` 为网关命名空间。

### 3.1 状态与心跳

| Topic | 方向 | 说明 |
|---|---|---|
| `applecase/{deviceId}/status` | GW→CL (retained) | 网关在线状态。retained 消息,CL 一连上 broker 就能读到 GW 当前状态。 |
| `applecase/{deviceId}/heartbeat` | GW→CL | 周期心跳(默认 30s)。CL 据此判断 GW 是否存活。 |

### 3.2 指令与回执(CL→GW 请求,GW→CL 响应)

| Topic | 方向 | 说明 |
|---|---|---|
| `applecase/{deviceId}/cmd` | CL→GW | 客户端下发的指令(发短信、拨号等)。 |
| `applecase/{deviceId}/cmd/ack` | GW→CL | 指令回执。用 `ref`(对应原指令的 id)关联。 |

### 3.3 事件(GW→CL 主动上报)

| Topic | 方向 | 说明 |
|---|---|---|
| `applecase/{deviceId}/event/sms/in` | GW→CL | 收到新短信。 |
| `applecase/{deviceId}/event/sms/out` | GW→CL | 短信发送状态变更(发送中/已送达/失败)。 |
| `applecase/{deviceId}/event/call/incoming` | GW→CL | 来电响铃。 |
| `applecase/{deviceId}/event/call/state` | GW→CL | 通话状态变更(接通/挂断/未接)。 |
| `applecase/{deviceId}/event/sim` | GW→CL | SIM 状态变化(信号/插拔/欠费)。 |

## 4. 消息格式

### 4.1 通用信封

所有 MQTT payload 均为 UTF-8 JSON,统一信封:

```json
{
  "v": 1,
  "id": "msg-uuid-v4",
  "ref": "所回复消息的id(仅回执/响应类消息带)",
  "type": "消息类型(见下表)",
  "ts": 1700000000000,
  "token": "sha256(preshared_key)",
  "data": { ... }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | 是 | 协议版本,当前 1。 |
| `id` | 是 | 消息唯一 id(UUID v4)。 |
| `ref` | 否 | 回执/响应消息必填,= 原指令的 `id`,用于关联请求-响应。 |
| `type` | 是 | 消息类型字符串(见 4.2)。 |
| `ts` | 是 | 毫秒时间戳(发送方本地时钟)。 |
| `token` | 是 | 鉴权 SHA256。GW 收到指令校验失败则忽略并记日志。 |
| `data` | 是 | 类型相关负载,见 4.2。 |

### 4.2 消息类型(type 值)

| type | 方向 | data 字段 | 说明 |
|---|---|---|---|
| `HELLO` | GW→CL (retained) | `{deviceId, fw, sims:[{slot,id,carrier,state,signal}], ip}` | 网关上线广播自身能力 |
| `HEARTBEAT` | GW→CL | `{uptime}` | 心跳 |
| `BYE` | GW→CL | `{reason}` | 网关主动下线(优雅关闭) |
| `SMS_SEND` | CL→GW | `{to:"+86...", body:"...", simSlot?:0|1}` | 发短信指令 |
| `SMS_IN` | GW→CL | `{id, slot, from, body, ts}` | 收到新短信(短信中心时间戳) |
| `SMS_OUT` | GW→CL | `{ref, status:"sent"\|"delivered"\|"failed", error?}` | 发送结果回执,ref=原 SMS_SEND 的 id |
| `CMD_ACK` | GW→CL | `{ref, ok:true\|false, error?}` | 通用指令回执(短信之外的) |
| `CALL_INCOMING` | GW→CL | `{id, slot, from, ts}` | 来电响铃(阶段二) |
| `CALL_STATE` | GW→CL | `{ref, state:"ringing"\|"active"\|"ended"\|"missed"}` | 通话状态(阶段二) |
| `CALL_PLACE` | CL→GW | `{to, simSlot?}` | 拨号指令(阶段二) |
| `CALL_CONTROL` | CL→GW | `{action:"answer"\|"hangup"\|"mute"}` | 通话控制(阶段二) |
| `SIM_STATE` | GW→CL | `{slot, state, signal, carrier}` | SIM 状态(阶段二) |
| `AUDIO_CTRL` | CL→GW | `{action:"start"\|"stop", dir:"up"\|"down"\|"both", codec:"opus", params:{...}}` | 音频桥接控制(阶段三) |

> 阶段一仅实现:HELLO / HEARTBEAT / BYE / SMS_SEND / SMS_IN / SMS_OUT / CMD_ACK。其余类型此处先定义占位,后续阶段填实。

### 4.3 阶段一消息示例

**GW 上线(retained status):**
```json
{"v":1,"id":"u1","type":"HELLO","ts":1700000000000,"token":"...","data":{"deviceId":"Q968-abc","fw":"0.1","sims":[{"slot":0,"carrier":"中国移动","state":"READY","signal":18},{"slot":1,"carrier":"中国联通","state":"READY","signal":12}],"ip":"10.147.17.5"}}
```

**CL 发短信:**
```json
{"v":1,"id":"c1","type":"SMS_SEND","ts":1700000001000,"token":"...","data":{"to":"+8613800138000","body":"测试","simSlot":0}}
```

**GW 收到真实短信,推给 CL:**
```json
{"v":1,"id":"g1","type":"SMS_IN","ts":1700000002000,"token":"...","data":{"id":"sms-1","slot":0,"from":"+8610010","body":"您的验证码是 123456","ts":1700000001900}}
```

**GW 短信发送结果:**
```json
{"v":1,"id":"g2","type":"SMS_OUT","ref":"c1","ts":1700000003000,"token":"...","data":{"ref":"c1","status":"sent"}}
```

## 5. UDP 音频帧格式(阶段三填实,此处先定义)

实时语音不走 MQTT(MQTT 适合可靠指令,不适合低延迟音频)。两端的 UDP 端口在 `AUDIO_CTRL.start` 协商时互相告知(填在 data.params 里)。

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  ver  | dir   |                    seq                         |  8 bytes 头
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          ts (ms, 发送方)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         payload (Opus)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| 字段 | 说明 |
|---|---|
| ver | 协议版本(1) |
| dir | 1=下行(GW→CL,对方说话) 2=上行(CL→GW,你说话) |
| seq | 序列号,每帧+1,用于排序/丢包检测 |
| ts | 发送方毫秒时间戳,用于抖动缓冲 |
| payload | Opus 编码音频(20ms 帧,16kHz 单声道窄带,~16-24kbps) |

> 骨架期 UdpAudioSocket 先实现收发空包 + 统计,验证 ZeroTier UDP 打通。真实音频在阶段三接入。

## 6. 错误码

回执 data.error 用以下错误码字符串:

| 错误码 | 说明 |
|---|---|
| `AUTH_FAIL` | token 校验失败 |
| `BAD_REQUEST` | 消息格式/字段错误 |
| `NO_SIM` | 无可用 SIM |
| `SEND_FAILED` | 发短信/拨号失败(底层错误) |
| `BUSY` | 设备忙(如正在通话中无法发短信) |
| `UNSUPPORTED` | 消息类型本版本不支持 |

## 7. 版本演进

- `v` 字段做版本协商。GW 收到 `v` 高于自身的消息,回 `CMD_ACK{ok:false, error:"UNSUPPORTED"}`。
- 新增 type 不破坏旧客户端(旧端收到未知 type 直接忽略 + 记日志,不报错)。
- 字段新增:旧端忽略未知字段(前向兼容)。
- 字段语义变更/移除:升 `v` 版本。
