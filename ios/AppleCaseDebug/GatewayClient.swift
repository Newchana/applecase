import Foundation
import CryptoKit

/// 协议常量与消息封装(与 docs/PROTOCOL.md v1 对齐)。
enum Protocol_v1 {
    static let version = 1
}

/// 通用信封 {v,id,ref,type,ts,token,data}
struct Envelope: Codable {
    let v: Int
    let id: String
    var ref: String?
    let type: String
    let ts: Int
    let token: String
    let data: [String: AnyCodable]?
}

/// 消息类型(见 PROTOCOL.md 4.2)
enum MsgType {
    static let hello = "HELLO"
    static let heartbeat = "HEARTBEAT"
    static let bye = "BYE"
    static let smsSend = "SMS_SEND"
    static let smsIn = "SMS_IN"
    static let smsOut = "SMS_OUT"
    static let cmdAck = "CMD_ACK"
    static let callIncoming = "CALL_INCOMING"
    static let callState = "CALL_STATE"
    static let callPlace = "CALL_PLACE"
    static let callControl = "CALL_CONTROL"
    static let simState = "SIM_STATE"
    static let audioCtrl = "AUDIO_CTRL"
}

/// Codable 字典包装(信封 data 用)
enum AnyCodable: Codable {
    case string(String), int(Int), double(Double), bool(Bool), dict([String: AnyCodable]), array([AnyCodable]), null

    init(_ value: Any) {
        switch value {
        case let v as String: self = .string(v)
        case let v as Int: self = .int(v)
        case let v as Double: self = .double(v)
        case let v as Bool: self = .bool(v)
        case let v as [String: Any]: self = .dict(v.mapValues { AnyCodable($0) })
        case let v as [Any]: self = .array(v.map { AnyCodable($0) })
        default: self = .null
        }
    }

    var stringValue: String? { if case .string(let v) = self { return v }; return nil }
    var intValue: Int? {
        switch self {
        case .int(let v): return v
        case .double(let v): return Int(v)
        default: return nil
        }
    }
    var boolValue: Bool? { if case .bool(let v) = self { return v }; return nil }
    var dictValue: [String: AnyCodable]? { if case .dict(let v) = self { return v }; return nil }

    // 简化编码:递归产出 JSON 对象
    var jsonValue: Any {
        switch self {
        case .string(let v): return v
        case .int(let v): return v
        case .double(let v): return v
        case .bool(let v): return v
        case .dict(let v): return v.mapValues { $0.jsonValue }
        case .array(let v): return v.map { $0.jsonValue }
        case .null: return NSNull()
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        let any = jsonValue
        if let v = any as? String { try container.encode(v) }
        else if let v = any as? Int { try container.encode(v) }
        else if let v = any as? Double { try container.encode(v) }
        else if let v = any as? Bool { try container.encode(v) }
        else if let v = any as? [String: Any] {
            let data = try JSONSerialization.data(withJSONObject: v)
            try container.encode(String(data: data, encoding: .utf8) ?? "null")
        }
        else if let v = any as? [Any] {
            let data = try JSONSerialization.data(withJSONObject: v)
            try container.encode(String(data: data, encoding: .utf8) ?? "null")
        }
        else { try container.encodeNil() }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let v = try? container.decode(String.self) {
            // 协议 data 一律是对象;字符串形式不应出现,兜底处理
            if let d = v.data(using: .utf8),
               let obj = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                self = .dict(obj.mapValues { AnyCodable($0) })
            } else { self = .string(v) }
        }
        else if let v = try? container.decode(Int.self) { self = .int(v) }
        else if let v = try? container.decode(Double.self) { self = .double(v) }
        else if let v = try? container.decode(Bool.self) { self = .bool(v) }
        else if let v = try? container.decode([String: AnyCodable].self) { self = .dict(v) }
        else if let v = try? container.decode([AnyCodable].self) { self = .array(v) }
        else { self = .null }
    }
}

/// 网关客户端:MQTT 指令/事件 + 语音桥。
final class GatewayClient: ObservableObject {
    // ===== 配置(运行前改成你的实际值) =====
    @AppStorage("brokerHost") var brokerHost = "10.147.17.1"    // broker 的 ZeroTier IP
    @AppStorage("deviceId") var deviceId = "Q968-default"
    let username = "applecase"
    let password = "change-me"
    let presharedKey = "dev-secret-change-me"                    // 与 Q968 BuildConfig.PRESHARED_KEY 一致
    @AppStorage("gatewayHost") var gatewayHost = "10.147.17.5"   // Q968 的 ZeroTier IP(语音 UDP 用)
    // ======================================

    let audioUdpPort: UInt16 = 51821   // iPhone 侧收发 UDP 端口
    let gatewayAudioPort: UInt16 = 51820 // GW 侧音频端口(ack 返回,默认此值)

    @Published var messages: [SmsMessage] = []
    @Published var calls: [CallEntry] = []
    @Published var sims: [SimInfo] = []
    @Published var connected = false
    @Published var statusText = "未连接"
    @Published var audioActive = false

    private var mqtt: CocoaMQTT?
    let bridge = AudioBridge()

    // token = SHA256(presharedKey) 十六进制
    private var token: String {
        let digest = SHA256.hash(data: Data(presharedKey.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    func connect() {
        let clientId = "cl-ios-" + UUID().uuidString.prefix(8)
        let m = CocoaMQTT(clientID: String(clientId), host: brokerHost, port: 1883)
        m.username = username
        m.password = password
        m.keepAlive = 30
        m.autoReconnect = true
        m.delegate = self
        mqtt = m
        let ok = m.connect()
        statusText = ok ? "连接中..." : "连接失败"
    }

    private func publishCommand(_ type: String, data: [String: Any]) -> String {
        let msgId = "ios-" + UUID().uuidString.prefix(8)
        let payload: [String: Any] = [
            "v": 1, "id": msgId, "type": type,
            "ts": Int(Date().timeIntervalSince1970 * 1000),
            "token": token, "data": data,
        ]
        if let json = try? JSONSerialization.data(withJSONObject: payload),
           let str = String(data: json, encoding: .utf8) {
            mqtt?.publish("applecase/\(deviceId)/cmd", withString: str, qos: .qos1)
        }
        return msgId
    }

    func sendSms(to: String, body: String, slot: Int) {
        let ref = publishCommand(MsgType.smsSend, data: ["to": to, "body": body, "simSlot": slot])
        statusText = "已发 SMS_SEND: \(ref)"
    }

    func placeCall(to: String, slot: Int) {
        // 苹果皮形态:Q968 会在受理成功瞬间自动静音本机(AO Mixer 切断),回铃也不外放
        let ref = publishCommand(MsgType.callPlace, data: ["to": to, "simSlot": slot])
        statusText = "已发 CALL_PLACE(\(slot)号卡): \(ref)"
    }

    func callControl(_ action: String, on: Bool = true) {
        let ref = publishCommand(MsgType.callControl, data: ["action": action, "on": on])
        statusText = "已发 CALL_CONTROL \(action): \(ref)"
    }

    private func audioCtrl(_ action: String, dir: String = "both") {
        publishCommand(MsgType.audioCtrl, data: [
            "action": action, "dir": dir, "codec": "pcm16",
            "params": ["ip": myZeroTierIP(), "port": Int(audioUdpPort)],
        ])
    }

    /// 本机 ZeroTier 地址(简单遍历全部接口找 zt 前缀;模拟器/未组网时回退网关地址)
    private func myZeroTierIP() -> String {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        if getifaddrs(&ifaddr) == nil, let first = ifaddr {
            for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
                let name = String(cString: ptr.pointee.ifa_name)
                guard name.hasPrefix("zt") || name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
                let fam = ptr.pointee.ifa_addr.pointee.sa_family
                if fam == UInt8(AF_INET) {
                    var addr = ptr.pointee.ifa_addr.pointee
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(&addr, socklen_t(MemoryLayout<sockaddr>.size), &host, socklen_t(NI_MAXHOST), nil, 0, NI_NUMERICHOST) == 0 {
                        let ip = String(cString: host)
                        if !ip.hasPrefix("127.") { address = ip; if name.hasPrefix("zt") { break } }
                    }
                }
            }
        }
        freeifaddrs(ifaddr)
        return address ?? brokerHost
    }
}

extension GatewayClient: CocoaMQTTDelegate {
    func mqtt(_ mqtt: CocoaMQTT, didConnectAck ack: CocoaMQTTConnAck) {
        guard ack == .accept else { return }
        DispatchQueue.main.async {
            self.connected = true
            self.statusText = "已连接"
        }
        mqtt.subscribe("applecase/\(deviceId)/event/#", qos: .qos1)
        mqtt.subscribe("applecase/\(deviceId)/cmd/ack", qos: .qos1)
        mqtt.subscribe("applecase/\(deviceId)/status", qos: .qos1)
    }

    func mqtt(_ mqtt: CocoaMQTT, didReceiveMessage message: CocoaMQTTMessage, id: UInt16) {
        guard let str = message.string,
              let data = str.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        let type = obj["type"] as? String ?? ""
        let d = obj["data"] as? [String: Any] ?? [:]

        switch type {
        case MsgType.smsIn:
            let sms = SmsMessage(
                id: d["id"] as? String ?? UUID().uuidString,
                from: d["from"] as? String ?? "?",
                body: d["body"] as? String ?? "",
                ts: d["ts"] as? Double ?? 0)
            DispatchQueue.main.async { self.messages.insert(sms, at: 0) }

        case MsgType.smsOut:
            let status = d["status"] as? String ?? "?"
            let ref = d["ref"] as? String ?? ""
            DispatchQueue.main.async { self.statusText = "短信结果: \(status) (ref=\(ref.prefix(8)))" }

        case MsgType.hello:
            var parsed: [SimInfo] = []
            if let arr = d["sims"] as? [[String: Any]] {
                for s in arr {
                    parsed.append(SimInfo(
                        slot: s["slot"] as? Int ?? 0,
                        carrier: s["carrier"] as? String ?? "?",
                        state: s["state"] as? String ?? "?",
                        signal: s["signal"] as? Int ?? -1,
                        number: s["number"] as? String ?? ""))
                }
            }
            let ip = d["ip"] as? String ?? "?"
            DispatchQueue.main.async {
                self.sims = parsed
                self.statusText = "网关在线 IP=\(ip)"
            }

        case MsgType.callIncoming:
            let call = CallEntry(
                callId: d["id"] as? String ?? UUID().uuidString,
                from: d["from"] as? String ?? "?",
                slot: d["slot"] as? Int ?? 0,
                dir: "in", state: "ringing")
            DispatchQueue.main.async { self.calls.insert(call, at: 0) }

        case MsgType.callState:
            let callId = d["ref"] as? String ?? ""
            let state = d["state"] as? String ?? "?"
            let from = d["from"] as? String ?? "?"
            let slot = d["slot"] as? Int ?? 0
            let dir = d["dir"] as? String ?? "in"
            DispatchQueue.main.async {
                if let idx = self.calls.firstIndex(where: { $0.callId == callId }) {
                    self.calls[idx].state = state
                    if self.calls[idx].from == "?" || self.calls[idx].from.isEmpty { self.calls[idx].from = from }
                } else {
                    self.calls.insert(CallEntry(callId: callId, from: from, slot: slot, dir: dir, state: state), at: 0)
                }
                // 语音桥:接通即开(先启 iPhone 侧采集/播放,再通知 GW),结束即停
                if state == "active" {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        guard !self.audioActive else { return }
                        do {
                            try self.bridge.start(gatewayHost: self.gatewayHost)
                            self.audioCtrl("start", dir: "both")
                            self.audioActive = true
                        } catch {
                            self.statusText = "语音桥启动失败: \(error.localizedDescription)"
                        }
                    }
                } else if state == "ended" || state == "missed" {
                    if audioActive {
                        audioCtrl("stop")
                        bridge.stop()
                        audioActive = false
                    }
                }
            }

        case MsgType.simState:
            let slot = d["slot"] as? Int ?? 0
            let level = d["signal"] as? Int ?? -1
            let carrier = d["carrier"] as? String ?? ""
            DispatchQueue.main.async {
                if let idx = self.sims.firstIndex(where: { $0.slot == slot }) {
                    self.sims[idx] = SimInfo(slot: slot, carrier: carrier,
                                             state: self.sims[idx].state, signal: level,
                                             number: self.sims[idx].number)
                }
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
