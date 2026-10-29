import XCTest

/// Simulator-only smoke test for the player gestures. Needs the WebDAV/SMB fixture described in ios/README.md.
final class PlayerGestureUITests: XCTestCase {
    let out = ProcessInfo.processInfo.environment["FS_SHOT_DIR"] ?? "/tmp/fsshots"

    func shot(_ name: String) {
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
    }

    func testGestures() throws {
        let app = XCUIApplication()
        app.launchEnvironment["FS_TEST_PROTO"] = ProcessInfo.processInfo.environment["FS_TEST_PROTO"] ?? "smb"
        app.launchEnvironment["FS_TEST_OPEN"] = "test.mp4"
        app.launch()
        sleep(9)
        shot("01-playing")
        let w = app.windows.firstMatch
        func at(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate { w.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)) }

        // The interaction region sits between ~y=0.10 and ~0.80 (portrait). Centre of it is zone 4.
        at(0.5, 0.45).tap(); sleep(1); shot("02-after-tap")
        at(0.5, 0.45).tap(); sleep(1); shot("03-after-tap2")
        at(0.5, 0.45).doubleTap(); sleep(1); shot("04-double-height")
        at(0.5, 0.45).doubleTap(); sleep(1); shot("05-double-native")
        at(0.5, 0.45).doubleTap(); sleep(1); shot("06-double-width")

        // hold bottom-centre (zone 7) and drag right: fast forward
        at(0.5, 0.70).press(forDuration: 1.2, thenDragTo: at(0.8, 0.70), withVelocity: .slow, thenHoldForDuration: 1.0)
        shot("07-after-hold-forward")
        // hold middle-centre (zone 4): reverse
        at(0.5, 0.45).press(forDuration: 1.2, thenDragTo: at(0.3, 0.45), withVelocity: .slow, thenHoldForDuration: 1.0)
        shot("08-after-hold-reverse")

        // horizontal drag = scrub (needs a quick drag in the middle zone)
        at(0.3, 0.5).press(forDuration: 0.05, thenDragTo: at(0.7, 0.5)); sleep(1); shot("09-after-scrub")

        // vertical swipes switch video
        at(0.5, 0.6).press(forDuration: 0.05, thenDragTo: at(0.5, 0.2), withVelocity: .fast, thenHoldForDuration: 0)
        usleep(400_000); shot("10-after-swipe-up")
        XCTAssertTrue(app.staticTexts["最后一条"].exists, "swipe up on the last item should flash 最后一条")
        at(0.5, 0.3).press(forDuration: 0.05, thenDragTo: at(0.5, 0.7), withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(app.staticTexts["del.mp4"].waitForExistence(timeout: 3), "swipe down should switch to the previous video")
        sleep(1); shot("11-after-swipe-down")

        // pinch to zoom
        w.pinch(withScale: 2.5, velocity: 1); sleep(1); shot("12-after-pinch")
        at(0.5, 0.45).doubleTap(); sleep(1); shot("13-double-reset")
        // long press on timeline then drag (scrub bar)
        at(0.5, 0.45).tap(); sleep(1)
        at(0.3, 0.93).press(forDuration: 0.6, thenDragTo: at(0.6, 0.93)); sleep(1); shot("14-seekbar")
    }

    func testScreens() throws {
        let app = XCUIApplication()
        app.launchEnvironment["FS_TEST_PROTO"] = ProcessInfo.processInfo.environment["FS_TEST_PROTO"] ?? "smb"
        app.launch()
        sleep(6)
        shot("20-files")
        app.navigationBars.buttons.matching(NSPredicate(format: "identifier IN {'square.grid.2x2','list.bullet'}")).firstMatch.tap(); sleep(4); shot("21-files-grid")
        app.tabBars.buttons["设置"].tap(); sleep(1); shot("22-settings")
        app.tabBars.buttons["标记"].tap(); sleep(1); shot("23-marks")
        app.terminate()

        let p = XCUIApplication()
        p.launchEnvironment["FS_TEST_PROTO"] = ProcessInfo.processInfo.environment["FS_TEST_PROTO"] ?? "smb"
        p.launchEnvironment["FS_TEST_OPEN"] = "test.mp4"
        p.launch()
        sleep(9)
        let w = p.windows.firstMatch
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap(); sleep(1)
        p.buttons["旋转"].tap(); sleep(3); shot("24-landscape")
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); sleep(1)
        p.buttons["媒体信息"].tap(); sleep(2); shot("25-info")
        p.buttons["媒体信息"].tap(); sleep(1)
        p.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "播放速度")).firstMatch.tap(); sleep(1); shot("26-speed-menu")
    }

    func testServerPathField() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["设置"].tap()
        let add = app.buttons["添加服务器"]
        for _ in 0..<6 where !add.isHittable { app.swipeUp() }
        add.tap()
        XCTAssertTrue(app.staticTexts["路径"].waitForExistence(timeout: 3), "SMB form should label the field 路径")
        XCTAssertFalse(app.textFields["共享名"].exists)
        let path = app.textFields["smb-path"]
        XCTAssertEqual(path.placeholderValue, "/share")
        shot("30-server-form")
        app.textFields["名称"].tap(); app.textFields["名称"].typeText("路径测试")
        app.textFields["主机 IP / 域名"].tap(); app.textFields["主机 IP / 域名"].typeText("127.0.0.1")
        let port = app.textFields["端口"]
        port.tap(); port.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 5) + "4445")
        path.tap(); path.typeText("/videos/sub")
        app.textFields["用户名（匿名访问可留空）"].tap(); app.textFields["用户名（匿名访问可留空）"].typeText("demo")
        app.secureTextFields["密码"].tap(); app.secureTextFields["密码"].typeText("secret")
        app.buttons["测试连接"].tap()
        // /videos/sub contains exactly one video, so the path was split into share "videos" + root "sub".
        XCTAssertTrue(app.staticTexts["连接成功，项目数：1"].waitForExistence(timeout: 10), "path /videos/sub should list the sub folder")
        shot("31-server-form-tested")
    }

    /// Runs last (alphabetical): it removes del.mp4, which testGestures needs.
    func testZDeleteFromMarks() throws {
        let fixture = ProcessInfo.processInfo.environment["FS_FIXTURE_DIR"] ?? "/tmp/fsmedia"
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture + "/del.mp4"), "fixture del.mp4 missing")
        let app = XCUIApplication()
        app.launchEnvironment["FS_TEST_PROTO"] = ProcessInfo.processInfo.environment["FS_TEST_PROTO"] ?? "smb"
        app.launchEnvironment["FS_TEST_OPEN"] = "del.mp4"
        app.launch()
        sleep(9)
        /// The HUD auto-hides; tap the centre zone (单击 = 菜单开关) until the control is really tappable.
        func control(_ label: String) -> XCUIElement {
            let b = app.buttons[label]
            for _ in 0..<4 where !b.isHittable {
                app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap(); sleep(1)
            }
            return b
        }
        // Marks persist across launches; only mark if a previous run has not already done so.
        _ = control("返回")
        if !app.buttons["取消标记"].exists { control("标记").tap(); sleep(1) }
        XCTAssertTrue(app.buttons["取消标记"].exists, "player should show the file as marked")
        control("返回").tap()
        XCTAssertTrue(app.tabBars.buttons["标记"].waitForExistence(timeout: 5))
        sleep(1)
        app.tabBars.buttons["标记"].tap()
        let row = app.staticTexts["del.mp4"]
        XCTAssertTrue(row.waitForExistence(timeout: 3), "marked file should be listed")
        row.swipeLeft()
        shot("32-marks-swipe")
        app.buttons["删除文件"].tap()
        XCTAssertTrue(app.alerts["删除这个视频？"].waitForExistence(timeout: 3))
        shot("33-marks-confirm")
        app.alerts.buttons["删除"].tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: row)
        waitForExpectations(timeout: 10)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture + "/del.mp4"), "file should be deleted on the server")
        shot("34-marks-after-delete")
    }

    /// Taps the centre zone (单击 = 菜单开关) until a player control is really tappable.
    func playerControl(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        let b = app.buttons[label]
        for _ in 0..<4 where !b.isHittable {
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap(); sleep(1)
        }
        return b
    }

    func testNewVideoWidthFitAndSpeed() throws {
        let app = XCUIApplication()
        app.launchEnvironment["FS_TEST_PROTO"] = ProcessInfo.processInfo.environment["FS_TEST_PROTO"] ?? "smb"
        app.launchEnvironment["FS_TEST_OPEN"] = "test.mp4"
        app.launch()
        sleep(9)
        // Download speed is part of the HUD: visible with the controls, hidden with them.
        _ = playerControl(app, "媒体信息")
        let speed = app.staticTexts["download-speed"]
        XCTAssertTrue(speed.exists && speed.isHittable, "speed should show with the HUD")
        XCTAssertTrue(speed.label.hasPrefix("下载 "), speed.label)
        shot("40-speed-visible")
        sleep(6)   // default HUD timeout is 3.5 s
        XCTAssertFalse(speed.isHittable, "speed should hide together with the HUD")
        shot("41-speed-hidden")

        // Switch to 高度适屏, then open another video: it must start at 宽度适屏 again.
        let centre = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        centre.doubleTap(); sleep(1)
        XCTAssertTrue(app.staticTexts["高度适屏"].exists, "double tap should switch to 高度适屏")
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))
            .press(forDuration: 0.05, thenDragTo: app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)), withVelocity: .fast, thenHoldForDuration: 0)
        XCTAssertTrue(app.staticTexts["del.mp4"].waitForExistence(timeout: 4), "swipe down should open the previous video")
        sleep(2)
        playerControl(app, "媒体信息").tap(); sleep(2)
        let info = app.descendants(matching: .any)["media-info"]
        // The panel loads track details asynchronously; give a freshly switched SMB item time.
        XCTAssertTrue(info.waitForExistence(timeout: 8), "media info panel should open")
        XCTAssertTrue(info.label.contains("画面  宽度适屏"), "new video should start at 宽度适屏: \(info.label)")
        shot("42-new-video-width")
    }

    func resetGestureDefaults(_ app: XCUIApplication) {
        let reset = app.buttons["恢复默认"].firstMatch
        for _ in 0..<4 where !reset.isHittable { app.swipeUp() }
        reset.tap(); sleep(1)
        let confirm = app.sheets.buttons["恢复默认"].firstMatch
        if confirm.exists { confirm.tap() } else { app.buttons.matching(identifier: "恢复默认").element(boundBy: 1).tap() }
        sleep(1)
        for _ in 0..<4 where !app.buttons["zone-1-tap"].isHittable { app.swipeDown() }
    }

    func testGestureDiagram() throws {
        let app = XCUIApplication()
        app.launch()
        app.tabBars.buttons["设置"].tap()
        app.buttons["九宫格手势"].tap()
        XCTAssertTrue(app.buttons["zone-4-double"].waitForExistence(timeout: 3))
        resetGestureDefaults(app)   // start from defaults even if an earlier run left changes
        let centreDouble = app.buttons["zone-4-double"]
        for z in 0..<9 { for k in ["tap", "double", "hold", "drag"] {
            XCTAssertTrue(app.buttons["zone-\(z)-\(k)"].exists, "missing control zone-\(z)-\(k)")
        } }
        // Controls sit where the zone is: zone 3 (中左) left of zone 4, zone 4 left of zone 5, top row above middle row.
        XCTAssertLessThan(app.buttons["zone-3-tap"].frame.midX, centreDouble.frame.midX)
        XCTAssertLessThan(centreDouble.frame.midX, app.buttons["zone-5-tap"].frame.midX)
        XCTAssertLessThan(app.buttons["zone-1-tap"].frame.midY, app.buttons["zone-4-tap"].frame.midY)
        XCTAssertLessThan(app.buttons["zone-4-tap"].frame.midY, app.buttons["zone-7-tap"].frame.midY)
        XCTAssertTrue(centreDouble.label.hasSuffix("切换适屏模式"), centreDouble.label)
        shot("50-gesture-diagram")
        centreDouble.tap()
        app.buttons["播放 / 暂停"].firstMatch.tap(); sleep(1)
        XCTAssertTrue(app.buttons["zone-4-double"].label.hasSuffix("播放 / 暂停"), app.buttons["zone-4-double"].label)
        shot("51-gesture-diagram-changed")
        resetGestureDefaults(app)
        XCTAssertTrue(app.buttons["zone-4-double"].label.hasSuffix("切换适屏模式"), "reset should restore defaults")
    }

    /// First line of the time label ("mm:ss\nmm:ss") in seconds.
    func playedSeconds(_ app: XCUIApplication) -> Int {
        let first = app.staticTexts["time-label"].label.split(whereSeparator: { $0 == "\n" || $0 == " " }).first.map(String.init) ?? ""
        let p = first.split(separator: ":").compactMap { Int($0) }
        return p.count == 2 ? p[0] * 60 + p[1] : -1
    }

    func testSeekBarDragsImmediately() throws {
        let app = XCUIApplication()
        app.launchEnvironment["FS_TEST_PROTO"] = ProcessInfo.processInfo.environment["FS_TEST_PROTO"] ?? "smb"
        app.launchEnvironment["FS_TEST_OPEN"] = "test.mp4"
        app.launch()
        sleep(9)
        let bar = app.windows.firstMatch
        let before = playedSeconds(app)
        XCTAssertGreaterThanOrEqual(before, 5)
        // No hold: touch down and drag straight away, towards the start of the bar.
        bar.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.935))
            .press(forDuration: 0.02, thenDragTo: bar.coordinate(withNormalizedOffset: CGVector(dx: 0.17, dy: 0.935)), withVelocity: .default, thenHoldForDuration: 0.3)
        sleep(1)
        let after = playedSeconds(app)
        XCTAssertLessThanOrEqual(after, 3, "immediate drag should move playback near the start (was \(before)s, now \(after)s)")
        shot("60-seekbar-immediate")
        // A plain tap must not jump.
        let t0 = playedSeconds(app)
        bar.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.935)).tap(); sleep(1)
        XCTAssertLessThanOrEqual(playedSeconds(app), t0 + 3, "tap without drag should not seek")
    }

    func testFineScrub() throws {
        let app = XCUIApplication()
        app.launchEnvironment["FS_TEST_PROTO"] = ProcessInfo.processInfo.environment["FS_TEST_PROTO"] ?? "smb"
        app.launch()
        app.tabBars.buttons["设置"].tap()
        app.buttons["九宫格手势"].tap()
        XCTAssertTrue(app.buttons["zone-4-drag"].waitForExistence(timeout: 3))
        resetGestureDefaults(app)
        app.buttons["zone-4-drag"].tap()
        app.buttons["精细进度调整（±10 秒）"].firstMatch.tap(); sleep(1)
        XCTAssertTrue(app.buttons["zone-4-drag"].label.hasSuffix("精细进度调整（±10 秒）"), app.buttons["zone-4-drag"].label)
        shot("70-fine-scrub-setting")

        app.tabBars.buttons["文件"].tap(); sleep(3)
        app.staticTexts["test.mp4"].firstMatch.tap(); sleep(6)
        playerControl(app, "暂停").tap(); sleep(1)
        let w = app.windows.firstMatch
        let start = playedSeconds(app)
        // Drag right by more than half the width: clamps at +10 s around the starting point.
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.45))
            .press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.45)), withVelocity: .slow, thenHoldForDuration: 0.5)
        sleep(1)
        let plus = playedSeconds(app)
        XCTAssertTrue(abs(plus - (start + 10)) <= 1, "fine scrub should move +10 s (from \(start) to \(plus))")
        // A quarter-width drag left ≈ −5 s.
        w.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.45))
            .press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45)), withVelocity: .slow, thenHoldForDuration: 0.5)
        sleep(1)
        let minus = playedSeconds(app)
        XCTAssertTrue(abs(minus - (plus - 5)) <= 1, "quarter-width drag left should move about −5 s (from \(plus) to \(minus))")
        shot("71-fine-scrub-done")

        for _ in 0..<3 where !app.tabBars.buttons["设置"].isHittable { playerControl(app, "返回").tap(); sleep(2) }
        app.tabBars.buttons["设置"].tap(); sleep(1)
        // The settings tab keeps its navigation stack, so we may already be on the gesture page.
        if !app.buttons["zone-4-drag"].exists { app.buttons["九宫格手势"].tap(); sleep(1) }
        resetGestureDefaults(app)
        XCTAssertTrue(app.buttons["zone-4-drag"].label.hasSuffix("调整播放进度"))
    }
}
