import Foundation
import AVFoundation
import Network

/// 实时语音桥:iPhone 麦克风 ⇄ UDP ⇄ Q968 网关(root 中继 → 通话上下行)。
/// 协议:pcm16/16kHz/mono/20ms 帧,8 字节头 [ver|dir][seq24][ts32](PROTOCOL.md 第 5 节)。
/// voiceChat 模式自带 AEC(回声消除),避免 iPhone 侧回声。
final class AudioBridge: NSObject {

    static let frameBytes = 640          // 20ms @ 16k×16bit
    static let frameDuration: TimeInterval = 0.020
    static let localUdpPort: UInt16 = 51821
    static let gatewayUdpPort: UInt16 = 51820

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var conn: NWConnection?
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "audio-bridge")
    private var upSeq: UInt32 = 0

    /// 启动双向桥。gatewayHost = Q968 的 ZeroTier IP。
    /// - 上行:麦克风 → 16k Float32 → Int16 → 20ms 帧+头 → UDP 51820
    /// - 下行:UDP 51821 收帧 → 解头 → 播放
    func start(gatewayHost: String) throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat,
                                options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        let engine = AVAudioEngine()
        self.engine = engine

        // ---- 上行:麦克风(硬件采样率)→ 转 16k/mono/Float32 ----
        let srcFmt = engine.inputNode.outputFormat(forBus: 0)
        guard srcFmt.sampleRate > 0 else {
            throw BridgeError.noMicrophone
        }
        let dstFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000,
                                   channels: 1, interleaved: false)!
        let converter = AVAudioConverter(from: srcFmt, to: dstFmt)!

        let capBuf = AVAudioPCMBuffer(pcmFormat: dstFmt, frameCapacity: 2048)!
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: srcFmt) { buf, _ in
            var err: NSError?
            let got = converter.convert(to: capBuf, error: &err) { _, outStatus in
                outStatus.pointee = .haveData
                return buf
            }
            guard got > 0 else { return }
            self.sendUplink(pcm: capBuf, frames: got)
        }

        // ---- 下行:播放节点(16k mono),引擎自动重采样到硬件 ----
        let player = AVAudioPlayerNode()
        self.player = player
        let playFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000,
                                    channels: 1, interleaved: false)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playFmt)
        try engine.start()
        player.play()

        // ---- UDP 收(GW 下行帧) ----
        let params = NWParameters.udp
        let listener = try NWListener(using: params, on: Self.localUdpPort)
        listener.newConnectionHandler = { [weak self] c in
            c.start(queue: self?.queue ?? .global())
            self?.receiveLoop(c)
        }
        listener.start(queue: queue)
        self.listener = listener

        // ---- UDP 发(上行帧 → GW) ----
        let conn = NWConnection(host: NWEndpoint.Host(gatewayHost), port: Self.gatewayUdpPort,
                                using: .udp)
        conn.start(queue: queue)
        self.conn = conn
    }

    func stop() {
        engine?.inputNode.removeTap(onBus: 0)
        player?.stop()
        engine?.stop()
        engine = nil
        player = nil
        conn?.cancel()
        conn = nil
        listener?.cancel()
        listener = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - 上行

    private func sendUplink(pcm: AVAudioPCMBuffer, frames: AVAudioFrameCount) {
        guard let ch = pcm.floatChannelData?[0] else { return }
        var samples = [Int16](repeating: 0, count: Int(frames))
        for i in 0..<Int(frames) {
            let v = max(-1.0, min(1.0, ch[i]))
            samples[i] = Int16(v * 32767.0)
        }
        var offset = 0
        while offset < samples.count {
            let n = min(Self.frameBytes / 2, samples.count - offset)
            var packet = Data(capacity: 8 + n * 2)
            let seq = upSeq
            upSeq &+= 1
            packet.append(0x12)                                    // ver=1 | dir=2(上行)
            packet.append(UInt8((seq >> 16) & 0xFF))
            packet.append(UInt8((seq >> 8) & 0xFF))
            packet.append(UInt8(seq & 0xFF))
            var ts = UInt32(Date().timeIntervalSince1970 * 1000).bigEndian
            packet.append(Data(bytes: &ts, count: 4))
            let chunk = Array(samples[offset..<(offset + n)])
            chunk.withUnsafeBytes { raw in
                packet.append(contentsOf: raw.prefix(n * 2))
            }
            conn?.send(content: packet, completion: .contentProcessed { _ in })
            offset += n
        }
    }

    // MARK: - 下行

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
                if dir == 1 {   // 下行帧(GW→iPhone,对方说话)
                    self.playDownlink(pcm: Array(data.dropFirst(8)))
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
        guard let out = buf.floatChannelData?[0] else { return }
        pcm.withUnsafeBytes { raw in
            let s16 = raw.bindMemory(to: Int16.self)
            for i in 0..<s16.count { out[i] = Float(Int16(littleEndian: s16[i])) / 32768.0 }
        }
        player?.scheduleBuffer(buf)
        if player?.isPlaying == false { player?.play() }
    }

    enum BridgeError: LocalizedError {
        case noMicrophone
        var errorDescription: String? {
            switch self {
            case .noMicrophone: return "无法访问麦克风(检查权限/模拟器)"
            }
        }
    }
}
