# 以晴 Q968 苹果皮方案 — 硬件能力验证报告

> 验证日期：2026-08-03
> 验证方法：root + adb 实测（通话进行中抓取 mixer / AudioFlinger / audio_policy 状态）
> 结论：**双向语音桥接可行，上行注入的"硬墙"在本机不存在。**

## 设备信息

| 项 | 值 |
|---|---|
| 型号 | 以晴 Q968 (Sunelan/FIH) |
| 平台 | 紫光展锐 Unisoc **ums9620** (代号 whale) |
| Android | 13 (API 33), arm64-v8a |
| Audio HAL | `audio.primary.whale.so` (展锐自研) |
| Root | ✅ Magisk (su -c uid=0, context=magisk) |
| SIM | 双卡 中国移动 + 中国联通，均 LOADED/在线 |
| Mixer | `sprdphone-sc2730`, 319 个控件 |

## 关键证据链

### 1. Mixer 控件层 — 上行注入/下行捕获 ENUM 齐全

```
147  VBC_DSP_VOICE_CAPTURE_TYPE:
       VOICE_CAPTURE_DOWNLINK          ← 录对方(下行)
       VOICE_CAPTURE_UPLINK            ← 录本机麦克风(上行)
       VOICE_CAPTURE_UPLINK_DOWNLINK   ← 双向

148  VBC_DSP_VOICE_PCM_PLAY_MODE:
       VOICE_PCM_PLAY_UPLINK_MIX       ← 把音频[混入]通话上行
       VOICE_PCM_PLAY_UPLINK_ONLY      ← 把音频[替换为]通话上行
```

展锐 VBC (Voice Baseband Codec) 明确支持 "PCM回放 → 混入/替换通话上行"。

### 2. Mixer 开关层 — 通话时 voice 通路全部激活

通话中 (mCallState=2 OFFHOOK) 自动 ON 的开关：

```
254  S_VOICE_P_CODEC SWITCH    On   ← Voice Playback(上行注入路径)
255  S_VOICE_C_CODEC SWITCH    On   ← Voice Capture(下行捕获路径)
297  S_VOICE_CAP_C SWITCH      On   ← Voice 捕获
305  S_VOICE_PCM_P SWITCH      On   ← Voice PCM Playback
```

空闲时全部 Off，通话时全部 On —— 通路随通话动态开关。

### 3. 数据通道层 — voice 不走标准 ALSA

- `/dev/snd/pcmC0D*` 共 25 个节点，**通话时全部 closed**（voice 不走标准 ALSA）
- voice 走展锐私有通道：`/dev/audio_pipe_voice` (char device, 10,89)
  - 设备树：`unisoc,audio_pipe_voice`, `sprd,channel = 2`
  - 驱动：`sprd_audio_pipe`
- 辅助节点：`/dev/audio_dsp_pcm`(480), `/dev/audio_dsp_call_info`(478) 等 (ioctl 接口)
- 含义：底层 voice 数据是 AGDSP 私有协议，但**应用层无需碰它**（见下）

### 4. ⭐ AudioFlinger / audio_policy 层 — 官方框架入口（决定性）

`dumpsys media.audio_flinger` 在通话中暴露：

```
Output thread AudioOut_25:
  Output devices: 0x10000 (AUDIO_DEVICE_OUT_TELEPHONY_TX)   ← 输出到电话上行
  flags 0x10000 (AUDIO_OUTPUT_FLAG_INCALL_MUSIC)            ← 通话中音乐
  I/O handle 37, Latency 80ms

mixer: mode:2 call_status:2 call_start:1   ← 通话模式已激活
```

`dumpsys media.audio_policy` 路由表：

```
Port ID  9; "Telephony Tx"; {AUDIO_DEVICE_OUT_TELEPHONY_TX}   ← 上行注入端口
Port ID 15; "Telephony Rx"; {AUDIO_DEVICE_IN_TELEPHONY_RX}    ← 下行捕获端口

I/O handle: 37; 0x10000 (AUDIO_OUTPUT_FLAG_INCALL_MUSIC)
  Devices: {AUDIO_DEVICE_OUT_TELEPHONY_TX}

录音源 Sources 含: "Telephony Rx"   ← VOICE_CALL 录音可用
```

## 最终判定

| 能力 | 可行性 | 实现方式 | 证据强度 |
|---|---|---|---|
| 短信收发 | ✅ 完全可行 | `SmsManager` + `SmsReceiver` | 标准 API |
| 来电通知 | ✅ 完全可行 | `CallScreeningService` / 通话状态监听 | 标准 API |
| 远程拨号 | ✅ 完全可行 | `TelecomManager.placeCall` / `ACTION_CALL` | 标准 API |
| **下行语音捕获**(对方→iPhone) | ✅ **可行** | `AudioRecord` 源 `VOICE_CALL` | HAL 支持 Telephony Rx |
| **上行语音注入**(iPhone→对方) | ✅ **可行** | `AudioTrack` flag `INCALL_MUSIC` 路由 `TELEPHONY_TX` | AudioFlinger 暴露端口 + mixer UPLINK_MIX |

**这台机器本身就是一台合格的"语音苹果皮主机"，无需更换硬件。**

## 已部署的调试资源

- tinyalsa 工具：`/data/local/tmp/ta/` (tinycap / tinyplay / tinypcminfo)
- 通话扫描脚本：`/data/local/tmp/scan_call.sh`
- 节点探测脚本：`/data/local/tmp/call_probe.sh`

## 端到端实测结果

1. ✅ `AudioRecord(VOICE_CALL)` 通话中抓到真实下行数据 —— 2026-08-17,拨 10086 通话中,
   经 root 中继(RootAudioRelay)抓取:30s 共 1770 帧,峰值电平 3126/32768,19% 非静默帧
   (与机器人断续说话吻合);空闲时纯静音(无假数据)
2. ✅ `AudioTrack(INCALL_MUSIC)` 上行路由确认 —— AudioAttributes flag 0x800(FLAG_INCALL_MUSIC,
   hidden API 反射被拦,兜底值正确)在通话中创建 `AUDIO_OUTPUT_FLAG_INCALL_MUSIC` 输出并路由
   `AUDIO_DEVICE_OUT_TELEPHONY_TX`(dumpsys 实证);通话中灌入 3s/150 帧正弦波正常写入。
   "对方听到混入音频"的最终主观确认需双端人工测试(robot 无法确认)
3. ⬜ 上下行延迟实测（目标 <300ms 单端到端）—— 管道为 20ms 帧 + UDP 直发,理论低;
   待 iPhone 端接入后实测
4. ⬜ AEC（回声消除）效果验证 —— iPhone 侧用 AVAudioEngine 的 voiceChat 模式自带 AEC
