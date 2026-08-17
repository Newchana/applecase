# 苹果皮(AppleCase)

> 把以晴 Q968 老人机变成 iPhone 的通信外挂 —— 短信/电话/语音桥接。
> 灵感来自 2010 年的"苹果皮 520"(Apple Peel),但用现代架构实现:ZeroTier 组网 + MQTT 指令 + UDP 音频。

## 这是什么

一台插了 SIM 卡的 Android 老人机(Q968,展锐 ums9620,已 root),作为 iPhone 的:
- **短信代理**:iPhone 收发短信,实际走 Q968 的卡
- **电话代理**:iPhone 上点拨号,Q968 拨出;来电推送到 iPhone
- **语音桥接**(阶段三):对着 iPhone 说话,实际通话在 Q968 的卡上

## 架构

```
Q968 (Android 13, root)  ←─ ZeroTier(P2P) ─→  iPhone (Swift App)
  ├ SMS 收发                MQTT(指令/事件)     ├ 消息界面
  ├ 来电/拨号                UDP(实时音频)        ├ 通话界面
  └ 语音桥接(AudioRec/Trk)                      └ Opus 编解码
```

详见 [docs/PROTOCOL.md](docs/PROTOCOL.md) 协议规范。

## 进度

- ✅ **硬件验证** —— 证明 Q968 支持双向语音桥接,见 [VERIFICATION.md](VERIFICATION.md)
- ✅ **阶段一** —— 通信骨架 + 短信收发
- ✅ **阶段二** —— 来电通知/拨号/通话控制/双卡状态/配置化/保活(2026-08-17 实机端到端验证)
- 🔨 **阶段三(进行中)** —— 语音桥双工管道已打通,通话中实测待做(见下)

### 阶段二实机验证记录(2026-08-17,USB 网络 + amqtt broker)

| 链路 | 结果 |
|---|---|
| HELLO(retained,真实双卡:slot0 移动/slot1 联通,信号档位) | ✅ |
| HEARTBEAT / SIM_STATE | ✅ |
| SMS_SEND → CMD_ACK → SMS_OUT sent → delivered 全回路 | ✅ |
| CALL_PLACE 指定卡0/卡1 拨出(TelecomManager 选卡) | ✅ 双卡各自真实拨出 |
| CALL_STATE active/ended(含卡槽归属、去电号码回填) | ✅ |
| CALL_CONTROL hangup(TelecomManager.endCall) | ✅ |
| CALL_INCOMING(真实来电) | ⏳ 待用户用另一部手机打入验证(代码路径与去电对称) |

### 阶段三进展(2026-08-17)

架构(App ⇄ root 特权中继):
```
iPhone ⇄(UDP/PCM16 16k mono)⇄ VoiceBridge(App) ⇄(localhost TCP)⇄ RootAudioRelay(root app_process)
                                                          ├ AudioRecord(VOICE_CALL)  下行捕获
                                                          └ AudioTrack(INCALL_MUSIC→TELEPHONY_TX) 上行注入
```
- signature 级权限(CAPTURE_AUDIO_OUTPUT)拿不到的解法:su 拉起 `app_process` 跑本 APK 里的
  `RootAudioRelay`,root uid 直接过 AudioFlinger 权限校验(priv-app 挂载在本 ROM 因 FBE 数据目录问题失败)
- Magisk su 白名单固化:deploy 脚本自动写入 `magisk.db`(重装后 uid 变化会自动更新)
- 已验证:AUDIO_CTRL 全回路、下行 50fps 精确出流(无通话时静音,符合预期)、上行 150 帧灌入 AudioTrack
- 待真实通话中验证:下行是否抓到人声、上行 INCALL_MUSIC 是否真的混入对方听筒
  (FLAG_INCALL_MUSIC 反射被 hidden-API 拦截,当前兜底 0x800,若路由不对需试 0x1000 等)
- PC 测试:`python scripts/test-client.py audio down 15` / `audio tx <wav>` / `audio stop`

双卡要点:
- 发短信/拨号指令都带 `simSlot`(0/1),按 subscriptionId → PhoneAccountHandle 精确选卡
- 来电/去电按"每张卡独立的通话状态监听"识别卡槽(本机 ROM 的 PHONE_STATE 广播不带 subscription,踩坑后改用 per-sub 监听)
- 本机号码:国内 SIM 不写 MSISDN(AT+CNUM / AT+CRSM 实测为空),App 配置界面手动填一次,上报在 HELLO 的 `sims[].number`

## 目录结构

```
applecase/
├── VERIFICATION.md         硬件能力验证报告(已完成)
├── docs/PROTOCOL.md        通信协议规范(阶段二已落地)
├── android/                Q968 端 Android 工程(Java)
├── ios/                    iPhone 端(阶段一/二:调试客户端)
├── scripts/
│   ├── deploy-android.sh   一键编译+部署到 Q968(含权限授予)
│   ├── install-keepalive.sh  装 Magisk 保活模块(开机自启+看门狗)
│   ├── magisk-applecase/   保活模块本体(module.prop + service.sh)
│   ├── test-client.py      PC 端测试客户端(模拟 iPhone,watch/sms/call/ctrl)
│   └── mqtt-broker/        mosquitto/amqtt 配置
└── README.md               本文件
```

## 快速开始(阶段二:短信 + 电话)

### 准备

1. Q968:已 root(Magisk)、USB 调试开启、SIM 卡插好
2. 一台机器跑 MQTT broker(你的电脑或一台 VPS)
3. iPhone:能跑 Xcode(有 Mac)
4. 三台机器(或两端)能联网

### 第 1 步:搭 ZeroTier 网络(生产)

1. 注册 [zerotier.com](https://www.zerotier.com/),创建一个 Network,记下 NetworkID
2. iPhone:App Store 装 ZeroTier,加入这个 Network
3. Q968:推荐用 Magisk 模块 `zerotier-magisk`(headless,无 UI,适合老人机),加入同一 Network
4. (可选,国内加速)在一台有公网 IP 的 VPS 上自建 moon 节点
5. 在 ZeroTier 后台给 Q968 和 iPhone 各授权、记下它们的 ZeroTier IP

> 没有 ZeroTier 也能开发调试:Q968 的 USB 网口(`sipa_usb0`,192.168.195.x)和电脑直通,
> broker 跑电脑上,App 配置里填电脑的 USB 网段 IP 即可(本文档的实机验证就是这么做的)。

### 第 2 步:部署 MQTT broker

生产用 mosquitto:

```bash
# Ubuntu/Debian
sudo apt install mosquitto mosquitto-clients
sudo mosquitto_passwd -c /etc/mosquitto/passwd applecase
# (输入密码 change-me)
sudo cp scripts/mqtt-broker/mosquitto.conf /etc/mosquitto/conf.d/applecase.conf
sudo systemctl restart mosquitto
```

开发调试用 Python amqtt(PC 上):

```bash
pip install amqtt
cd scripts/mqtt-broker
python -c "from pwdlib import PasswordHash; print('applecase:'+PasswordHash.recommended().hash('change-me'))" > mqtt_test.passwd
amqtt -c amqtt-test.yaml
```

记下 broker 的 IP。

### 第 3 步:部署 Q968 端

1. 编译部署(会自动授予全部运行时权限 + Doze 白名单):
   ```bash
   ./scripts/deploy-android.sh
   ```
2. 打开 App,在配置界面填:broker 地址/端口/账号/密码、每张卡的本机号码(可选),保存并启动
3. (可选,强烈建议)装保活模块 —— 开机自启 + 看门狗:
   ```bash
   ./scripts/install-keepalive.sh
   ```
4. 看 logcat 确认:
   ```bash
   adb logcat -s AC.Gateway AC.Mqtt AC.SmsRx AC.SmsTx AC.Sim AC.CallRx AC.CallTx
   ```

### 第 4 步:部署 iPhone 调试客户端

照 [ios/README.md](ios/README.md) 在 Xcode 建工程,改配置后 Run。

### 第 5 步:验证

1. App 显示"已连接"或"网关在线",双卡状态条显示两张卡
2. 用第三部手机发短信到 Q968 的卡 → iPhone 客户端几秒内显示 ✅
3. 发短信指令(可选卡)→ Q968 对应卡真实发出 → sent/delivered ✅
4. 第三部手机打 Q968 → iPhone 响铃提示 → 接听/挂断可控 ✅
5. iPhone 上选卡拨号 → Q968 对应卡真实拨出 ✅

没有 iPhone 在手?用 PC 测试客户端模拟:

```bash
python scripts/test-client.py watch          # 看所有事件
python scripts/test-client.py sms 10086 "hi" 0   # 卡0发短信
python scripts/test-client.py call 10086 0        # 卡0拨号
python scripts/test-client.py ctrl hangup        # 挂断
```

## 技术要点

- **进程模型**:单 APK 前台服务(普通权限)。语音桥接(需 `CAPTURE_AUDIO_OUTPUT`/`MODIFY_PHONE_STATE` signature 级权限)留到阶段三用 root 特权进程解决。
- **双卡**:一切按 subscriptionId 区分。发短信/拨号带 `simSlot` 选卡;通话状态用 per-sub 监听识别卡槽;SIM 状态按卡上报。
- **保活**:前台服务 + 电池白名单 + Magisk `service.sh` 开机自启 + 看门狗(进程被杀 60s 内补拉)。老人机省电激进,保活是短信不漏收的生命线。
- **MQTT 首连重试**:Paho 的 automaticReconnect 只在首次连接成功后才生效,首连失败需自建重试循环(已修)。
- **鉴权**:每条 MQTT 消息带 `token = SHA256(presharedKey)`。发短信/拨号是高敏指令,防止 broker 暴露后被滥用。

## 已知限制(阶段二)

- 本机号码需手动配置(国内 SIM 不写 MSISDN;AT 通道已打通——root 下 `/dev/stty_nr7` 起的空闲通道可用,`AT+CNUM`/`AT+CRSM` 实测卡上无号码,阶段三可用 AT 做底层调试)
- 来电的真实铃声/未接在 iPhone 端只是事件,尚无本地通知推送形态
- 无 TLS(明文 MQTT),仅适合 ZeroTier 私网内;对公网暴露需加 TLS
- 语音桥接(阶段三)未动工

## 开发

```bash
# 编译
cd android && ./gradlew.bat assembleDebug

# 看 logcat
adb logcat -s AC.Gateway AC.Mqtt AC.SmsRx AC.SmsTx AC.Sim AC.CallRx AC.CallTx AC.UdpAudio

# 改完代码重新部署(含权限授予)
./scripts/deploy-android.sh

# PC 模拟 iPhone 做端到端测试
python scripts/test-client.py watch
```
