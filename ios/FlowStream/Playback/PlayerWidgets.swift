import UIKit
import AVFoundation

enum OrientationLock {
    static var mask: UIInterfaceOrientationMask = .allButUpsideDown
}

/// Layer-hosting view for AVPlayer.
final class PlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    var player: AVPlayer? {
        get { playerLayer.player }
        set { playerLayer.player = newValue; playerLayer.videoGravity = .resize }
    }
}

/// Root view: while locked, only the unlock button receives touches.
final class LockableView: UIView {
    var locked = false
    weak var lockButton: UIView?
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if locked {
            if let b = lockButton, !b.isHidden, b.convert(b.bounds, to: self).contains(point) { return b }
            return self
        }
        return super.hitTest(point, with: event)
    }
}

final class PaddedLabel: UILabel {
    var insets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
    override func drawText(in rect: CGRect) { super.drawText(in: rect.inset(by: insets)) }
    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + insets.left + insets.right, height: s.height + insets.top + insets.bottom)
    }
    override func sizeThatFits(_ size: CGSize) -> CGSize {
        let s = super.sizeThatFits(CGSize(width: size.width - insets.left - insets.right, height: size.height))
        return CGSize(width: s.width + insets.left + insets.right, height: s.height + insets.top + insets.bottom)
    }
}

/// Seek bar: put a finger on it and drag; tracking starts on the first few points of movement, no hold delay.
/// A tap without movement does nothing. No thumb: white = played, grey = remaining.
final class ScrubBar: UIView {
    var progress: CGFloat = 0 { didSet { setNeedsLayout() } }
    var isSeekable = true
    var onBegin: (() -> Void)?
    var onChange: ((CGFloat) -> Void)?
    var onEnd: ((CGFloat, Bool) -> Void)?
    private(set) var tracking = false
    private let track = CALayer(), fill = CALayer()
    private weak var touch: UITouch?
    private var startX: CGFloat = 0
    private static let pad: CGFloat = 10
    private static let startDistance: CGFloat = 3

    override init(frame: CGRect) {
        super.init(frame: frame)
        track.backgroundColor = UIColor(white: 1, alpha: 0.3).cgColor
        fill.backgroundColor = UIColor.white.cgColor
        [track, fill].forEach { layer.addSublayer($0) }
        isMultipleTouchEnabled = false
        isAccessibilityElement = true
        accessibilityLabel = "播放进度，按住拖动调整"
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let h: CGFloat = tracking ? 6 : 3
        let w = bounds.width - Self.pad * 2
        let p = max(0, min(1, progress))
        CATransaction.begin(); CATransaction.setDisableActions(true)
        track.frame = CGRect(x: Self.pad, y: bounds.midY - h / 2, width: w, height: h); track.cornerRadius = h / 2
        fill.frame = CGRect(x: Self.pad, y: bounds.midY - h / 2, width: w * p, height: h); fill.cornerRadius = h / 2
        CATransaction.commit()
    }
    private func value(_ x: CGFloat) -> CGFloat {
        max(0, min(1, (x - Self.pad) / max(1, bounds.width - Self.pad * 2)))
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard touch == nil, let t = touches.first else { return }
        touch = t; startX = t.location(in: self).x
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touch, touches.contains(t) else { return }
        let x = t.location(in: self).x
        if !tracking {
            guard isSeekable, abs(x - startX) >= Self.startDistance else { return }
            tracking = true; setNeedsLayout()
            progress = value(x); onBegin?(); onChange?(progress)
            UISelectionFeedbackGenerator().selectionChanged()
            return
        }
        progress = value(x); onChange?(progress)
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touch, touches.contains(t) else { return }
        touch = nil
        if tracking { tracking = false; setNeedsLayout(); onEnd?(progress, true) }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touch, touches.contains(t) else { return }
        touch = nil
        if tracking { tracking = false; setNeedsLayout(); onEnd?(progress, false) }
    }
    func cancelTracking() {
        touch = nil
        guard tracking else { return }
        tracking = false; setNeedsLayout()
    }
}
