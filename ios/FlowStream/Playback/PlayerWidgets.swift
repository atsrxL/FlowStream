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

/// A seek bar that ignores short taps. A long press starts tracking; drag to a position, release to commit.
final class HoldSlider: UIView {
    var progress: CGFloat = 0 { didSet { setNeedsLayout() } }
    var isSeekable = true
    var onBegin: (() -> Void)?
    var onChange: ((CGFloat) -> Void)?
    var onEnd: ((CGFloat, Bool) -> Void)?
    private(set) var tracking = false
    private let track = CALayer(), fill = CALayer(), thumb = CALayer()
    private lazy var press: UILongPressGestureRecognizer = {
        let r = UILongPressGestureRecognizer(target: self, action: #selector(pressed(_:)))
        r.minimumPressDuration = 0.25; r.allowableMovement = 10_000
        return r
    }()
    override init(frame: CGRect) {
        super.init(frame: frame)
        track.backgroundColor = UIColor(white: 1, alpha: 0.3).cgColor
        fill.backgroundColor = UIColor.white.cgColor
        thumb.backgroundColor = UIColor.white.cgColor
        [track, fill, thumb].forEach { layer.addSublayer($0) }
        addGestureRecognizer(press)
        isAccessibilityElement = true
        accessibilityLabel = "长按后拖动播放进度并预览缩略图"
    }
    required init?(coder: NSCoder) { fatalError() }
    override func layoutSubviews() {
        super.layoutSubviews()
        let h: CGFloat = tracking ? 6 : 3
        let pad: CGFloat = 10
        let w = bounds.width - pad * 2
        CATransaction.begin(); CATransaction.setDisableActions(true)
        track.frame = CGRect(x: pad, y: bounds.midY - h / 2, width: w, height: h); track.cornerRadius = h / 2
        let p = max(0, min(1, progress))
        fill.frame = CGRect(x: pad, y: bounds.midY - h / 2, width: w * p, height: h); fill.cornerRadius = h / 2
        let d: CGFloat = tracking ? 18 : 12
        thumb.frame = CGRect(x: pad + w * p - d / 2, y: bounds.midY - d / 2, width: d, height: d); thumb.cornerRadius = d / 2
        CATransaction.commit()
    }
    private func value(_ p: CGPoint) -> CGFloat {
        let pad: CGFloat = 10
        return max(0, min(1, (p.x - pad) / max(1, bounds.width - pad * 2)))
    }
    @objc private func pressed(_ g: UILongPressGestureRecognizer) {
        switch g.state {
        case .began:
            guard isSeekable else { g.isEnabled = false; g.isEnabled = true; return }
            tracking = true; progress = value(g.location(in: self)); onBegin?(); onChange?(progress)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .changed:
            guard tracking else { return }
            progress = value(g.location(in: self)); onChange?(progress)
        case .ended:
            guard tracking else { return }
            tracking = false; setNeedsLayout(); onEnd?(progress, true)
        case .cancelled, .failed:
            if tracking { tracking = false; setNeedsLayout(); onEnd?(progress, false) }
        default: break
        }
    }
    func cancelTracking() {
        guard tracking else { return }
        tracking = false; setNeedsLayout()
        press.isEnabled = false; press.isEnabled = true
    }
}
