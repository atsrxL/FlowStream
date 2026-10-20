import AVFoundation
import UIKit

/// Main-thread only. Reverse mode is muted sampled seeking, not negative-rate decoding.
final class TrickPlay {
    private let player: AVPlayer
    private let hint: (String) -> Void
    private let clock = ReverseClock()
    private(set) var held = false
    private(set) var reversing = false
    private var restorePlaying = false
    private(set) var baseSpeed: Float = 1
    private var requestedSpeed: Float = 1
    private var lastSeek: Int64 = -1
    private var seeking = false
    private var timer: Timer?

    init(player: AVPlayer, hint: @escaping (String) -> Void) { self.player = player; self.hint = hint }

    var playWhenReady: Bool { player.rate != 0 || player.timeControlStatus == .waitingToPlayAtSpecifiedRate }
    private var nowMs: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    func setBaseSpeed(_ s: Float) { endHold(); baseSpeed = SpeedScale.normalize(s); applySpeed(baseSpeed) }
    func beginHold() { if held { return }; held = true; restorePlaying = playWhenReady }
    func doNotResumeAfterHold() { restorePlaying = false }

    func forward(_ speed: Float) {
        guard held else { return }
        stopReverse(); applySpeed(speed); player.play()
        hint("快进 " + SpeedScale.rateLabel(speed))
    }
    func reverse(_ magnitude: Float) {
        guard held else { return }
        guard player.currentItem != nil, let d = player.currentItem?.duration, d.isNumeric, d.seconds > 0 else { hint("无法快退"); return }
        if !reversing {
            reversing = true
            player.pause(); player.isMuted = true
            clock.start(positionMs: Int64(player.currentTime().seconds * 1000), now: nowMs, speed: magnitude)
            lastSeek = -1; seeking = false
            timer?.invalidate()
            let t = Timer(timeInterval: 0.12, repeats: true) { [weak self] _ in self?.tick() }
            RunLoop.main.add(t, forMode: .common); timer = t
        } else { clock.setMagnitude(magnitude) }
        hint("快退 " + SpeedScale.rateLabel(magnitude))
    }
    private func tick() {
        guard reversing else { return }
        let target = clock.target(now: nowMs)
        if !seeking && target != lastSeek {
            lastSeek = target; seeking = true
            player.seek(to: CMTime(value: target, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in self?.seeking = false }
        }
        if target == 0 { hint("已到开头") }
    }
    func endHold() {
        guard held else { return }
        held = false; stopReverse(); applySpeed(baseSpeed)
        if restorePlaying { player.play() } else { player.pause() }
        hint("")
    }
    private func stopReverse() {
        guard reversing else { return }
        reversing = false; timer?.invalidate(); timer = nil
        player.isMuted = requestedSpeed < 0.1
    }
    private func applySpeed(_ v: Float) {
        requestedSpeed = max(0.05, min(5, v))
        player.isMuted = reversing || requestedSpeed < 0.1   // 0.05× is intentionally silent
        player.defaultRate = requestedSpeed
        if player.rate != 0 { player.rate = requestedSpeed }
    }
    /// Re-assert the requested rate after the item changes.
    func itemChanged() {
        reversing = false; timer?.invalidate(); timer = nil
        player.defaultRate = requestedSpeed
        player.isMuted = requestedSpeed < 0.1
    }
    func dispose() { endHold(); stopReverse(); timer?.invalidate(); timer = nil }
}
