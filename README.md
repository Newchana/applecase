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
- 🔨 **阶段一(进行中)** —— 通信骨架 + 短信收发(本文档)
- ⬜ 阶段二 —— 来电通知/拨号/状态同步/保活加固
- ⬜ 阶段三 —— 实时语音桥接完全体

## 目录结构

```
applecase/
├── VERIFICATION.md         硬件能力验证报告(已完成)
├── docs/PROTOCOL.md        通信协议规范
├── android/                Q968 端 Android 工程(Java)
├── ios/                    iPhone 端(阶段一:调试客户端)
├── scripts/
│   ├── deploy-android.sh   一键编译+部署到 Q968
│   └── mqtt-broker/        mosquitto 配置
└── README.md               本文件
```

## 快速开始(阶段一:打通短信链路)

### 准备

1. Q968:已 root(Magisk)、USB 调试开启、SIM 卡插好
2. 一台机器跑 MQTT broker(你的电脑或一台 VPS)
3. iPhone:能跑 Xcode(有 Mac)
4. 三台机器(或两端)能联网

### 第 1 步:搭 ZeroTier 网络

1. 注册 [zerotier.com](https://www.zerotier.com/),创建一个 Network,记下 NetworkID
2. iPhone:App Store 装 ZeroTier,加入这个 Network
3. Q968:推荐用 Magisk 模块 `zerotier-magisk`(headless,无 UI,适合老人机),加入同一 Network
4. (可选,国内加速)在一台有公网 IP 的 VPS 上自建 moon 节点
5. 在 ZeroTier 后台给 Q968 和 iPhone 各授权、记下它们的 ZeroTier IP

### 第 2 步:部署 MQTT broker

在 broker 机器上(可以是你的电脑、VPS、或 ZeroTier 网内任一台):

```bash
# Ubuntu/Debian
sudo apt install mosquitto mosquitto-clients
# 生成密码文件
sudo mosquitto_passwd -c /etc/mosquitto/passwd applecase
# (输入密码 change-me)
# 用项目里的配置
sudo cp scripts/mqtt-broker/mosquitto.conf /etc/mosquitto/conf.d/applecase.conf
sudo systemctl restart mosquitto
```

记下 broker 的 **ZeroTier IP**(例如 `10.147.17.1`)。

### 第 3 步:配置并部署 Q968 端

1. 改 `android/app/src/main/java/com/applecase/gateway/GatewayService.java` 里的配置:
   ```java
   private static final String BROKER_HOST = "10.147.17.1";  // ← 改成 broker 的 ZeroTier IP
   private static final String MQTT_USER = "applecase";
   private static final String MQTT_PASS = "change-me";      // ← 改成你的密码
   ```
2. (可选)改 deviceId:`android/app/build.gradle` 的 `DEFAULT_DEVICE_ID`
3. 编译部署:
   ```bash
   ./scripts/deploy-android.sh
   ```
4. 看 logcat 确认连上 broker:
   ```bash
   adb logcat -s AC.Gateway AC.Mqtt AC.SmsRx AC.SmsTx
   ```
   应看到 `connected to ...` 和 `subscribed applecase/.../cmd/#`

### 第 4 步:部署 iPhone 调试客户端

照 [ios/README.md](ios/README.md) 在 Xcode 建工程,改配置后 Run。

### 第 5 步:验证

1. App 显示"已连接"或"网关在线"
2. 用第三部手机发短信到 Q968 的卡 → iPhone 客户端几秒内显示该短信 ✅
3. 在 iPhone 客户端发"发短信" → Q968 真实发出 → 状态显示 sent ✅

链路打通!阶段一完成。

## 技术要点

- **进程模型**:单 APK 前台服务(普通权限)。语音桥接(需 `CAPTURE_AUDIO_OUTPUT`/`MODIFY_PHONE_STATE` signature 级权限)留到阶段三用 root 特权进程解决。
- **保活**:前台服务 + 电池白名单 + (阶段二)Magisk `service.sh` 开机自启。老人机省电激进,保活是短信不漏收的生命线。
- **鉴权**:每条 MQTT 消息带 `token = SHA256(presharedKey)`。发短信/拨号是高敏指令,防止 broker 暴露后被滥用。
- **双卡**:`SmsManager` 按 subscriptionId 区分卡槽;阶段二读 `TelephonyManager` 上报每张卡状态。

## 开发

```bash
# 编译
cd android && ./gradlew.bat assembleDebug

# 看 logcat
adb logcat -s AC.Gateway AC.Mqtt AC.SmsRx AC.SmsTx AC.UdpAudio

# 改完代码重新部署
./scripts/deploy-android.sh
```

## 已知限制(阶段一)

- deviceId / broker 配置是硬编码的(阶段二改 SharedPreferences)
- HELLO 的 SIM 信息是空的(阶段二从 TelephonyManager 读)
- 仅实现了 SMS_SEND 指令;来电/拨号/语音在后续阶段
- 无 TLS(明文 MQTT),仅适合 ZeroTier 私网内;对公网暴露需加 TLS
