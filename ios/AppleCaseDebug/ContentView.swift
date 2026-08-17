import SwiftUI

struct SmsMessage: Identifiable {
    let id: String
    let from: String
    let body: String
    let ts: Double
}

struct CallEntry: Identifiable {
    let id = UUID()
    let callId: String
    var from: String
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

struct ContentView: View {
    @StateObject private var client = GatewayClient()
    @State private var sendTo = ""
    @State private var sendBody = ""
    @State private var callTo = ""
    @State private var smsSlot = 0
    @State private var callSlot = 0
    @State private var showSettings = false

    var activeCall: CallEntry? {
        client.calls.first { $0.state == "ringing" || $0.state == "active" }
    }

    var body: some View {
        NavigationView {
            List {
                Section(header: HStack {
                    Text(client.statusText)
                    Spacer()
                    if client.audioActive { Label("语音桥", systemImage: "waveform").font(.caption).foregroundColor(.green) }
                }) {
                    Button(client.connected ? "已连接" : "连接") {
                        if !client.connected { client.connect() }
                    }
                    .buttonStyle(.bordered)
                    .tint(client.connected ? .green : .blue)

                    ForEach(client.sims, id: \.slot) { sim in
                        HStack {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                            VStack(alignment: .leading) {
                                Text("卡\(sim.slot) \(sim.carrier)").font(.subheadline)
                                if !sim.number.isEmpty {
                                    Text(sim.number).font(.caption2).foregroundColor(.secondary)
                                }
                            }
                            Spacer()
                            Text("\(sim.state) 信号\(sim.signal)")
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                }

                if let call = activeCall {
                    Section(header: Text(call.state == "ringing" ? "📞 来电响铃" : "☎️ 通话中(语音桥自动开启)")) {
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

                Section(header: Text("拨号(双卡选卡;Q968 自动静音,声音走 iPhone)")) {
                    TextField("号码 +86...", text: $callTo)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.phonePad)
                    Picker("用哪张卡", selection: $callSlot) {
                        ForEach(client.sims, id: \.slot) { sim in
                            Text("卡\(sim.slot) \(sim.carrier)").tag(sim.slot)
                        }
                    }
                    .pickerStyle(.segmented)
                    Button("拨出") {
                        guard !callTo.isEmpty else { return }
                        client.placeCall(to: callTo, slot: callSlot)
                    }
                    .buttonStyle(.borderedProminent)
                }

                Section(header: Text("发短信")) {
                    TextField("收件号码 +86...", text: $sendTo)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.phonePad)
                    TextField("短信内容", text: $sendBody)
                        .textFieldStyle(.roundedBorder)
                    Picker("用哪张卡", selection: $smsSlot) {
                        ForEach(client.sims, id: \.slot) { sim in
                            Text("卡\(sim.slot) \(sim.carrier)").tag(sim.slot)
                        }
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
            .toolbar {
                Button {
                    showSettings = true
                } label: {
                    Image(systemName: "gearshape")
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
            }
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

/// 配置页(broker / deviceId / 网关 IP,存 AppStorage)。
struct SettingsView: View {
    @AppStorage("brokerHost") var brokerHost = ""
    @AppStorage("deviceId") var deviceId = ""
    @AppStorage("gatewayHost") var gatewayHost = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("MQTT Broker")) {
                    TextField("broker 主机(ZeroTier IP)", text: $brokerHost)
                        .keyboardType(.decimalPad)
                }
                Section(header: Text("网关")) {
                    TextField("deviceId", text: $deviceId)
                    TextField("Q968 的 ZeroTier IP(语音 UDP)", text: $gatewayHost)
                        .keyboardType(.decimalPad)
                }
            }
            .navigationTitle("配置")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}
