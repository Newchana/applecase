# iPhone 调试客户端(阶段一)

> 阶段一最小验证客户端:连 broker、显示收到的短信、能发"发短信"指令。
> 不是最终产品 UI,只用来验证端到端链路。

## 创建步骤(Xcode)

1. **新建工程**:Xcode → New → Project → iOS App
   - Product Name: `AppleCaseDebug`
   - Interface: **SwiftUI**
   - Language: Swift
   - 最低版本 iOS 15

2. **加 CocoaMQTT 依赖**:File → Add Package Dependencies,粘贴
   ```
   https://github.com/emqx/CocoaMQTT
   ```
   勾选 `CocoaMQTT` library。

3. **替换 `AppleCaseDebugApp.swift` 和 `ContentView.swift`** 为下面两个文件的内容。

4. **配置**:把 `ContentView.swift` 里的 `brokerHost` / `deviceId` / `presharedKey` 改成你的实际值(和 Q968 端一致)。

5. Run。发条短信到 Q968 的卡,这里应该实时显示。

---

## AppleCaseDebugApp.swift

```swift
import SwiftUI

@main
struct AppleCaseDebugApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

## ContentView.swift

```swift
import SwiftUI
import CocoaMQTT

struct SmsMessage: Identifiable, Codable {
    let id: String
    let from: String
    let body: String
    let ts: Double
}

class GatewayClient: ObservableObject {
    @Published var messages: [SmsMessage] = []
    @Published var connected = false
    @Published var statusText = "未连接"

    // ===== 配置(改成你的实际值) =====
    let brokerHost = "10.147.17.1"      // broker 的 ZeroTier IP
    let brokerPort: UInt16 = 1883
    let deviceId = "Q968-default"        // 与 Q968 端 resolveDeviceId() 一致
    let username = "applecase"
    let password = "change-me"
    let presharedKey = "dev-secret-change-me"  // 与 Q968 BuildConfig.PRESHARED_KEY 一致
    // ================================

    private var mqtt: CocoaMQTT?

    // 计算 token = SHA256(presharedKey)
    private var token: String {
        let data = Data(presharedKey.utf8)
        let digest = data.sha256()
        return digest.compactMap { String(format: "%02x", $0) }.joined()
    }

    func connect() {
        let clientId = "cl-ios-" + UUID().uuidString.prefix(8)
        mqtt = CocoaMQTT(clientID: String(clientId), host: brokerHost, port: brokerPort)
        mqtt?.username = username
        mqtt?.password = password
        mqtt?.keepAlive = 30
        mqtt?.autoReconnect = true
        mqtt?.delegate = self

        let ok = mqtt?.connect() ?? false
        statusText = ok ? "连接中..." : "连接失败"
    }

    func sendSms(to: String, body: String) {
        // 发 SMS_SEND 指令到 applecase/{deviceId}/cmd
        let msgId = "ios-" + UUID().uuidString.prefix(8)
        let payload: [String: Any] = [
            "v": 1,
            "id": msgId,
            "type": "SMS_SEND",
            "ts": Int(Date().timeIntervalSince1970 * 1000),
            "token": token,
            "data": ["to": to, "body": body, "simSlot": 0]
        ]
        guard let json = try? JSONSerialization.data(withJSONObject: payload),
              let jsonStr = String(data: json, encoding: .utf8) else { return }
        let topic = "applecase/\(deviceId)/cmd"
        mqtt?.publish(topic, withString: jsonStr, qos: .qos1)
        statusText = "已发送发短信指令: \(msgId)"
    }
}

extension GatewayClient: CocoaMQTTDelegate {
    func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        if ack == .accept {
            DispatchQueue.main.async {
                self.connected = true
                self.statusText = "已连接"
            }
            // 订阅短信事件 + 回执
            mqtt.subscribe("applecase/\(deviceId)/event/#", qos: .qos1)
            mqtt.subscribe("applecase/\(deviceId)/cmd/ack", qos: .qos1)
            mqtt.subscribe("applecase/\(deviceId)/status", qos: .qos1)
        }
    }

    func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
        guard let str = message.string else { return }
        guard let data = str.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        let type = obj["type"] as? String ?? ""
        let msgData = obj["data"] as? [String: Any] ?? [:]

        if type == "SMS_IN" {
            let sms = SmsMessage(
                id: msgData["id"] as? String ?? UUID().uuidString,
                from: msgData["from"] as? String ?? "?",
                body: msgData["body"] as? String ?? "",
                ts: msgData["ts"] as? Double ?? 0
            )
            DispatchQueue.main.async {
                self.messages.insert(sms, at: 0)
            }
        } else if type == "SMS_OUT" {
            let status = msgData["status"] as? String ?? "?"
            let ref = msgData["ref"] as? String ?? ""
            DispatchQueue.main.async {
                self.statusText = "短信发送结果: \(status) (ref=\(ref.prefix(8)))"
            }
        } else if type == "HELLO" {
            let ip = msgData["ip"] as? String ?? "?"
            DispatchQueue.main.async {
                self.statusText = "网关在线 IP=\(ip)"
            }
        }
    }

    func mqtt(_ mqtt: CocoaMQTT, didStateChangeTo state: CocoaMQTTConnState) {}
    func mqtt(_ mqtt: CocoaMQTT, didPublishMessage message: CocoaMQTTMessage, id: UInt16) {}
    func mqtt(_ mqtt: CocoaMQTT, didPublishAck id: UInt16) {}
    func mqtt(_ mqtt: CocoaMQTT, didSubscribeTopics success: NSDictionary, failed: [String]) {}
    func mqtt(_ mqtt: CocoaMQTT, didUnsubscribeTopics topics: [String]) {}
    func mqttDidDisconnect(_ mqtt: CocoaMQTT, withError err: Error?) {
        DispatchQueue.main.async {
            self.connected = false
            self.statusText = "已断开: \(err?.localizedDescription ?? "")"
        }
    }
}

struct ContentView: View {
    @StateObject private var client = GatewayClient()
    @State private var sendTo = ""
    @State private var sendBody = ""

    var body: some View {
        NavigationView {
            List {
                Section(header: Text(client.statusText)) {
                    Button(client.connected ? "已连接" : "连接") {
                        if !client.connected { client.connect() }
                    }
                    .buttonStyle(.bordered)
                    .tint(client.connected ? .green : .blue)
                }

                Section(header: Text("发短信测试")) {
                    TextField("收件号码 +86...", text: $sendTo)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.phonePad)
                    TextField("短信内容", text: $sendBody)
                        .textFieldStyle(.roundedBorder)
                    Button("发送") {
                        guard !sendTo.isEmpty, !sendBody.isEmpty else { return }
                        client.sendSms(to: sendTo, body: sendBody)
                    }
                    .buttonStyle(.borderedProminent)
                }

                Section(header: Text("收到的短信")) {
                    ForEach(client.messages) { msg in
                        VStack(alignment: .leading) {
                            Text(msg.from).font(.headline)
                            Text(msg.body).font(.body)
                        }
                    }
                }
            }
            .navigationTitle("苹果皮调试")
        }
    }
}
```

## 验证清单

部署 Q968 + 启动 broker 后,用这个客户端验证:

- [ ] 打开 App,点"连接",状态变"已连接"或"网关在线 IP=..."
- [ ] 用另一部手机发短信到 Q968 的卡 → 这里几秒内显示该短信
- [ ] 在"发短信测试"里填号码+内容点发送 → Q968 那张卡真实发出一条短信 → 状态显示"sent"
- [ ] 杀掉 App 重开,retained 的 status 消息还能读到网关状态

四项全过 = 阶段一通信骨架端到端打通。
