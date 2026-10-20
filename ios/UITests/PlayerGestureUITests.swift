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
}
