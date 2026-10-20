import UIKit
import AVFoundation
import AVKit

/// Full-screen player. Port of the Android PlayerActivity: nine-zone gestures, HUD, scrub preview, queue, marks, delete.
final class PlayerViewController: UIViewController, GestureViewOutput, AVPictureInPictureControllerDelegate {
    private let store = Store.shared
    private let session: Session
    private let server: Server

    // media
    private let player = AVPlayer()
    private var loader: MediaLoader?
    private var playbackSource: RemoteSource?
    private var preloadReader: MediaReader?
    private var preloadSource: RemoteSource?
    private var trick: TrickPlay!
    private let framePreview = FramePreview()
    private var itemObservers = [NSKeyValueObservation]()
    private var playerObservers = [NSKeyValueObservation]()
    private var endObserver: NSObjectProtocol?
    private var pip: AVPictureInPictureController?

    // views
    private let root = LockableView()
    private let videoContainer = UIView()
    private let playerView = PlayerView()
    private let gestures = GestureView()
    private let topBar = UIStackView()
    private let menuStack = UIStackView()
    private let timelineRow = UIStackView()
    private let titleLabel = UILabel()
    private let hudLabel = PaddedLabel()
    private let rateLabel = PaddedLabel()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let seekBar = HoldSlider()
    private let timeLabel = UILabel()
    private let playButton = UIButton(type: .system)
    private let markButton = UIButton(type: .system)
    private let lockButton = UIButton(type: .system)
    private let speedButton = UIButton(type: .system)
    private let infoLabel = PaddedLabel()
    private let previewImage = UIImageView()
    private let previewTime = UILabel()
    private let errorBox = UIView()
    private let errorLabel = UILabel()

    // geometry
    private let transform = VideoTransformModel()

    // state
    private var visibleControls = true
    private var scrubbing = false, gestureScrub = false, scrubResume = false
    private var scrubStart: Int64 = 0, liveScrub: Int64 = 0
    private var pendingScrubSeek: DispatchWorkItem?
    private var hideWork: DispatchWorkItem?
    private var hudWork: DispatchWorkItem?
    private var ticker: Timer?
    private var locked = false
    private var deleting = false
    private var resumePlaying = true
    private var pipActive = false
    private var lastCheckpoint = Date.distantPast
    private var previewTarget: Int64 = -1
    private var thumbnails = [Int64: UIImage]()
    private var pendingPreview: DispatchWorkItem?
    private var lastRequestedPreview: Int64 = -1
    private var lastPreviewAt = Date.distantPast
    private var previewWarmed = false
    private var randomOrder = RandomOrder()
    private var usingRandom = false
    private var rateAt = Date.distantPast, rateBytes: Int64 = 0, loadRate = 0.0
    private let netStartBytes = VideoCache.shared.totalNetworkBytes
    private let netStartTime = Date()
    private var buffering = false
    private var swipeHintActive = false

    init(session: Session, server: Server) {
        self.session = session; self.server = server
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }
    required init?(coder: NSCoder) { fatalError() }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { OrientationLock.mask }

    // MARK: - lifecycle
    override func loadView() { view = root }

    override func viewDidLoad() {
        super.viewDidLoad()
        root.backgroundColor = .black
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        buildUI()
        player.automaticallyWaitsToMinimizeStalling = true
        trick = TrickPlay(player: player) { [weak self] text in self?.persistentHint(text) }
        trick.setBaseSpeed(store.globalSpeed)
        session.speed = trick.baseSpeed
        playerView.player = player
        if AVPictureInPictureController.isPictureInPictureSupported() {
            pip = AVPictureInPictureController(playerLayer: playerView.playerLayer)
            pip?.delegate = self
        }
        playerObservers.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.updateBuffering() }
        })
        NotificationCenter.default.addObserver(self, selector: #selector(didBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(audioInterruption(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(routeChange(_:)), name: AVAudioSession.routeChangeNotification, object: nil)
        ticker = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.tick() }
        openCurrent(position: MediaPaths.resumePosition(session.position, session.duration), playing: true)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let landscape = view.window?.windowScene?.interfaceOrientation.isLandscape ?? (view.bounds.width > view.bounds.height)
        OrientationLock.mask = landscape ? .landscape : .portrait
        UIApplication.shared.isIdleTimerDisabled = true
    }
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || presentingViewController == nil { teardown() }
    }
    private func teardown() {
        UIApplication.shared.isIdleTimerDisabled = false
        OrientationLock.mask = UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
        view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: OrientationLock.mask))
        ticker?.invalidate(); ticker = nil
        hideWork?.cancel(); hudWork?.cancel(); pendingScrubSeek?.cancel(); pendingPreview?.cancel()
        trick?.dispose()
        releasePlayer()
        framePreview.reset()
        VideoCache.shared.preload(reader: nil, path: "", megabytes: 0)
        preloadSource?.close(); preloadSource = nil; preloadReader = nil
        NotificationCenter.default.removeObserver(self)
    }
    deinit { NotificationCenter.default.removeObserver(self) }

    private func releasePlayer() {
        itemObservers.removeAll()
        if let e = endObserver { NotificationCenter.default.removeObserver(e); endObserver = nil }
        player.pause()
        player.replaceCurrentItem(with: nil)
        loader?.cancelAll(); loader = nil
        playbackSource?.close(); playbackSource = nil
    }

    // MARK: - UI
    private func symbolButton(_ name: String, _ label: String, size: CGFloat = 20, action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .system)
        configure(b, name, label, size: size)
        b.addAction(UIAction { [weak self] _ in self?.gestures.cancelGesture(); action() }, for: .touchUpInside)
        return b
    }
    private func configure(_ b: UIButton, _ name: String, _ label: String, size: CGFloat = 20) {
        b.setImage(UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .regular)), for: .normal)
        b.tintColor = .white
        b.accessibilityLabel = label
    }
    private func textCircle(_ title: String, _ action: @escaping () -> Void) -> UIView {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 11, weight: .medium)
        b.tintColor = .white
        b.layer.cornerRadius = 22; b.layer.borderWidth = 1; b.layer.borderColor = UIColor(white: 0.6, alpha: 1).cgColor
        b.backgroundColor = UIColor(white: 0, alpha: 0.15)
        b.addAction(UIAction { [weak self] _ in action(); self?.showControls() }, for: .touchUpInside)
        return slot(b, size: 44)
    }
    private func slot(_ v: UIView, size: CGFloat) -> UIView {
        let s = UIView()
        v.translatesAutoresizingMaskIntoConstraints = false
        s.addSubview(v)
        NSLayoutConstraint.activate([v.centerXAnchor.constraint(equalTo: s.centerXAnchor), v.centerYAnchor.constraint(equalTo: s.centerYAnchor),
                                     v.widthAnchor.constraint(equalToConstant: size), v.heightAnchor.constraint(equalToConstant: size)])
        return s
    }
    private func row(_ views: [UIView]) -> UIStackView {
        let s = UIStackView(arrangedSubviews: views)
        s.axis = .horizontal; s.distribution = .fillEqually; s.alignment = .fill
        return s
    }

    private func buildUI() {
        videoContainer.clipsToBounds = true
        videoContainer.backgroundColor = .black
        videoContainer.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(videoContainer)
        videoContainer.addSubview(playerView)
        gestures.translatesAutoresizingMaskIntoConstraints = false
        gestures.output = self
        root.addSubview(gestures)
        NSLayoutConstraint.activate([
            videoContainer.leadingAnchor.constraint(equalTo: root.leadingAnchor), videoContainer.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            videoContainer.topAnchor.constraint(equalTo: root.topAnchor), videoContainer.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            gestures.leadingAnchor.constraint(equalTo: root.leadingAnchor), gestures.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            gestures.topAnchor.constraint(equalTo: root.topAnchor), gestures.bottomAnchor.constraint(equalTo: root.bottomAnchor),
        ])

        // HUD pill + loading
        for l in [hudLabel, rateLabel] {
            l.textColor = .white; l.textAlignment = .center; l.numberOfLines = 2
            l.backgroundColor = UIColor(white: 0, alpha: 0.65)
            l.layer.cornerRadius = 14; l.layer.masksToBounds = true
            l.translatesAutoresizingMaskIntoConstraints = false
            l.isHidden = true
        }
        hudLabel.font = .systemFont(ofSize: 17, weight: .medium)
        rateLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .regular)
        root.addSubview(rateLabel); root.addSubview(hudLabel)
        spinner.color = .white; spinner.hidesWhenStopped = true
        spinner.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(spinner)

        // top bar
        titleLabel.textColor = UIColor(white: 1, alpha: 0.85); titleLabel.font = .systemFont(ofSize: 12)
        titleLabel.numberOfLines = 2; titleLabel.textAlignment = .center
        configure(speedButton, "speedometer", "播放速度")
        speedButton.showsMenuAsPrimaryAction = true
        speedButton.addAction(UIAction { [weak self] _ in self?.gestures.cancelGesture(); self?.hideWork?.cancel() }, for: .menuActionTriggered)
        rebuildSpeedMenu()
        let pipBtn = symbolButton("pip.enter", "小窗") { [weak self] in self?.enterPip() }
        topBar.axis = .horizontal; topBar.distribution = .fill; topBar.alignment = .fill
        let close = symbolButton("chevron.down", "返回") { [weak self] in self?.back() }
        let rotate = symbolButton("rotate.right", "旋转") { [weak self] in self?.rotate() }
        for (v, w) in [(close, 1.0), (rotate, 1.0), (titleLabel, 2.0), (pipBtn, 1.0), (speedButton, 1.0)] as [(UIView, CGFloat)] {
            topBar.addArrangedSubview(v)
            if v !== close { v.widthAnchor.constraint(equalTo: close.widthAnchor, multiplier: w).isActive = true }
        }
        topBar.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(topBar)

        // menu (info/mark/more + transport)
        configure(markButton, "bookmark", "标记")
        markButton.addAction(UIAction { [weak self] _ in self?.gestures.cancelGesture(); self?.toggleMarked() }, for: .touchUpInside)
        let info = symbolButton("info.circle", "媒体信息") { [weak self] in self?.playbackInfo() }
        let more = UIButton(type: .system)
        configure(more, "ellipsis.circle", "菜单")
        more.showsMenuAsPrimaryAction = true
        more.menu = UIMenu(children: [UIAction(title: "删除", image: UIImage(systemName: "trash"), attributes: .destructive) { [weak self] _ in self?.confirmDelete() }])
        more.addAction(UIAction { [weak self] _ in self?.gestures.cancelGesture(); self?.hideWork?.cancel() }, for: .menuActionTriggered)
        let infoRow = row([info, markButton, UIView(), UIView(), more])
        configure(playButton, "pause.fill", "播放或暂停")
        playButton.layer.cornerRadius = 22; playButton.layer.borderWidth = 1; playButton.layer.borderColor = UIColor(white: 0.6, alpha: 1).cgColor
        playButton.addAction(UIAction { [weak self] _ in self?.gestures.cancelGesture(); self?.togglePlayback() }, for: .touchUpInside)
        let transport = row([textCircle("-10s") { [weak self] in self?.seekBy(-10_000) }, textCircle("-3s") { [weak self] in self?.seekBy(-3000) },
                             slot(playButton, size: 44),
                             textCircle("+3s") { [weak self] in self?.seekBy(3000) }, textCircle("+10s") { [weak self] in self?.seekBy(10_000) }])
        menuStack.axis = .vertical; menuStack.distribution = .fill
        menuStack.addArrangedSubview(infoRow); menuStack.addArrangedSubview(transport)
        infoRow.heightAnchor.constraint(equalToConstant: 48).isActive = true
        transport.heightAnchor.constraint(equalToConstant: 48).isActive = true
        menuStack.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(menuStack)

        // timeline
        configure(lockButton, "lock.open", "锁定手势")
        lockButton.addAction(UIAction { [weak self] _ in self?.toggleLock() }, for: .touchUpInside)
        root.lockButton = lockButton
        timeLabel.textColor = .white; timeLabel.font = .monospacedDigitSystemFont(ofSize: 10, weight: .regular)
        timeLabel.numberOfLines = 2; timeLabel.textAlignment = .center; timeLabel.text = "00:00\n--:--"
        timelineRow.axis = .horizontal; timelineRow.alignment = .fill; timelineRow.distribution = .fill
        timelineRow.addArrangedSubview(lockButton); timelineRow.addArrangedSubview(seekBar); timelineRow.addArrangedSubview(timeLabel)
        lockButton.widthAnchor.constraint(equalToConstant: 48).isActive = true
        timeLabel.widthAnchor.constraint(equalToConstant: 60).isActive = true
        timelineRow.heightAnchor.constraint(equalToConstant: 48).isActive = true
        timelineRow.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(timelineRow)
        seekBar.onBegin = { [weak self] in self?.seekBarBegan() }
        seekBar.onChange = { [weak self] p in self?.seekBarChanged(p) }
        seekBar.onEnd = { [weak self] p, commit in self?.seekBarEnded(p, commit) }

        // preview
        previewImage.contentMode = .scaleAspectFit; previewImage.isHidden = true
        previewImage.backgroundColor = UIColor(white: 0, alpha: 0.5)
        previewImage.layer.cornerRadius = 6; previewImage.clipsToBounds = true
        previewTime.textColor = .white; previewTime.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        previewTime.textAlignment = .center; previewTime.isHidden = true
        for v in [previewImage, previewTime] { v.translatesAutoresizingMaskIntoConstraints = false; root.addSubview(v) }

        // info + error
        infoLabel.textColor = .white; infoLabel.font = .systemFont(ofSize: 13); infoLabel.numberOfLines = 0
        infoLabel.backgroundColor = UIColor(white: 0.12, alpha: 0.95); infoLabel.layer.cornerRadius = 14; infoLabel.layer.masksToBounds = true
        infoLabel.insets = UIEdgeInsets(top: 14, left: 16, bottom: 14, right: 16)
        infoLabel.isHidden = true; infoLabel.isUserInteractionEnabled = true
        infoLabel.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(dismissInfo)))
        infoLabel.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(infoLabel)
        errorBox.backgroundColor = UIColor(red: 0.09, green: 0.14, blue: 0.18, alpha: 0.93); errorBox.layer.cornerRadius = 14
        errorBox.isHidden = true; errorBox.translatesAutoresizingMaskIntoConstraints = false
        errorLabel.textColor = .white; errorLabel.numberOfLines = 0; errorLabel.textAlignment = .center; errorLabel.font = .systemFont(ofSize: 15)
        let retry = UIButton(type: .system)
        retry.setTitle("重试", for: .normal)
        retry.addAction(UIAction { [weak self] _ in self?.retry() }, for: .touchUpInside)
        let es = UIStackView(arrangedSubviews: [errorLabel, retry]); es.axis = .vertical; es.spacing = 10
        es.translatesAutoresizingMaskIntoConstraints = false
        errorBox.addSubview(es); root.addSubview(errorBox)

        let g = root.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: g.topAnchor, constant: 4), topBar.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 8),
            topBar.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -8), topBar.heightAnchor.constraint(equalToConstant: 48),
            timelineRow.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 8), timelineRow.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -8),
            timelineRow.bottomAnchor.constraint(equalTo: g.bottomAnchor, constant: -4),
            menuStack.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 8), menuStack.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -8),
            menuStack.bottomAnchor.constraint(equalTo: timelineRow.topAnchor),
            hudLabel.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: 8), hudLabel.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            hudLabel.widthAnchor.constraint(lessThanOrEqualTo: root.widthAnchor, constant: -40),
            rateLabel.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: 8), rateLabel.centerXAnchor.constraint(equalTo: root.centerXAnchor),
            spinner.centerXAnchor.constraint(equalTo: root.centerXAnchor), spinner.centerYAnchor.constraint(equalTo: root.centerYAnchor),
            previewImage.widthAnchor.constraint(equalToConstant: 160), previewImage.heightAnchor.constraint(equalToConstant: 90),
            previewImage.centerXAnchor.constraint(equalTo: root.centerXAnchor), previewImage.bottomAnchor.constraint(equalTo: previewTime.topAnchor, constant: -4),
            previewTime.centerXAnchor.constraint(equalTo: root.centerXAnchor), previewTime.bottomAnchor.constraint(equalTo: timelineRow.topAnchor, constant: -2),
            infoLabel.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: 40),
            infoLabel.leadingAnchor.constraint(equalTo: g.leadingAnchor, constant: 16), infoLabel.trailingAnchor.constraint(equalTo: g.trailingAnchor, constant: -16),
            errorBox.centerXAnchor.constraint(equalTo: root.centerXAnchor), errorBox.centerYAnchor.constraint(equalTo: root.centerYAnchor),
            errorBox.leadingAnchor.constraint(greaterThanOrEqualTo: g.leadingAnchor, constant: 24), errorBox.trailingAnchor.constraint(lessThanOrEqualTo: g.trailingAnchor, constant: -24),
            es.topAnchor.constraint(equalTo: errorBox.topAnchor, constant: 18), es.bottomAnchor.constraint(equalTo: errorBox.bottomAnchor, constant: -14),
            es.leadingAnchor.constraint(equalTo: errorBox.leadingAnchor, constant: 18), es.trailingAnchor.constraint(equalTo: errorBox.trailingAnchor, constant: -18),
        ])
        updateControlsVisibility(animated: false)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let scale = view.window?.screen.scale ?? UIScreen.main.scale
        transform.scale = scale
        transform.setViewport(view.bounds.size)
        applyTransform()
        let safe = view.safeAreaInsets
        gestures.setInteractionBounds(top: safe.top + 64, bottom: view.bounds.height - safe.bottom - 150)
    }
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        cancelScrub(); gestures.cancelGesture()
    }

    // MARK: - transform
    private func applyTransform() {
        CATransaction.begin(); CATransaction.setDisableActions(true)
        playerView.frame = transform.frame(container: videoContainer.bounds)
        CATransaction.commit()
    }

    // MARK: - opening / items
    private var inPip: Bool { pipActive }
    private func openCurrent(position: Int64, playing: Bool) {
        clearPreviews()
        transform.setPreset(store.sourcePreset(server.id)); transform.newVideo(); applyTransform()
        syncRandom(); randomOrder.visit(session.current.path)
        errorBox.isHidden = true
        let item = session.current
        itemObservers.removeAll()
        if let e = endObserver { NotificationCenter.default.removeObserver(e); endObserver = nil }
        loader?.cancelAll()
        if loader == nil {
            do {
                let src = try Sources.make(server)
                playbackSource = src
                loader = MediaLoader(reader: MediaReader(source: src, serverId: server.id))
            } catch { showError(error.localizedDescription); return }
        }
        let asset = loader!.asset(for: item)
        let pi = AVPlayerItem(asset: asset)
        pi.audioTimePitchAlgorithm = .spectral
        pi.preferredForwardBufferDuration = 30
        itemObservers.append(pi.observe(\.status, options: [.new]) { [weak self] it, _ in DispatchQueue.main.async { self?.itemStatusChanged(it) } })
        itemObservers.append(pi.observe(\.presentationSize, options: [.new]) { [weak self] it, _ in DispatchQueue.main.async { self?.videoSizeChanged(it.presentationSize) } })
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: pi, queue: .main) { [weak self] _ in
            guard let self = self else { return }
            self.player.seek(to: .zero) { _ in self.player.play() }
        }
        player.replaceCurrentItem(with: pi)
        trick.itemChanged()
        if position > 0 { player.seek(to: CMTime(value: position, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) }
        if playing { player.play() } else { player.pause() }
        updateMarked()
        titleLabel.text = item.name
        speedButton.accessibilityLabel = "播放速度 " + SpeedScale.label(trick.baseSpeed)
        showControls()
        updateBuffering()
    }
    private func itemStatusChanged(_ item: AVPlayerItem) {
        guard item === player.currentItem else { return }
        switch item.status {
        case .failed:
            trick.doNotResumeAfterHold(); gestures.cancelGesture()
            var text = "播放失败"
            if let e = item.error { text += " · " + e.localizedDescription }
            let ext = (session.current.path as NSString).pathExtension.lowercased()
            if ["mkv", "webm", "avi", "mpg", "mpeg", "m2ts", "ts", "mpd"].contains(ext) {
                text += "\niOS 系统播放器不支持 \(ext.uppercased()) 封装，请换用 MP4 / MOV / M4V。"
            }
            showError(text); checkpoint()
        case .readyToPlay:
            updateBuffering(); preloadNext(); warmPreview()
            if let d = item.asset.duration.isNumeric ? item.duration : nil { session.duration = Int64(d.seconds * 1000) }
        default: break
        }
    }
    private func videoSizeChanged(_ size: CGSize) {
        gestures.cancelGesture()
        transform.onVideoSize(size)
        applyTransform()
    }
    private func updateBuffering() {
        let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate && player.currentItem?.status != .failed
        buffering = waiting
        if waiting && !inPip { spinner.startAnimating() } else { spinner.stopAnimating() }
    }
    private func retry() {
        errorBox.isHidden = true
        openCurrent(position: max(0, session.position), playing: true)
    }

    // MARK: - HUD
    private func updateControlsVisibility(animated: Bool) {
        let show = visibleControls && !inPip
        let apply = { [self] in
            topBar.alpha = show ? 1 : 0
            menuStack.alpha = show && previewTarget < 0 ? 1 : 0
            hudLabel.alpha = inPip ? 0 : 1
        }
        topBar.isUserInteractionEnabled = show
        menuStack.isUserInteractionEnabled = show && previewTarget < 0
        timelineRow.isHidden = inPip
        if animated { UIView.animate(withDuration: 0.18, animations: apply) } else { apply() }
    }
    private func showControls() {
        if inPip { return }
        visibleControls = true
        updateControlsVisibility(animated: true)
        scheduleHide()
    }
    private func scheduleHide() {
        hideWork?.cancel()
        guard !locked, !scrubbing, trick.playWhenReady else { return }
        let delay = store.hudTimeoutMs
        let w = DispatchWorkItem { [weak self] in
            guard let self = self, !self.scrubbing, self.infoLabel.isHidden, self.trick.playWhenReady, !self.trick.held else { return }
            self.visibleControls = false; self.updateControlsVisibility(animated: true)
        }
        hideWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(delay), execute: w)
    }
    private func toggleControls() {
        hideWork?.cancel()
        if visibleControls { infoLabel.isHidden = true; visibleControls = false; updateControlsVisibility(animated: true) } else { showControls() }
    }
    private func persistentHint(_ text: String) {
        hudWork?.cancel()
        if inPip || text.isEmpty { hudLabel.isHidden = true; return }
        hudLabel.text = text; hudLabel.isHidden = false
    }
    private func flash(_ text: String) {
        persistentHint(text)
        let w = DispatchWorkItem { [weak self] in self?.hudLabel.isHidden = true }
        hudWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6, execute: w)
    }
    private func showError(_ text: String) {
        spinner.stopAnimating()
        errorLabel.text = text
        if !inPip { errorBox.isHidden = false }
        showControls()
    }
    @objc private func dismissInfo() { infoLabel.isHidden = true; showControls() }

    private func tick() {
        let now = Date()
        let bytes = VideoCache.shared.totalNetworkBytes
        if rateAt == .distantPast { rateAt = now; rateBytes = bytes }
        else if now.timeIntervalSince(rateAt) >= 0.5 {
            loadRate = Double(max(0, bytes - rateBytes)) / now.timeIntervalSince(rateAt); rateAt = now; rateBytes = bytes
        }
        rateLabel.text = "加载 · " + Fmt.rate(loadRate)
        rateLabel.isHidden = !(buffering && !inPip && hudLabel.isHidden)
        guard player.currentItem != nil else { return }
        let duration = durationMs, position = positionMs
        if !scrubbing {
            seekBar.isSeekable = duration > 0
            seekBar.progress = duration > 0 ? CGFloat(min(1, Double(position) / Double(duration))) : 0
            timeLabel.text = Fmt.time(position) + "\n" + Fmt.time(duration)
        }
        configure(playButton, trick.playWhenReady ? "pause.fill" : "play.fill", trick.playWhenReady ? "暂停" : "播放")
        if Date().timeIntervalSince(lastCheckpoint) >= 5 { checkpoint(); lastCheckpoint = Date() }
    }
    private var durationMs: Int64 {
        guard let d = player.currentItem?.duration, d.isNumeric, d.seconds > 0 else { return 0 }
        return Int64(d.seconds * 1000)
    }
    private var positionMs: Int64 {
        let t = player.currentTime()
        return t.isNumeric ? max(0, Int64(t.seconds * 1000)) : 0
    }

    // MARK: - playback control
    private func togglePlayback() {
        if trick.playWhenReady { player.pause() } else { player.play() }
        showControls(); hudLabel.isHidden = true; checkpoint()
    }
    private func seekBy(_ delta: Int64) {
        guard durationMs > 0 else { return }
        gestures.cancelGesture()
        var target = max(0, positionMs + delta)
        target = min(durationMs - 1, target)
        player.seek(to: CMTime(value: target, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        checkpoint(); showControls()
    }
    private func rebuildSpeedMenu() {
        let current = SpeedScale.nearest(trick?.baseSpeed ?? store.globalSpeed)
        let actions = (0..<SpeedScale.count).map { i -> UIAction in
            UIAction(title: SpeedScale.label(SpeedScale.at(i)), state: i == current ? .on : .off) { [weak self] _ in
                guard let self = self else { return }
                self.trick.setBaseSpeed(SpeedScale.at(i))
                self.session.speed = self.trick.baseSpeed
                self.store.globalSpeed = self.trick.baseSpeed
                self.rebuildSpeedMenu()
                self.flash(SpeedScale.label(self.trick.baseSpeed)); self.showControls()
            }
        }
        speedButton.menu = UIMenu(title: "播放速度", children: actions.reversed())
    }
    private func toggleLock() {
        gestures.cancelGesture()
        locked.toggle(); root.locked = locked
        configure(lockButton, locked ? "lock.fill" : "lock.open", locked ? "解锁手势" : "锁定手势")
        lockButton.tintColor = locked ? UIColor(red: 0.4, green: 0.71, blue: 1, alpha: 1) : .white
        hideWork?.cancel()
        if !locked { showControls() }
    }
    private func toggleMarked() {
        let marked = store.toggleMarked(server.id, session.current)
        updateMarked(); flash(marked ? "已标记" : "已取消标记")
    }
    private func updateMarked() {
        let marked = store.isMarked(server.id, session.current.path)
        configure(markButton, marked ? "bookmark.fill" : "bookmark", marked ? "取消标记" : "标记")
        markButton.tintColor = marked ? UIColor(red: 0.4, green: 0.71, blue: 1, alpha: 1) : .white
    }
    private func back() {
        cancelScrub(); gestures.cancelGesture(); checkpoint()
        dismiss(animated: true)
    }
    private func rotate() {
        cancelScrub(); gestures.cancelGesture(); transform.resetFit(); applyTransform(); checkpoint()
        let landscape = view.bounds.width > view.bounds.height
        OrientationLock.mask = landscape ? .portrait : .landscape
        setNeedsUpdateOfSupportedInterfaceOrientations()
        view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: OrientationLock.mask)) { _ in }
        showControls()
    }
    private func checkpoint() {
        guard player.currentItem != nil else { return }
        session.position = positionMs
        session.duration = max(session.duration, durationMs)
        session.speed = trick.baseSpeed
    }

    // MARK: - queue
    private func queuePaths() -> [String] { session.queue.map { $0.path } }
    private func syncRandom() {
        let key = session.serverId + "\u{0}" + session.folder
        if let r = store.randomOrders[key] { randomOrder = r } else { randomOrder = RandomOrder(); store.randomOrders[key] = randomOrder }
        usingRandom = store.randomMode
    }
    private func preloadNext() {
        syncRandom()
        let path: String? = usingRandom ? randomOrder.peekNext(queuePaths()) : (session.index + 1 < session.queue.count ? session.queue[session.index + 1].path : nil)
        guard let p = path, let item = session.queue.first(where: { $0.path == p }) else {
            VideoCache.shared.preload(reader: nil, path: "", megabytes: 0); return
        }
        if preloadReader == nil {
            guard let src = try? Sources.make(server) else { return }
            preloadSource = src
            preloadReader = MediaReader(source: src, serverId: server.id)
        }
        preloadReader?.remember(size: item.size, for: item.path)
        VideoCache.shared.preload(reader: preloadReader, path: p, megabytes: store.preloadMb)
    }
    private func switchTo(delta: Int) {
        guard player.currentItem != nil, !scrubbing, !deleting else { return }
        syncRandom()
        var next: Int
        if usingRandom {
            let paths = queuePaths()
            let sel = delta > 0 ? randomOrder.next(paths) : randomOrder.previous(paths)
            next = sel.flatMap { paths.firstIndex(of: $0) } ?? -1
        } else { next = MediaPaths.adjacent(session.index, delta, session.queue.count) }
        if next < 0 { swipeHintActive = false; flash(usingRandom ? (delta > 0 ? "已全部看过" : "没有上一条") : (delta > 0 ? "最后一条" : "第一条")); return }
        gestures.cancelGesture(); checkpoint()
        swipeHintActive = false; hudLabel.isHidden = true
        session.index = next; session.position = 0; session.duration = 0
        openCurrent(position: 0, playing: true)
        let h = view.bounds.height
        videoContainer.layer.removeAllAnimations()
        videoContainer.transform = CGAffineTransform(translationX: 0, y: delta > 0 ? h * 0.7 : -h * 0.7)
        videoContainer.alpha = 0.4
        UIView.animate(withDuration: 0.24, delay: 0, options: .curveEaseOut) { self.videoContainer.transform = .identity; self.videoContainer.alpha = 1 }
    }

    // MARK: - delete
    private func confirmDelete() {
        guard !deleting else { return }
        gestures.cancelGesture(); hideWork?.cancel()
        let item = session.current
        let a = UIAlertController(title: "删除当前视频？", message: item.name + "\n将从服务器磁盘删除原文件，不能在本应用内撤销。", preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "取消", style: .cancel) { [weak self] _ in self?.showControls() })
        a.addAction(UIAlertAction(title: "删除", style: .destructive) { [weak self] _ in
            guard let self = self, self.session.current.path == item.path else { return }
            self.deleteCurrent()
        })
        present(a, animated: true)
    }
    private func deleteCurrent() {
        guard !deleting else { return }
        cancelScrub(); gestures.cancelGesture(); checkpoint()
        let item = session.current; let srv = server
        deleting = true
        VideoCache.shared.preload(reader: nil, path: "", megabytes: 0)
        clearPreviews(); releasePlayer(); loader = nil
        infoLabel.isHidden = true; persistentHint("正在删除…")
        Task { @MainActor in
            do {
                let src = try Sources.make(srv)
                defer { src.close() }
                try await src.delete(item.path)
                store.removeMarked(srv.id, item.path)
                let before = session.index
                session.queue.removeAll { $0.path == item.path }
                deleting = false; hudLabel.isHidden = true
                if session.queue.isEmpty { dismiss(animated: true); return }
                session.index = min(before, session.queue.count - 1); session.position = 0; session.duration = 0
                flash("已从服务器删除 " + item.name)
                openCurrent(position: 0, playing: true)
            } catch {
                deleting = false; hudLabel.isHidden = true
                openCurrent(position: max(0, session.position), playing: true)
                showError(error.localizedDescription)
            }
        }
    }

    // MARK: - info
    private func playbackInfo() {
        if !infoLabel.isHidden { dismissInfo(); return }
        guard let item = player.currentItem else { return }
        hideWork?.cancel()
        let elapsed = max(1, Date().timeIntervalSince(netStartTime))
        let bytes = max(0, VideoCache.shared.totalNetworkBytes - netStartBytes)
        let cur = session.current
        Task { @MainActor in
            var text = cur.name + "\n" + Fmt.time(durationMs) + " · " + Fmt.size(cur.size) + "\n"
            if let track = try? await item.asset.loadTracks(withMediaType: .video).first {
                let formats = (try? await track.load(.formatDescriptions)) ?? []
                let rate = (try? await track.load(.nominalFrameRate)) ?? 0
                let bitrate = (try? await track.load(.estimatedDataRate)) ?? 0
                let size = (try? await track.load(.naturalSize)) ?? .zero
                var codec = "未知"
                if let f = formats.first { codec = fourCC(CMFormatDescriptionGetMediaSubType(f)) }
                text += "编码  \(codec)\n尺寸  \(Int(size.width)) × \(Int(size.height))\n帧率  " + (rate > 0 ? String(format: "%.2f", rate) : "未知")
                text += "\n码率  " + (bitrate > 0 ? Fmt.bytes(Int64(bitrate / 8)) + "/s" : "未知")
            } else { text += "视频轨尚未就绪" }
            let buffered = item.loadedTimeRanges.first?.timeRangeValue
            let ahead = buffered.map { max(0, $0.end.seconds - player.currentTime().seconds) } ?? 0
            text += "\n播放速度  " + SpeedScale.label(trick.baseSpeed) + "\n缓冲  " + Fmt.time(Int64(ahead * 1000))
            text += "\n本次平均接收  " + Fmt.bytes(Int64(Double(bytes) / elapsed)) + "/s（含预读）\n下一条预读  \(store.preloadMb) MB\n画面  " + transform.label
            infoLabel.text = text; infoLabel.isHidden = false
        }
    }
    private func fourCC(_ v: FourCharCode) -> String {
        let b = [UInt8((v >> 24) & 255), UInt8((v >> 16) & 255), UInt8((v >> 8) & 255), UInt8(v & 255)]
        return String(bytes: b, encoding: .ascii) ?? "\(v)"
    }

    // MARK: - scrub + preview
    private func seekBarBegan() {
        gestures.cancelGesture(); scrubbing = true; hideWork?.cancel()
        scrubResume = trick.playWhenReady; player.pause()
        scrubStart = positionMs
    }
    private func seekBarChanged(_ p: CGFloat) {
        let d = durationMs
        guard d > 0 else { return }
        let t = Int64(Double(d) * Double(p))
        timeLabel.text = Fmt.time(t) + "\n" + Fmt.time(d)
        showPreview(t)
    }
    private func seekBarEnded(_ p: CGFloat, _ commit: Bool) {
        if commit, durationMs > 0 { player.seek(to: CMTime(value: Int64(Double(durationMs) * Double(p)), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) }
        else if durationMs > 0 { player.seek(to: CMTime(value: scrubStart, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) }
        gestureScrub = false
        cancelScrub(); checkpoint(); showControls()
    }
    func gmScrubStarted() {
        guard durationMs > 0 else { return }
        scrubbing = true; gestureScrub = true
        scrubStart = positionMs; liveScrub = scrubStart; scrubResume = trick.playWhenReady
        player.pause(); hideWork?.cancel()
    }
    func gmScrubMoved(totalDx: Float, width: Float) {
        guard scrubbing, gestureScrub else { return }
        let d = durationMs
        liveScrub = max(0, min(d - 1, scrubStart + Int64((Double(totalDx) / Double(max(1, width)) * Double(d)).rounded())))
        seekBar.progress = CGFloat(Double(liveScrub) / Double(d))
        timeLabel.text = Fmt.time(liveScrub) + "\n" + Fmt.time(d)
        showPreview(liveScrub)
        if pendingScrubSeek == nil {
            let w = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                self.pendingScrubSeek = nil
                if self.scrubbing && self.gestureScrub {
                    self.player.seek(to: CMTime(value: self.liveScrub, timescale: 1000), toleranceBefore: .positiveInfinity, toleranceAfter: .positiveInfinity)
                }
            }
            pendingScrubSeek = w
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1, execute: w)
        }
    }
    func gmScrubEnded(commit: Bool) {
        guard scrubbing else { return }
        if commit && gestureScrub {
            player.seek(to: CMTime(value: liveScrub, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            gestureScrub = false
        }
        cancelScrub(); checkpoint()
    }
    private func cancelScrub() {
        if scrubbing { persistentHint("") }
        pendingScrubSeek?.cancel(); pendingScrubSeek = nil
        if gestureScrub { player.seek(to: CMTime(value: scrubStart, timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero); gestureScrub = false }
        seekBar.cancelTracking()
        previewTarget = -1
        pendingPreview?.cancel(); pendingPreview = nil; framePreview.cancel()
        previewImage.isHidden = true; previewTime.isHidden = true
        updateControlsVisibility(animated: false)
        guard scrubbing else { return }
        scrubbing = false
        if scrubResume { player.play() } else { player.pause() }
        scheduleHide()
    }
    private func nearestPreview(_ t: Int64) -> (Int64, UIImage)? {
        guard let k = thumbnails.keys.min(by: { abs($0 - t) < abs($1 - t) }), let i = thumbnails[k] else { return nil }
        return (k, i)
    }
    private func renderPreview() {
        let entry = nearestPreview(previewTarget)
        if let e = entry { previewImage.image = e.1; previewImage.isHidden = false }
        previewTime.text = Fmt.time(previewTarget) + ((entry == nil || abs(entry!.0 - previewTarget) > 1000) ? " · 加载中" : "")
    }
    private func showPreview(_ target: Int64) {
        if previewTarget < 0 { previewTarget = target; updateControlsVisibility(animated: false) }
        previewTarget = target
        previewTime.isHidden = false
        persistentHint(Fmt.time(target) + " / " + Fmt.time(durationMs))
        renderPreview()
        if let n = nearestPreview(target), abs(n.0 - target) < 400 { return }
        if pendingPreview == nil {
            let w = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                self.pendingPreview = nil
                guard self.scrubbing, self.previewTarget >= 0 else { return }
                let req = max(0, min(self.durationMs - 1, Int64((Double(self.previewTarget) / 500).rounded()) * 500))
                if req == self.lastRequestedPreview && Date().timeIntervalSince(self.lastPreviewAt) < 1 { return }
                self.lastRequestedPreview = req; self.lastPreviewAt = Date()
                self.requestPreview(req)
            }
            pendingPreview = w
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: w)
        }
    }
    private func requestPreview(_ t: Int64) {
        framePreview.request(t) { [weak self] epoch, image in
            guard let self = self, epoch == self.framePreview.currentEpoch else { return }
            if let image = image {
                self.thumbnails[t] = image
                if self.thumbnails.count > 160, let first = self.thumbnails.keys.sorted().first { self.thumbnails[first] = nil }
                if self.scrubbing && self.previewTarget >= 0 { self.renderPreview() }
            } else if self.scrubbing && self.thumbnails.isEmpty {
                self.previewTime.text = Fmt.time(self.previewTarget) + " · 暂无预览"
            }
        }
    }
    private func warmPreview() {
        guard !previewWarmed else { return }
        previewWarmed = true
        framePreview.prepare(server: server, item: session.current)
        requestPreview(positionMs)
    }
    private func clearPreviews() {
        framePreview.reset(); previewWarmed = false; lastRequestedPreview = -1; lastPreviewAt = .distantPast
        pendingPreview?.cancel(); pendingPreview = nil; previewImage.image = nil; thumbnails.removeAll()
    }

    // MARK: - PiP / lifecycle
    private func enterPip() {
        guard let pip = pip, pip.isPictureInPicturePossible else { flash("设备不支持或未允许画中画"); return }
        cancelScrub(); gestures.cancelGesture(); transform.resetFit(); applyTransform(); checkpoint()
        infoLabel.isHidden = true
        pip.startPictureInPicture()
    }
    func pictureInPictureControllerWillStartPictureInPicture(_ c: AVPictureInPictureController) {
        pipActive = true; gestures.cancelGesture(); persistentHint(""); updateControlsVisibility(animated: false)
    }
    func pictureInPictureControllerDidStopPictureInPicture(_ c: AVPictureInPictureController) {
        pipActive = false; showControls()
    }
    func pictureInPictureController(_ c: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler done: @escaping (Bool) -> Void) { done(true) }
    @objc private func didBackground() {
        gestures.cancelGesture(); cancelScrub(); checkpoint()
        if !pipActive { resumePlaying = trick.playWhenReady; trick.endHold(); player.pause() }
    }
    @objc private func audioInterruption(_ n: Notification) {
        if let v = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, AVAudioSession.InterruptionType(rawValue: v) == .began {
            scrubResume = false; trick.doNotResumeAfterHold(); gestures.cancelGesture(); cancelScrub()
        }
    }
    @objc private func routeChange(_ n: Notification) {
        if let v = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt, AVAudioSession.RouteChangeReason(rawValue: v) == .oldDeviceUnavailable {
            DispatchQueue.main.async { [self] in scrubResume = false; trick.doNotResumeAfterHold(); player.pause(); gestures.cancelGesture(); cancelScrub() }
        }
    }

    // MARK: - GestureViewOutput
    func inspectMode() -> Bool { transform.isCustom }
    func gmTap() {}
    func gvSingleTap(x: CGFloat, y: CGFloat) { if !inPip && !inspectMode() { zoneAction(x, y, "tap") } }
    func gvDoubleTap(x: CGFloat, y: CGFloat) {
        if inPip { return }
        if inspectMode() { transform.resetFit(); applyTransform(); fitChanged() } else { zoneAction(x, y, "double") }
    }
    private func zoneAction(_ x: CGFloat, _ y: CGFloat, _ kind: String) {
        let zone = gestures.zoneAt(x, y)
        guard zone >= 0 else { return }
        switch store.gestureAction(zone, kind) {
        case "hud": toggleControls()
        case "play": togglePlayback()
        case "seek_back_1": seekBy(-1000); flash("−1s")
        case "seek_forward_1": seekBy(1000); flash("+1s")
        case "fit": transform.cyclePreset(); applyTransform(); fitChanged()
        default: break
        }
    }
    private func fitChanged() {
        store.setSourcePreset(server.id, transform.presetIndex)
        flash(transform.label); showControls()
    }
    func gmPan(dx: Float, dy: Float) {
        let s = transform.scale
        transform.pan(Double(dx) * Double(s), Double(dy) * Double(s)); applyTransform()
    }
    func gvPinch(factor: CGFloat, prevX: CGFloat, prevY: CGFloat, x: CGFloat, y: CGFloat) {
        guard !inPip else { return }
        let s = Double(transform.scale)
        transform.pinch(Double(factor), Double(prevX) * s, Double(prevY) * s, Double(x) * s, Double(y) * s)
        applyTransform()
    }
    func gvTransformFinished() { transform.finish(); applyTransform() }
    func gmHoldStarted() {
        trick.beginHold(); hideWork?.cancel()
        visibleControls = false; updateControlsVisibility(animated: true)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }
    func gmForward(_ speed: Float) { trick.forward(speed) }
    func gmReverse(_ magnitude: Float) { trick.reverse(magnitude) }
    func gmHoldEnded() { trick.endHold() }
    func gmSwipePreview(dy: Float, committed: Bool) {
        if dy == 0 {
            UIView.animate(withDuration: 0.16) { self.videoContainer.transform = .identity }
            if swipeHintActive && !trick.held {
                swipeHintActive = false
                hudWork?.cancel(); let w = DispatchWorkItem { [weak self] in self?.hudLabel.isHidden = true }; hudWork = w
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: w)
            }
            return
        }
        videoContainer.layer.removeAllAnimations()
        let limit = view.bounds.height * 0.35
        videoContainer.transform = CGAffineTransform(translationX: 0, y: CGFloat(max(-limit, min(limit, CGFloat(dy)))))
        swipeHintActive = true
        persistentHint(dy < 0 ? "下一条" : "上一条")
    }
    func gmSwitchVideo(_ delta: Int) { switchTo(delta: delta) }
}

/// Bridges VideoViewport (pixels) to a frame in points.
final class VideoTransformModel {
    private let viewport = VideoViewport()
    private var preset = VideoViewport.Mode.width
    var scale: CGFloat = 3
    private var lastSize = CGSize.zero

    var isCustom: Bool { viewport.mode == .custom }
    var presetIndex: Int { preset == .height ? 1 : preset == .native ? 2 : 0 }
    var label: String {
        if isCustom { return String(format: "缩放 %.2f×", viewport.relativeToFit) }
        return presetIndex == 0 ? "宽度适屏" : presetIndex == 1 ? "高度适屏" : "原始大小"
    }
    func setViewport(_ size: CGSize) {
        guard size != lastSize else { return }
        lastSize = size
        viewport.setViewport(Double(size.width * scale), Double(size.height * scale))
        viewport.preset(preset)
    }
    func onVideoSize(_ size: CGSize) {
        viewport.setVideoSize(Int(size.width.rounded()), Int(size.height.rounded()))
        if !isCustom { viewport.preset(preset) }
    }
    func newVideo() { viewport.clearVideo(); viewport.preset(preset) }
    func resetFit() { setPreset(0) }
    func setPreset(_ i: Int) { preset = i == 1 ? .height : i == 2 ? .native : .width; viewport.preset(preset) }
    func cyclePreset() { setPreset(isCustom ? 0 : (presetIndex + 1) % 3) }
    func pan(_ dx: Double, _ dy: Double) { if isCustom { viewport.pan(dx, dy) } }
    func pinch(_ f: Double, _ ox: Double, _ oy: Double, _ x: Double, _ y: Double) { viewport.pinch(factor: f, prevX: ox, prevY: oy, x: x, y: y) }
    func finish() { viewport.finishTransform() }
    func frame(container: CGRect) -> CGRect {
        guard viewport.ready else { return container }
        return CGRect(x: viewport.left / Double(scale), y: viewport.top / Double(scale), width: viewport.width / Double(scale), height: viewport.height / Double(scale))
    }
}
