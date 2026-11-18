import SwiftUI
import AVFoundation
import UIKit
import Player

// Native iOS player lab — drives :player through Player.framework in the iOS Simulator (arm64), no
// Compose. AVFoundation LocalPlayer (bundled sample.mp4). Muted by default (config volume 0) —
// unmute with the slider. Prints player state to stdout so a headless `simctl launch --console`
// run is verifiable.

@main
struct PlayerLabApp: App {
    init() {
        // iOS needs an active audio session for AVPlayer to produce audio (even muted).
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }
    var body: some Scene {
        WindowGroup { RootView() }
    }
}

struct RootView: View {
    var body: some View {
        LocalView()
    }
}

// MARK: - Local (AVFoundation)

final class PlayerModel: ObservableObject {
    let player: LocalPlayer =
        LocalPlayerFactory_appleKt.createLocalPlayer(context: LocalPlayerContext())

    @Published var status: PlaybackStatus
    // Progress fields as plain Int64 — the Kotlin `Progress` type's Swift name collides with the
    // exported `Player` facade protocol, so we read its fields off the inferred value, never spell it.
    @Published var positionMs: Int64 = 0
    @Published var durationMs: Int64 = 0
    // Muted (volume 0) by default per the test-audio rule; unmute via the slider.
    @Published var config: PlaybackConfig = AppleInteropKt.playbackConfig(volume: 0.0, speed: 1.0, audioOnly: false)
    private var timer: Timer?

    var avPlayer: AVPlayer? { AppleInteropKt.avPlayer(player) }

    init() {
        status = AppleInteropKt.currentStatus(player)
        var tick = 0
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.status = AppleInteropKt.currentStatus(self.player)
            let p = AppleInteropKt.currentProgress(self.player)
            self.positionMs = p.positionMs
            self.durationMs = p.durationMs
            tick += 1
            if tick % 8 == 0 {  // ~2s: a headless `simctl launch --console` run can see playback advance
                let vs = self.status.videoSize.map { "\($0.width)x\($0.height)" } ?? "nil"
                print("[ios-lab] LOCAL state=\(self.status.state.name) pos=\(p.positionMs) dur=\(p.durationMs) video=\(vs)")
            }
        }
    }

    func loadSample() {
        guard let url = Bundle.main.url(forResource: "sample", withExtension: "mp4") else {
            print("[ios-lab] sample.mp4 missing from bundle"); return
        }
        print("[ios-lab] LOCAL load \(url.path)")
        player.load(source: PlayableItemLocalUri(uri: url.path),
                    startPositionMs: 0, playWhenReady: true, config: config)
    }

    func setAudioOnly(_ on: Bool) {
        config = AppleInteropKt.playbackConfig(volume: config.volume, speed: config.speed, audioOnly: on)
        player.setConfig(config: config)
    }
    func setVolume(_ v: Float) {
        config = AppleInteropKt.playbackConfig(volume: v, speed: config.speed, audioOnly: config.audioOnly)
        player.setConfig(config: config)
    }
    func seek(fraction: Double) {
        if durationMs > 0 { player.seekTo(positionMs: Int64(fraction * Double(durationMs))) }
    }
}

struct LocalView: View {
    @StateObject private var model = PlayerModel()
    private var duration: Int64 { model.durationMs }
    private var position: Int64 { model.positionMs }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("LocalPlayer — AVFoundation (iOS)").font(.headline)
            Button("Load sample.mp4") { model.loadSample() }
            Text("State: \(model.status.state.name)").font(.subheadline)
            if let e = model.status.error { Text("Failed (\(e.code))").foregroundColor(.red) }

            HStack {
                Toggle("Audio-only", isOn: Binding(
                    get: { model.config.audioOnly }, set: { model.setAudioOnly($0) }))
                if let v = model.status.videoSize {
                    Text("\(v.width)×\(v.height)").foregroundColor(.secondary)
                }
            }
            if !model.config.audioOnly, let av = model.avPlayer {
                VideoLayerView(player: av).frame(height: 200).background(Color.black).cornerRadius(6)
            }
            transport(position: position, duration: duration,
                      seek: { model.seek(fraction: $0) },
                      play: { model.player.play() }, pause: { model.player.pause() },
                      back: { model.player.seekTo(positionMs: position - 10_000) },
                      fwd: { model.player.seekTo(positionMs: position + 10_000) },
                      stop: { model.player.stop() })
            HStack {
                Text("Vol")
                Slider(value: Binding(get: { Double(model.config.volume) },
                                      set: { model.setVolume(Float($0)) }), in: 0...1)
            }
            Spacer()
        }
        .padding(16)
        .onAppear { model.loadSample() }
    }
}

// MARK: - Shared bits

@ViewBuilder
func transport(position: Int64, duration: Int64,
               seek: @escaping (Double) -> Void,
               play: @escaping () -> Void, pause: @escaping () -> Void,
               back: @escaping () -> Void, fwd: @escaping () -> Void, stop: @escaping () -> Void) -> some View {
    VStack(spacing: 8) {
        HStack {
            Text(fmtMs(position)).monospacedDigit()
            Slider(value: Binding(get: { duration > 0 ? Double(position) / Double(duration) : 0 }, set: { seek($0) }), in: 0...1)
            Text(duration > 0 ? fmtMs(duration) : "--:--").monospacedDigit()
        }
        HStack(spacing: 6) {
            Button("Play", action: play); Button("Pause", action: pause)
            Button("−10s", action: back); Button("+10s", action: fwd); Button("Stop", action: stop)
        }.buttonStyle(.bordered)
    }
}

func fmtMs(_ ms: Int64) -> String {
    let total = Int(ms / 1000)
    return String(format: "%d:%02d", total / 60, total % 60)
}

/// A `UIView` hosting an `AVPlayerLayer`, bound to the backend's `AVPlayer`.
struct VideoLayerView: UIViewRepresentable {
    let player: AVPlayer
    func makeUIView(context: Context) -> UIView { PlayerUIView(player: player) }
    func updateUIView(_ uiView: UIView, context: Context) { (uiView as? PlayerUIView)?.playerLayer.player = player }
}

final class PlayerUIView: UIView {
    let playerLayer = AVPlayerLayer()
    init(player: AVPlayer) {
        super.init(frame: .zero)
        backgroundColor = .black
        playerLayer.videoGravity = .resizeAspect
        playerLayer.player = player
        layer.addSublayer(playerLayer)
    }
    required init?(coder: NSCoder) { fatalError("not used") }
    override func layoutSubviews() { super.layoutSubviews(); playerLayer.frame = bounds }
}
