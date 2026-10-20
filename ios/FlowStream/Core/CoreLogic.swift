import Foundation

struct FlowError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Logical relative paths ("a/b/c.mp4"); never platform absolute paths.
enum MediaPaths {
    static func normalize(_ value: String) throws -> String {
        if value.isEmpty || value == "/" { return "" }
        var s = value.replacingOccurrences(of: "\\", with: "/")
        while s.hasPrefix("/") { s.removeFirst() }
        while s.hasSuffix("/") { s.removeLast() }
        for part in s.split(separator: "/", omittingEmptySubsequences: false) {
            if part == ".." || part == "." || part.isEmpty { throw FlowError("路径不能含有 .、.. 或空层级") }
            if part.unicodeScalars.contains(where: { $0.value < 0x20 || ($0.value >= 0x7f && $0.value < 0xa0) }) {
                throw FlowError("路径含控制字符")
            }
        }
        return s
    }
    static func join(_ parent: String, _ name: String) throws -> String {
        if name.isEmpty || name.contains("/") || name.contains("\\") { throw FlowError("非法文件名") }
        let p = try normalize(parent)
        return try normalize(p.isEmpty ? name : p + "/" + name)
    }
    static func parent(_ path: String) -> String {
        guard let i = path.lastIndex(of: "/") else { return "" }
        return String(path[..<i])
    }
    static func name(_ path: String) -> String {
        guard let i = path.lastIndex(of: "/") else { return path }
        return String(path[path.index(after: i)...])
    }
    private static let videoExt: Set<String> = ["mp4", "m4v", "mkv", "webm", "mov", "ts", "m2ts", "avi", "mpg", "mpeg", "3gp", "m3u8", "mpd"]
    static func isVideo(_ path: String) -> Bool {
        videoExt.contains((path as NSString).pathExtension.lowercased())
    }
    static func resumePosition(_ position: Int64, _ duration: Int64) -> Int64 {
        duration > 0 && position >= duration - 1500 ? 0 : max(0, position)
    }
    static func adjacent(_ index: Int, _ delta: Int, _ size: Int) -> Int {
        guard delta == -1 || delta == 1, size >= 1, index >= 0, index < size else { return -1 }
        let n = index + delta
        return n >= 0 && n < size ? n : -1
    }
}

enum GestureBindings {
    static let count = 9
    static func zoneAt(x: Float, y: Float, width: Float, height: Float) -> Int {
        guard x.isFinite, y.isFinite, width.isFinite, height.isFinite, width > 0, height > 0,
              x >= 0, x < width, y >= 0, y < height else { return -1 }
        let row = min(2, Int(3 * y / height))
        let col = x < width * 0.2 ? 0 : (x < width * 0.8 ? 1 : 2)
        return row * 3 + col
    }
    static func actions(_ kind: String) -> [String] {
        switch kind {
        case "tap", "double": return ["none", "hud", "play", "fit", "seek_back_1", "seek_forward_1"]
        case "hold": return ["none", "reverse", "forward"]
        default: return ["none", "scrub"]
        }
    }
    static func defaultAction(_ zone: Int, _ kind: String) -> String {
        let row = zone / 3, col = zone % 3
        switch kind {
        case "tap": return row == 0 ? "none" : "hud"
        case "double": return row == 0 ? "none" : col == 0 ? "seek_back_1" : col == 2 ? "seek_forward_1" : row == 1 ? "fit" : "play"
        case "hold": return row == 0 ? "none" : row == 1 ? "reverse" : "forward"
        default: return row == 0 ? "none" : "scrub"
        }
    }
    static func normalize(_ zone: Int, _ kind: String, _ action: String) -> String {
        actions(kind).contains(action) ? action : defaultAction(zone, kind)
    }
    static func label(_ action: String) -> String {
        switch action {
        case "hud": return "显示 / 隐藏菜单"
        case "play": return "播放 / 暂停"
        case "fit": return "切换适屏模式"
        case "reverse": return "快退调速"
        case "forward": return "快进调速"
        case "scrub": return "调整播放进度"
        case "seek_back_1": return "后退 1 秒"
        case "seek_forward_1": return "前进 1 秒"
        default: return "无操作"
        }
    }
    static let zoneNames = ["上左", "上中", "上右", "中左", "中央", "中右", "下左", "下中", "下右"]
    static let kinds: [(String, String)] = [("tap", "单击"), ("double", "双击"), ("hold", "长按"), ("drag", "左右拖动")]
}

enum SpeedScale {
    private static let values: [Int] = [5, 10, 15, 25, 50, 75, 90, 100, 110, 115, 125, 135, 150, 175, 200, 250]
    static var count: Int { values.count }
    static func at(_ i: Int) -> Float { Float(values[i]) / 100 }
    static func nearest(_ speed: Float) -> Int {
        guard speed.isFinite else { return 7 }
        var best = 0
        for i in 1..<values.count where abs(at(i) - speed) < abs(at(best) - speed) { best = i }
        return best
    }
    static func normalize(_ speed: Float) -> Float { at(nearest(speed)) }
    static func label(_ v: Float) -> String { String(format: "%.2f×", v) }
    static func rateLabel(_ v: Float) -> String {
        let r = (v * 100).rounded() / 100
        return (r == r.rounded() ? String(format: "%.0f", r) : String(format: "%g", r)) + "×"
    }
    private static func progress(_ distance: Float, _ span: Float, _ dead: Float) -> Float {
        let d = min(dead, span * 0.35)
        return max(0, min(1, (distance - d) / max(0.01, span - d)))
    }
    static func forwardHold(_ dx: Float, _ range: Float) -> Float {
        let t = progress(abs(dx), max(0.01, range), 4)
        return ((dx < 0 ? 2 - 1.9 * t : 2 + 3 * t) * 20).rounded() / 20
    }
    static func reverseHold(_ dx: Float, _ range: Float) -> Float {
        let t = progress(abs(dx), max(0.01, range), 4)
        return ((dx < 0 ? 1 + 4 * t : 1 - 0.9 * t) * 20).rounded() / 20
    }
}

/// Reverse target follows wall time, not the result of an asynchronous/rounded seek.
final class ReverseClock {
    private var position: Double = 0
    private var lastTime: Int64 = 0
    private var magnitude: Float = 1
    func start(positionMs: Int64, now: Int64, speed: Float) {
        position = Double(max(0, positionMs)); lastTime = now; setMagnitude(speed)
    }
    func setMagnitude(_ speed: Float) { magnitude = max(0.1, min(5, speed)) }
    func target(now: Int64) -> Int64 {
        let elapsed = max(0, min(500, now - lastTime))
        position = max(0, position - Double(elapsed) * Double(magnitude))
        lastTime = now
        return Int64(position.rounded())
    }
}

/// Process-local folder traversal. Visited items never re-enter the random candidate pool.
final class RandomOrder {
    private var seen = Set<String>()
    private var history = [String]()
    private var cursor = -1
    private var reserved: String?
    func visit(_ path: String) {
        if cursor >= 0, history[cursor] == path { seen.insert(path); return }
        while history.count > cursor + 1 { history.removeLast() }
        history.append(path); cursor = history.count - 1; seen.insert(path); reserved = nil
    }
    func peekNext(_ available: [String]) -> String? {
        if let r = reserved, available.contains(r), !seen.contains(r) { return r }
        let c = available.filter { !seen.contains($0) }
        reserved = c.randomElement()
        return reserved
    }
    func next(_ available: [String]) -> String? {
        let p = peekNext(available)
        if let p = p { visit(p) }
        return p
    }
    func previous(_ available: [String]) -> String? {
        var i = cursor - 1
        while i >= 0 {
            if available.contains(history[i]) { cursor = i; reserved = nil; return history[i] }
            i -= 1
        }
        return nil
    }
}

/// Video geometry in rendering pixels.
final class VideoViewport {
    enum Mode { case fit, width, height, native, custom }
    private(set) var viewportWidth = 0.0, viewportHeight = 0.0
    private var sourceWidth = 0.0, sourceHeight = 0.0, pixelRatio = 1.0
    private(set) var scale = 1.0, left = 0.0, top = 0.0
    private(set) var mode = Mode.fit

    var ready: Bool { viewportWidth > 0 && viewportHeight > 0 && sourceWidth > 0 && sourceHeight > 0 }
    var width: Double { sourceWidth * pixelRatio * scale }
    var height: Double { sourceHeight * scale }
    var fitScale: Double { ready ? min(viewportWidth / (sourceWidth * pixelRatio), viewportHeight / sourceHeight) : 1 }
    var minScale: Double { min(1, fitScale) }
    var maxScale: Double {
        max(max(1, fitScale * 8), ready ? max(viewportWidth / (sourceWidth * pixelRatio), viewportHeight / sourceHeight) : 1)
    }
    var relativeToFit: Double { scale / fitScale }

    func setViewport(_ w: Double, _ h: Double) {
        guard w > 0, h > 0, w.isFinite, h.isFinite else { return }
        if w == viewportWidth && h == viewportHeight { return }
        viewportWidth = w; viewportHeight = h
        resetFit()
    }
    func setVideoSize(_ w: Int, _ h: Int, ratio: Double = 1) {
        let r = ratio > 0 && ratio.isFinite ? ratio : 1
        if w <= 0 || h <= 0 { clearVideo(); return }
        if sourceWidth == Double(w) && sourceHeight == Double(h) && pixelRatio == r { return }
        sourceWidth = Double(w); sourceHeight = Double(h); pixelRatio = r
        resetFit()
    }
    func clearVideo() { sourceWidth = 0; sourceHeight = 0; mode = .fit; scale = 1; left = 0; top = 0 }
    func resetFit() {
        mode = .fit; scale = fitScale
        left = (viewportWidth - width) / 2; top = (viewportHeight - height) / 2
        clamp()
    }
    func preset(_ selected: Mode) {
        mode = selected
        scale = !ready ? 1 : selected == .native ? 1 : selected == .width ? viewportWidth / (sourceWidth * pixelRatio) : viewportHeight / sourceHeight
        left = (viewportWidth - width) / 2; top = (viewportHeight - height) / 2
        clamp()
    }
    func pan(_ dx: Double, _ dy: Double) {
        guard ready, mode != .fit, dx.isFinite, dy.isFinite else { return }
        left += dx; top += dy; clamp()
    }
    func pinch(factor: Double, prevX: Double, prevY: Double, x: Double, y: Double) {
        guard ready, factor > 0, factor.isFinite, prevX.isFinite, prevY.isFinite, x.isFinite, y.isFinite else { return }
        let target = max(minScale, min(maxScale, scale * factor))
        let ratio = target / scale
        left = x - (prevX - left) * ratio
        top = y - (prevY - top) * ratio
        scale = target
        mode = .custom; clamp()
    }
    func finishTransform() { if ready { clamp() } }
    private func clamp() {
        guard ready else { return }
        left = Self.clampAxis(left, width, viewportWidth)
        top = Self.clampAxis(top, height, viewportHeight)
        if mode == .native && abs(pixelRatio - 1) < 1e-6 {
            left = Self.aligned(left, width, viewportWidth)
            top = Self.aligned(top, height, viewportHeight)
        }
    }
    private static func clampAxis(_ offset: Double, _ content: Double, _ viewport: Double) -> Double {
        content <= viewport ? (viewport - content) / 2 : max(viewport - content, min(0, offset))
    }
    private static func aligned(_ offset: Double, _ content: Double, _ viewport: Double) -> Double {
        content <= viewport ? ((viewport - content) / 2).rounded() : max((viewport - content).rounded(.up), min(0, offset.rounded()))
    }
}

/// Delays single taps until the double-tap window closes; a double tap commits on a valid second UP.
final class TapResolver {
    var onSingle: ((Float, Float) -> Void)?
    var onDouble: ((Float, Float) -> Void)?
    private let timeout: Int64, distance: Float
    private var pending = false, down = false, candidate = false
    private var firstUp: Int64 = 0, downAt: Int64 = 0
    private var firstX: Float = 0, firstY: Float = 0, downX: Float = 0, downY: Float = 0
    init(timeoutMs: Int64, distance: Float) { timeout = timeoutMs; self.distance = distance }
    func down(_ x: Float, _ y: Float, now: Int64) {
        candidate = pending && now >= firstUp && now - firstUp <= timeout && hypotf(x - firstX, y - firstY) <= distance
        if pending && !candidate { pending = false; onSingle?(firstX, firstY) }
        down = true; downAt = now; downX = x; downY = y
    }
    func up(eligible: Bool, _ x: Float, _ y: Float, now: Int64) {
        guard down else { return }
        down = false
        if !eligible || now < downAt || now - downAt >= GestureMachine.holdMs { cancel(); return }
        if candidate { cancel(); onDouble?(downX, downY); return }
        pending = true; candidate = false; firstUp = now; firstX = x; firstY = y
    }
    func confirm(now: Int64) {
        if pending && !down && now - firstUp >= timeout { pending = false; onSingle?(firstX, firstY) }
    }
    /// -1 means no timer needed.
    var deadline: Int64 { pending && !down ? firstUp + timeout : -1 }
    func cancel() { pending = false; down = false; candidate = false }
}

protocol GestureMachineOutput: AnyObject {
    func gmTap()
    func gmPan(dx: Float, dy: Float)
    func gmScrubStarted()
    func gmScrubMoved(totalDx: Float, width: Float)
    func gmScrubEnded(commit: Bool)
    func gmSwitchVideo(_ delta: Int)
    func gmHoldStarted()
    func gmForward(_ speed: Float)
    func gmReverse(_ magnitude: Float)
    func gmHoldEnded()
    func gmSwipePreview(dy: Float, committed: Bool)
}

/// Platform-free one-finger gesture arbitration. Coordinates are points, time is monotonic ms.
final class GestureMachine {
    enum Mode { case idle, pending, vertical, pan, scrub, holdTop, holdBottom, rejected }
    static let holdMs: Int64 = 380
    static let cooldownMs: Int64 = 450
    static let axisRatio: Float = 1.6
    private weak var out: GestureMachineOutput?
    private(set) var mode = Mode.idle
    private var x0: Float = 0, y0: Float = 0, x: Float = 0, y: Float = 0, width: Float = 1, height: Float = 1
    private var slop: Float = 12, maxTravel: Float = 0
    private var zone = 0
    private var zoneHold = (0..<9).map { GestureBindings.defaultAction($0, "hold") }
    private var zoneDrag = (0..<9).map { GestureBindings.defaultAction($0, "drag") }
    var holdRange: Float = 160 / 2.54
    private var downAt: Int64 = 0
    private var lastSwitch: Int64 = Int64.min / 2
    private var holdActive = false, inspectMode = false

    init(_ out: GestureMachineOutput) { self.out = out }
    func setZoneHold(_ zone: Int, _ a: String) { zoneHold[zone] = GestureBindings.normalize(zone, "hold", a) }
    func setZoneDrag(_ zone: Int, _ a: String) { zoneDrag[zone] = GestureBindings.normalize(zone, "drag", a) }

    func down(x: Float, y: Float, width: Float, height: Float, slop: Float, now: Int64, zone: Int, inspect: Bool) {
        cancel(); inspectMode = inspect
        x0 = x; self.x = x; y0 = y; self.y = y
        self.width = max(1, width); self.height = max(1, height)
        self.slop = max(12, slop); maxTravel = 0; downAt = now
        self.zone = zone
        mode = zone < 0 ? .rejected : .pending
    }
    func holdTimeout(now: Int64) {
        guard mode == .pending, now - downAt >= Self.holdMs, maxTravel <= slop else { return }
        if inspectMode || zoneHold[zone] == "none" { return }
        let reverse = zoneHold[zone] == "reverse"
        mode = reverse ? .holdTop : .holdBottom
        holdActive = true
        out?.gmHoldStarted()
        if reverse { out?.gmReverse(1) } else { out?.gmForward(2) }
    }
    func move(_ nx: Float, _ ny: Float, now: Int64) {
        let px = x, py = y
        x = nx; y = ny
        let dx = x - x0, dy = y - y0
        maxTravel = max(maxTravel, hypotf(dx, dy))
        switch mode {
        case .pan: out?.gmPan(dx: x - px, dy: y - py); return
        case .scrub: out?.gmScrubMoved(totalDx: dx, width: width); return
        case .holdTop: out?.gmReverse(SpeedScale.reverseHold(dx, holdRange)); return
        case .holdBottom: out?.gmForward(SpeedScale.forwardHold(dx, holdRange)); return
        default: break
        }
        if mode == .pending {
            if maxTravel > slop {
                if inspectMode { mode = .pan; out?.gmPan(dx: dx, dy: dy); return }
                if abs(dy) >= slop && abs(dy) >= Self.axisRatio * abs(dx) {
                    mode = .vertical
                } else if abs(dx) >= slop && abs(dx) >= Self.axisRatio * abs(dy) {
                    if zoneDrag[zone] == "scrub" {
                        mode = .scrub
                        out?.gmScrubStarted()
                        out?.gmScrubMoved(totalDx: dx, width: width)
                    } else { mode = .rejected }
                } else if maxTravel > slop * 2.5 {
                    mode = .rejected
                }
            } else { holdTimeout(now: now) }
        }
        if mode == .vertical { out?.gmSwipePreview(dy: dy, committed: abs(dy) >= distanceThreshold) }
    }
    func up(_ nx: Float, _ ny: Float, velocityY: Float, now: Int64) {
        if mode == .pending || mode == .pan || mode == .scrub { move(nx, ny, now: now) }
        let dx = nx - x0, dy = ny - y0
        if mode == .scrub { out?.gmScrubEnded(commit: true) }
        else if holdActive { endHold() }
        else if mode == .vertical {
            let axis = abs(dy) >= Self.axisRatio * abs(dx)
            let far = abs(dy) >= distanceThreshold
            let fling = abs(dy) >= 64 && abs(velocityY) >= 1000 && (velocityY < 0) == (dy < 0)
            if axis && (far || fling) && now - lastSwitch >= Self.cooldownMs {
                lastSwitch = now
                out?.gmSwitchVideo(dy < 0 ? 1 : -1)
            }
        } else if mode == .pending && maxTravel <= slop && now - downAt < Self.holdMs {
            out?.gmTap()
        }
        out?.gmSwipePreview(dy: 0, committed: false)
        mode = .idle
    }
    var distanceThreshold: Float { max(96, height * 0.14) }
    func cancel() {
        if mode == .scrub { out?.gmScrubEnded(commit: false) }
        endHold()
        if mode != .idle { out?.gmSwipePreview(dy: 0, committed: false) }
        mode = .idle
    }
    private func endHold() { if holdActive { holdActive = false; out?.gmHoldEnded() } }
}
