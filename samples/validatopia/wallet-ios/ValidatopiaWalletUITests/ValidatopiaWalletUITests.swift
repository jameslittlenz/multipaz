import XCTest

/// Drives Validatopia Wallet through the persona path against a real issuer, with an accessibility
/// audit (`performAccessibilityAudit`, WCAG 2.2 AA basics: labels, contrast, hit regions, Dynamic
/// Type clipping) on each screen.
///
/// The issuer is the app's configured default, or `VALIDATOPIA_ISSUER_URL` if set (pass it to
/// xcodebuild as `TEST_RUNNER_VALIDATOPIA_ISSUER_URL`). Test identity `p1` (Claudia Hill) must exist.
final class ValidatopiaWalletUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["-consent_accepted", "NO"]
        if let issuerUrl = ProcessInfo.processInfo.environment["VALIDATOPIA_ISSUER_URL"] {
            app.launchArguments += ["-issuer_url", issuerUrl]
        }
    }

    func testPersonaPhotoIdAndItsScreens() throws {
        app.launch()

        // Welcome and consent.
        XCTAssertTrue(app.staticTexts["Test identities only"].waitForExistence(timeout: 30))
        try auditAndCapture()
        tapScrollingIfNeeded(app.buttons["Agree and continue"])

        // Home, then the test identity list.
        let getFirst = app.buttons["Get a Photo ID"]
        let getAnother = app.buttons["Get another Photo ID"]
        XCTAssertTrue(getFirst.waitForExistence(timeout: 10) || getAnother.exists)
        try auditAndCapture()
        tapScrollingIfNeeded(getFirst.exists ? getFirst : getAnother)

        let claudia = app.buttons["Claudia Hill"]
        XCTAssertTrue(claudia.waitForExistence(timeout: 30), "The issuer's test identities didn't load")
        try auditAndCapture()
        claudia.tap()

        // Issuance ends on the new Photo ID's presenting screen: no identifying card art, only the caption.
        let showCode = app.buttons["Show code"]
        XCTAssertTrue(showCode.waitForExistence(timeout: 60), "The Photo ID wasn't issued")
        XCTAssertTrue(app.staticTexts["Claudia's Photo ID"].exists)
        try auditAndCapture()

        // The viewing screen keeps its warning while the details scroll.
        tapScrollingIfNeeded(app.buttons["View my details"])
        let warning = app.staticTexts["Do not share this screen. It isn't verified, and the information on it can't be relied on."].firstMatch
        XCTAssertTrue(warning.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["CLAUDIA"].exists || app.staticTexts["Claudia"].exists)
        try auditAndCapture()
        // Audited before scrolling: text passing under the banner is faded by the scroll edge
        // effect, which the contrast audit would count against it.
        let warningFrame = warning.frame
        app.swipeUp()
        XCTAssertEqual(warning.frame, warningFrame, "The warning should stay pinned while the details scroll")

        app.buttons["View portrait"].firstMatch.tap()
        XCTAssertTrue(app.images["Your Photo ID portrait"].waitForExistence(timeout: 10))
        try auditAndCapture()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // Presenting: a QR code when Bluetooth is available (devices), else a clear failure (the Simulator has none).
        tapScrollingIfNeeded(showCode)
        let qr = app.images["QR code for sharing your Photo ID. Show it to the verifier."]
        let failed = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Sharing didn't complete")).firstMatch
        let outcome = NSPredicate { _, _ in qr.exists || failed.exists }
        wait(for: [XCTNSPredicateExpectation(predicate: outcome, object: nil)], timeout: 30)
        try auditAndCapture()
        XCTAssertTrue(qr.exists || failed.exists, "Neither a QR code nor an error was shown")
        let bluetooth = qr.exists ? "QR code shown" : "failed: \(failed.label)"
        XCTContext.runActivity(named: "Presentment on this device: \(bluetooth)") { _ in }
    }

    func testLargestAccessibilityTextSize() throws {
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Test identities only"].waitForExistence(timeout: 30))
        try app.performAccessibilityAudit(for: [.dynamicType, .textClipped])
    }

    private func tapScrollingIfNeeded(_ element: XCUIElement) {
        var attempts = 0
        while !element.isHittable && attempts < 10 {
            app.swipeUp()
            attempts += 1
        }
        element.tap()
    }

    private var screenshotCount = 0

    /// Audits the current screen. Keeps a screenshot of it too: in the result bundle, and in
    /// `VALIDATOPIA_SCREENSHOT_DIR` when set (pass `TEST_RUNNER_VALIDATOPIA_SCREENSHOT_DIR`).
    private func auditAndCapture() throws {
        let screenshot = app.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["VALIDATOPIA_SCREENSHOT_DIR"] {
            screenshotCount += 1
            let name = "\(name.filter { $0.isLetter })-\(screenshotCount).png"
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name))
        }
        try app.performAccessibilityAudit()
    }
}
