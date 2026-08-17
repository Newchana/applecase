# iPhone 调试客户端(阶段一/二/三)

> 最小验证客户端:连 broker、收发短信(阶段一)+ 来电显示/双卡拨号/通话控制(阶段二)
> + 实时语音桥(阶段三:对着 iPhone 说话走 Q968 的卡,对方声音回 iPhone)。
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

struct SmsMessage: Identifiable {
    let id: String
    let from: String
    let body: String
    let ts: Double
}

struct CallEntry: Identifiable {
    let id = UUID()
    let callId: String
    let from: String
    let slot: Int
    let dir: String
    var state: String   // ringing | active | ended | missed
}

struct SimInfo {
    let slot: Int
    let carrier: String
    let state: String
    let signal: Int
    let number: String
}

class GatewayClient: ObservableObject {
    @Published var messages: [SmsMessage] = []
    @Published var calls: [CallEntry] = []
    @Published var sims: [SimInfo] = []
    @Published var connected = false
    @Published var statusText = "未连接"

    // ===== 配置(改成你的实际值) =====
    let brokerHost = "10.147.17.1"      // broker 的 ZeroTier IP
    let brokerPort: UInt16 = 1883
    let deviceId = "Q968-default"        // 与 Q968 端配置一致
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

    private func publishCommand(_ type: String, data: [String: Any]) -> String {
        let msgId = "ios-" + UUID().uuidString.prefix(8)
        let payload: [String: Any] = [
            "v": 1,
            "id": msgId,
            "type": type,
            "ts": Int(Date().timeIntervalSince1970 * 1000),
            "token": token,
            "data": data
        ]
        if let json = try? JSONSerialization.data(withJSONObject: payload),
           let jsonStr = String(data: json, encoding: .utf8) {
            mqtt?.publish("applecase/\(deviceId)/cmd", withString: jsonStr, qos: .qos1)
        }
        return msgId
    }

    func sendSms(to: String, body: String, slot: Int) {
        let ref = publishCommand("SMS_SEND", data: ["to": to, "body": body, "simSlot": slot])
        statusText = "已发 SMS_SEND: \(ref)"
    }

    func placeCall(to: String, slot: Int) {
        let ref = publishCommand("CALL_PLACE", data: ["to": to, "simSlot": slot])
        statusText = "已发 CALL_PLACE(\(slot)号卡): \(ref)"
    }

    func callControl(_ action: String, on: Bool = true) {
        let ref = publishCommand("CALL_CONTROL", data: ["action": action, "on": on])
        statusText = "已发 CALL_CONTROL \(action): \(ref)"
    }
}

extension GatewayClient: CocoaMQTTDelegate {
    func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        if ack == .accept {
            DispatchQueue.main.async {
                self.connected = true
                self.statusText = "已连接"
            }
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

        switch type {
        case "SMS_IN":
            let sms = SmsMessage(
                id: msgData["id"] as? String ?? UUID().uuidString,
                from: msgData["from"] as? String ?? "?",
                body: msgData["body"] as? String ?? "",
                ts: msgData["ts"] as? Double ?? 0
            )
            DispatchQueue.main.async { self.messages.insert(sms, at: 0) }

        case "SMS_OUT":
            let status = msgData["status"] as? String ?? "?"
            let ref = msgData["ref"] as? String ?? ""
            DispatchQueue.main.async {
                self.statusText = "短信结果: \(status) (ref=\(ref.prefix(8)))"
            }

        case "HELLO":
            var parsed: [SimInfo] = []
            if let arr = msgData["sims"] as? [[String: Any]] {
                for s in arr {
                    parsed.append(SimInfo(
                        slot: s["slot"] as? Int ?? 0,
                        carrier: s["carrier"] as? String ?? "?",
                        state: s["state"] as? String ?? "?",
                        signal: s["signal"] as? Int ?? -1,
                        number: s["number"] as? String ?? ""
                    ))
                }
            }
            let ip = msgData["ip"] as? String ?? "?"
            DispatchQueue.main.async {
                self.sims = parsed
                self.statusText = "网关在线 IP=\(ip)"
            }

        case "CALL_INCOMING":
            let call = CallEntry(
                callId: msgData["id"] as? String ?? UUID().uuidString,
                from: msgData["from"] as? String ?? "?",
                slot: msgData["slot"] as? Int ?? 0,
                dir: "in",
                state: "ringing"
            )
            DispatchQueue.main.async { self.calls.insert(call, at: 0) }

        case "CALL_STATE":
            let callId = msgData["ref"] as? String ?? ""
            let state = msgData["state"] as? String ?? "?"
            let from = msgData["from"] as? String ?? "?"
            let slot = msgData["slot"] as? Int ?? 0
            let dir = msgData["dir"] as? String ?? "in"
            DispatchQueue.main.async {
                if let idx = self.calls.firstIndex(where: { $0.callId == callId }) {
                    self.calls[idx].state = state
                    if self.calls[idx].from == "?" { self.calls[idx].from = from }
                } else {
                    self.calls.insert(CallEntry(callId: callId, from: from, slot: slot, dir: dir, state: state), at: 0)
                }
            }

        case "SIM_STATE":
            let slot = msgData["slot"] as? Int ?? 0
            let level = msgData["signal"] as? Int ?? -1
            let carrier = msgData["carrier"] as? String ?? ""
            DispatchQueue.main.async {
                if let idx = self.sims.firstIndex(where: { $0.slot == slot }) {
                    self.sims[idx] = SimInfo(slot: slot, carrier: carrier,
                                             state: self.sims[idx].state, signal: level,
                                             number: self.sims[idx].number)
                }
                self.statusText = "卡\(slot) 信号\(level) \(carrier)"
            }

        default:
            break
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
    @State private var callTo = ""
    @State private var smsSlot = 0
    @State private var callSlot = 0

    var activeCall: CallEntry? {
        client.calls.first { $0.state == "ringing" || $0.state == "active" }
    }

    var body: some View {
        NavigationView {
            List {
                Section(header: Text(client.statusText)) {
                    Button(client.connected ? "已连接" : "连接") {
                        if !client.connected { client.connect() }
                    }
                    .buttonStyle(.bordered)
                    .tint(client.connected ? .green : .blue)

                    // 双卡状态(阶段二)
                    ForEach(client.sims, id: \.slot) { sim in
                        HStack {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                            Text("卡\(sim.slot) \(sim.carrier)")
                                .font(.subheadline)
                            Spacer()
                            Text("\(sim.state) 信号\(sim.signal)")
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                }

                // 来电/通话中(阶段二)
                if let call = activeCall {
                    Section(header: Text(call.state == "ringing" ? "📞 来电响铃" : "☎️ 通话中")) {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(call.from).font(.headline)
                                Text("卡\(call.slot) · \(call.dir == "out" ? "去电" : "来电")")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                            Spacer()
                            if call.state == "ringing" {
                                Button("接听") { client.callControl("answer") }
                                    .buttonStyle(.borderedProminent).tint(.green)
                            }
                            Button("挂断") { client.callControl("hangup") }
                                .buttonStyle(.bordered).tint(.red)
                        }
                    }
                }

                Section(header: Text("拨号测试(双卡选卡)")) {
                    TextField("号码 +86...", text: $callTo)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.phonePad)
                    Picker("用哪张卡", selection: $callSlot) {
                        Text("卡0").tag(0)
                        Text("卡1").tag(1)
                    }
                    .pickerStyle(.segmented)
                    Button("拨出") {
                        guard !callTo.isEmpty else { return }
                        client.placeCall(to: callTo, slot: callSlot)
                    }
                    .buttonStyle(.borderedProminent)
                }

                Section(header: Text("发短信测试")) {
                    TextField("收件号码 +86...", text: $sendTo)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.phonePad)
                    TextField("短信内容", text: $sendBody)
                        .textFieldStyle(.roundedBorder)
                    Picker("用哪张卡", selection: $smsSlot) {
                        Text("卡0").tag(0)
                        Text("卡1").tag(1)
                    }
                    .pickerStyle(.segmented)
                    Button("发送") {
                        guard !sendTo.isEmpty, !sendBody.isEmpty else { return }
                        client.sendSms(to: sendTo, body: sendBody, slot: smsSlot)
                    }
                    .buttonStyle(.borderedProminent)
                }

                Section(header: Text("通话记录")) {
                    ForEach(client.calls) { c in
                        HStack {
                            Image(systemName: c.dir == "out" ? "phone.arrow.up.right" : "phone.arrow.down.left")
                                .foregroundColor(colorFor(c.state))
                            VStack(alignment: .leading) {
                                Text(c.from).font(.headline)
                                Text("卡\(c.slot) · \(stateText(c.state))")
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
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

    private func stateText(_ s: String) -> String {
        switch s {
        case "ringing": return "响铃中"
        case "active": return "通话中"
        case "ended": return "已结束"
        case "missed": return "未接"
        default: return s
        }
    }

    private func colorFor(_ s: String) -> Color {
        switch s {
        case "ringing": return .blue
        case "active": return .green
        case "missed": return .red
        default: return .secondary
        }
    }
}
```

## 验证清单

部署 Q968 + 启动 broker 后,用这个客户端验证:

阶段一(短信):
- [ ] 打开 App,点"连接",状态变"已连接"或"网关在线 IP=..."
- [ ] 用另一部手机发短信到 Q968 的卡 → 这里几秒内显示该短信
- [ ] 在"发短信测试"里选卡、填号码+内容点发送 → 对应卡真实发出 → 状态显示"sent/delivered"
- [ ] 杀掉 App 重开,retained 的 status 消息还能读到网关状态

阶段二(电话/状态):
- [ ] 双卡状态条显示两张卡的运营商/信号
- [ ] 用另一部手机打电话到 Q968 → "📞 来电响铃"显示号码和卡槽 → 点"接听"真接通 → 点"挂断"挂断
- [ ] 不接等响完 → 通话记录显示"未接"
- [ ] 在"拨号测试"选卡1 → 拨出 → Q968 从卡1真实拨出 → 记录显示"去电"

全部通过 = 阶段二端到端打通。

---

## 阶段三:语音桥(AudioBridge.swift)

在阶段二工程里新增 `AudioBridge.swift`,并在通话接通时启动、挂断时停止。
实现:iOS 麦克风(voiceChat 模式,自带 AEC)→ 16k/mono/PCM16 → 20ms 帧 + 8 字节头
(见 PROTOCOL.md 第 5 节)→ UDP 发到 Q968:51820;同时收 Q968 发来的下行帧 → 播放。

### AudioBridge.swift

```swift
import Foundation
import AVFoundation
import Network

/// 实时语音桥:iPhone 麦克风 ⇄ UDP ⇄ Q968 网关(root 中继 → 通话上下行)。
/// 协议:pcm16/16kHz/mono/20ms 帧,8 字节头 [ver|dir][seq24][ts32]。
final class AudioBridge: NSObject {
    static let frameBytes = 640          // 20ms @16k*16bit
    static let frameDuration = 0.020

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var conn: NWConnection?
    private let queue = DispatchQueue(label: "audio-bridge")

    private var outQueue: [Data] = []    // 下行待播放缓冲(简单抖动缓冲,上限 10 帧)
    private var downSeqDelta: Int64 = 0

    /// 通话接通后调用。gatewayHost = Q968 的 ZeroTier IP。
    func start(gatewayHost: String) throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat,   // voiceChat 自带回声消除
                                options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        let engine = AVAudioEngine()
        self.engine = engine

        // ---- 上行:麦克风(硬件采样率)→ 转 16k/mono/Float32 供编码 ----
        let inFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000,
                                  channels: 1, interleaved: false)!
        let srcFmt = engine.inputNode.outputFormat(forBus: 0)
        let converter = AVAudioConverter(from: srcFmt, to: inFmt)!

        let capCapacity = 8192
        let capBuf = AVAudioPCMBuffer(pcmFormat: inFmt, frameCapacity: AVAudioFrameCount(capCapacity))!
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: srcFmt) { buf, _ in
            var err: NSError?
            let got = converter.convert(to: capBuf, error: &err) { _, outStatus in
                outStatus.pointee = .haveData
                return buf
            }
            guard got > 0 else { return }
            self.sendUplink(pcm: capBuf, frames: got)
        }

        // ---- 下行:播放节点(16k mono)→ 引擎自动转硬件采样率 ----
        let player = AVAudioPlayerNode()
        self.player = player
        let playFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000,
                                    channels: 1, interleaved: false)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playFmt)
        try engine.start()
        player.play()

        // ---- UDP:收下行帧 ----
        let params = NWParameters.udp
        let listener = try NWListener(using: params, on: 51821)
        listener.newConnectionHandler = { [weak self] c in
            c.start(queue: self?.queue ?? .global())
            self?.receiveLoop(c)
        }
        listener.start(queue: queue)

        // ---- UDP:发上行帧 ----
        let conn = NWConnection(host: NWEndpoint.Host(gatewayHost), port: 51820,
                                using: .udp)
        conn.start(queue: queue)
        self.conn = conn
    }

    /// 通话结束/挂断时调用。
    func stop() {
        engine?.inputNode.removeTap(onBus: 0)
        player?.stop()
        engine?.stop()
        engine = nil
        conn?.cancel()
        conn = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // ---- 上行:PCM Float32(16k) → Int16 帧 + 头 → UDP ----
    private func sendUplink(pcm: AVAudioPCMBuffer, frames: AVAudioFrameCount) {
        guard let ch = pcm.floatChannelData?[0] else { return }
        var samples = [Int16](repeating: 0, count: Int(frames))
        for i in 0..<Int(frames) {
            let v = max(-1.0, min(1.0, ch[i]))
            samples[i] = Int16(v * 32767)
        }
        var offset = 0
        while offset < samples.count {
            let n = min(Self.frameBytes / 2, samples.count - offset)
            var packet = Data(capacity: 8 + n * 2)
            packet.append(0x12)                       // ver=1 | dir=2(上行)
            packet.append(contentsOf: [0, 0, 0])      // seq(客户端暂不填序号)
            var ts = UInt32(Date().timeIntervalSince1970 * 1000).bigEndian
            packet.append(Data(bytes: &ts, count: 4))
            let chunk = samples[offset..<(offset + n)]
            chunk.withUnsafeBytes { raw in
                packet.append(contentsOf: unsafeBitCast(raw.baseAddress!, to: UnsafePointer<UInt8>.self)
                    .prefix(n * 2))
            }
            conn?.send(content: packet, completion: .contentProcessed { _ in })
            offset += n
        }
    }

    // ---- 下行:UDP → 解头 → 入播放缓冲 ----
    private var activeConn: NWConnection?
    private func receiveLoop(_ c: NWConnection) {
        activeConn = c
        receiveMore()
    }
    private func receiveMore() {
        activeConn?.receiveMessage { [weak self] data, _, _, error in
            guard let self = self else { return }
            if let data = data, data.count > 8 {
                let dir = data[data.startIndex] & 0x0F
                if dir == 1 {   // 下行帧
                    let pcm = data.dropFirst(8)
                    self.playDownlink(pcm: Array(pcm))
                }
            }
            if error == nil { self.receiveMore() }
        }
    }

    private func playDownlink(pcm: [UInt8]) {
        guard let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000,
                                      channels: 1, interleaved: false),
              let buf = AVAudioPCMBuffer(pcmFormat: fmt,
                                         frameCapacity: AVAudioFrameCount(pcm.count / 2)) else { return }
        buf.frameLength = AVAudioFrameCount(pcm.count / 2)
        let out = buf.floatChannelData![0]
        pcm.withUnsafeBytes { raw in
            let s16 = raw.bindMemory(to: Int16.self)
            for i in 0..<s16.count { out[i] = Float(s16[i]) / 32768.0 }
        }
        player?.scheduleBuffer(buf, at: nil, completionCallbackType: .dataPlayedBack) { _ in }
        player?.play()
    }
}
```

### 接线(在 ContentView.swift 的 GatewayClient 里)

```swift
// 新增
let bridge = AudioBridge()

// CALL_STATE 处理里,根据状态自动开/关音频桥:
case "CALL_STATE":
    ...
    if state == "active" {
        // 延迟 1s 等音频通路稳定,再启动桥并告知网关
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            try? self.bridge.start(gatewayHost: self.gatewayHost)   // Q968 的 ZeroTier IP
            self.publishCommand("AUDIO_CTRL", data: [
                "action": "start", "dir": "both", "codec": "pcm16",
                "params": ["ip": self.myZeroTierIP, "port": 51821]])
        }
    } else if state == "ended" || state == "missed" {
        self.publishCommand("AUDIO_CTRL", data: ["action": "stop"])
        self.bridge.stop()
    }
```

### 阶段三验证清单

- [ ] Q968 端按 README 部署(root 授权已由 deploy 脚本自动配置)
- [ ] iPhone 与 Q968 同 ZeroTier 网,iPhone 客户端拨号(CALL_PLACE)
- [ ] 接通后:Q968 侧按免提;对着 iPhone 说话 → Q968 应静音(声音走桥)→ 对方能听到
- [ ] 对方说话 → iPhone 扬声器出声
- [ ] 挂断后桥自动停(通知栏无残留),Q968 logcat 显示 `bridge stopped`
- [ ] 主观感受延迟(目标 <300ms)、回声情况(voiceChat AEC 效果)

