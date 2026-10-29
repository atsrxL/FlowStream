import UIKit


protocol GestureViewOutput: GestureMachineOutput {
    func inspectMode() -> Bool
    func gvSingleTap(x: CGFloat, y: CGFloat)
    func gvDoubleTap(x: CGFloat, y: CGFloat)
    func gvPinch(factor: CGFloat, prevX: CGFloat, prevY: CGFloat, x: CGFloat, y: CGFloat)
    func gvTransformFinished()
}

/// One owner per gesture: transport, inspection, or multi-touch. Raw touches, no UIGestureRecognizer arbitration.
final class GestureView: UIView, GestureMachineOutput {
    weak var output: GestureViewOutput?
    private lazy var machine: GestureMachine? = GestureMachine(self)
    private let taps = TapResolver(timeoutMs: 300, distance: 40)
    private var fingers = [UITouch]()
    private var active = false, blocked = false, multi = false, tapEligible = false
    private var pointer: UITouch?
    private var lastX: CGFloat = 0, lastY: CGFloat = 0
    private var tapZone = -1
    private var contactTop: CGFloat = 0
    private var interactionTop: CGFloat = 0, interactionBottom: CGFloat = .nan
    private var pinchDist: CGFloat = 0, pinchFocus = CGPoint.zero
    private var samples = [(t: TimeInterval, y: CGFloat)]()
    private var longPressWork: DispatchWorkItem?
    private var confirmWork: DispatchWorkItem?

    override init(frame: CGRect) { super.init(frame: frame); setup() }
    required init?(coder: NSCoder) { super.init(coder: coder); setup() }
    private func setup() {
        isMultipleTouchEnabled = true
        backgroundColor = .clear
        taps.onSingle = { [weak self] x, y in self?.output?.gvSingleTap(x: CGFloat(x), y: CGFloat(y)) }
        taps.onDouble = { [weak self] x, y in self?.output?.gvDoubleTap(x: CGFloat(x), y: CGFloat(y)) }
        isAccessibilityElement = true
        accessibilityLabel = "视频手势区域"
    }

    func setInteractionBounds(top: CGFloat, bottom: CGFloat) {
        guard top.isFinite, bottom.isFinite else { return }
        let t = max(0, top), b = max(t, bottom)
        if interactionTop == t && interactionBottom == b { return }
        cancelGesture(); interactionTop = t; interactionBottom = b
    }
    private var regionBottom: CGFloat { interactionBottom.isNaN ? bounds.height : min(bounds.height, interactionBottom) }
    func zoneAt(_ x: CGFloat, _ y: CGFloat) -> Int {
        GestureBindings.zoneAt(x: Float(x), y: Float(y - interactionTop), width: Float(bounds.width), height: Float(regionBottom - interactionTop))
    }
    private func ms(_ t: TimeInterval) -> Int64 { Int64(t * 1000) }
    private var nowMs: Int64 { ms(ProcessInfo.processInfo.systemUptime) }

    // MARK: touches
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            if fingers.isEmpty { blocked = false; multi = false }
            fingers.append(t)
            if fingers.count == 1 { beginSingle(t) }
            else if active && !blocked { beginMulti() }
        }
    }
    private func beginSingle(_ t: UITouch) {
        let p = t.location(in: self)
        let zone = zoneAt(p.x, p.y)
        guard zone >= 0, let machine = machine else { active = false; return }
        // A first tap in another zone is an independent single, never a double.
        if tapZone >= 0 && tapZone != zone {
            let d = taps.deadline
            if d >= 0 { taps.confirm(now: d) }
            cancelTaps()
        }
        longPressWork?.cancel(); confirmWork?.cancel(); machine.cancel()
        active = true; blocked = false; multi = false; tapEligible = false
        pointer = t; lastX = p.x; lastY = p.y; tapZone = zone; contactTop = interactionTop
        samples = [(t.timestamp, p.y)]
        let store = Store.shared
        machine.setZoneHold(zone, store.gestureAction(zone, "hold"))
        machine.setZoneDrag(zone, store.gestureAction(zone, "drag"))
        let now = ms(t.timestamp)
        machine.down(x: Float(p.x), y: Float(p.y - contactTop), width: Float(bounds.width), height: Float(regionBottom - contactTop),
                     slop: 10, now: now, zone: zone, inspect: output?.inspectMode() ?? false)
        taps.down(Float(p.x), Float(p.y), now: now)
        let w = DispatchWorkItem { [weak self] in
            guard let self = self, self.active, !self.blocked, !self.multi else { return }
            self.machine?.holdTimeout(now: self.nowMs)
        }
        longPressWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(GestureMachine.holdMs) + 5), execute: w)
    }
    private func beginMulti() {
        longPressWork?.cancel(); cancelTaps(); machine?.cancel()
        multi = true; samples.removeAll()
        resetPinchBaseline()
    }
    private func resetPinchBaseline() {
        guard fingers.count >= 2 else { return }
        let a = fingers[0].location(in: self), b = fingers[1].location(in: self)
        pinchDist = max(1, hypot(a.x - b.x, a.y - b.y))
        pinchFocus = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard active, !blocked else { return }
        if multi {
            if fingers.count >= 2 {
                let a = fingers[0].location(in: self), b = fingers[1].location(in: self)
                let d = max(1, hypot(a.x - b.x, a.y - b.y))
                let f = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
                output?.gvPinch(factor: d / pinchDist, prevX: pinchFocus.x, prevY: pinchFocus.y, x: f.x, y: f.y)
                pinchDist = d; pinchFocus = f
            } else if let p = pointer, touches.contains(p) {
                let l = p.location(in: self)
                output?.gmPan(dx: Float(l.x - lastX), dy: Float(l.y - lastY))
                lastX = l.x; lastY = l.y
            }
            return
        }
        guard let p = pointer, touches.contains(p), let machine = machine else { return }
        let l = p.location(in: self)
        samples.append((p.timestamp, l.y)); if samples.count > 8 { samples.removeFirst() }
        machine.move(Float(l.x), Float(l.y - contactTop), now: ms(p.timestamp))
        if machine.mode != .pending { cancelTaps() }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            guard let i = fingers.firstIndex(of: t) else { continue }
            if !active || blocked { fingers.remove(at: i); if fingers.isEmpty { active = false }; continue }
            if multi {
                fingers.remove(at: i)
                if fingers.count == 1 {
                    pointer = fingers[0]
                    let l = fingers[0].location(in: self); lastX = l.x; lastY = l.y
                } else if fingers.isEmpty {
                    output?.gvTransformFinished(); finishContact()
                } else { resetPinchBaseline() }
                continue
            }
            guard let machine = machine else { fingers.remove(at: i); continue }
            let l = t.location(in: self)
            longPressWork?.cancel(); tapEligible = false
            var vy: Float = 0
            if let first = samples.first(where: { t.timestamp - $0.t <= 0.1 }), t.timestamp > first.t {
                vy = Float((l.y - first.y) / CGFloat(t.timestamp - first.t))
            }
            machine.up(Float(l.x), Float(l.y - contactTop), velocityY: vy, now: ms(t.timestamp))
            // The callbacks may have cancelled this view synchronously (video switch), emptying `fingers`.
            if let k = fingers.firstIndex(of: t) { fingers.remove(at: k) }
            // A switch callback may have synchronously cancelled this view.
            if !active { continue }
            taps.up(eligible: tapEligible, Float(l.x), Float(l.y), now: ms(t.timestamp))
            output?.gvTransformFinished()
            finishContact(); scheduleTap()
        }
        if fingers.isEmpty { active = false }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelGesture() }

    // MARK: GestureMachineOutput adapter
    func gmTap() { tapEligible = true }   // only a candidate, never an immediate pause
    func gmPan(dx: Float, dy: Float) { output?.gmPan(dx: dx, dy: dy) }
    func gmScrubStarted(fine: Bool) { cancelTaps(); output?.gmScrubStarted(fine: fine) }
    func gmScrubMoved(totalDx: Float, width: Float) { output?.gmScrubMoved(totalDx: totalDx, width: width) }
    func gmScrubEnded(commit: Bool) { output?.gmScrubEnded(commit: commit) }
    func gmSwitchVideo(_ delta: Int) { output?.gmSwitchVideo(delta) }
    func gmHoldStarted() { cancelTaps(); output?.gmHoldStarted() }
    func gmForward(_ speed: Float) { output?.gmForward(speed) }
    func gmReverse(_ magnitude: Float) { output?.gmReverse(magnitude) }
    func gmHoldEnded() { output?.gmHoldEnded() }
    func gmSwipePreview(dy: Float, committed: Bool) { output?.gmSwipePreview(dy: dy, committed: committed) }

    private func scheduleTap() {
        confirmWork?.cancel()
        let d = taps.deadline
        guard d >= 0 else { return }
        let w = DispatchWorkItem { [weak self] in guard let self = self else { return }; self.taps.confirm(now: self.nowMs) }
        confirmWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(max(0, Int(d - nowMs)) + 2), execute: w)
    }
    private func cancelTaps() { confirmWork?.cancel(); taps.cancel(); tapZone = -1 }
    private func finishContact() { active = false; multi = false; pointer = nil; samples.removeAll() }

    /// Toolbar, focus loss, video switch, PiP and rotation also cancel deferred single taps.
    func cancelGesture() {
        longPressWork?.cancel(); cancelTaps(); machine?.cancel()
        if active || !fingers.isEmpty { output?.gvTransformFinished() }
        fingers.removeAll()
        finishContact()
        blocked = false
    }
    override func removeFromSuperview() { cancelGesture(); super.removeFromSuperview() }
}
