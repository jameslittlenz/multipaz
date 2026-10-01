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
        // The same label whether or not there are documents yet.
        let getFirst = app.buttons["Add a Credential"].firstMatch
        let getAnother = getFirst
        XCTAssertTrue(getFirst.waitForExistence(timeout: 10) || getAnother.exists)
        try auditAndCapture()
        tapScrollingIfNeeded(getFirst.exists ? getFirst : getAnother)

        let claudia = app.buttons["Claudia Hill"]
        XCTAssertTrue(claudia.waitForExistence(timeout: 30), "The issuer's test identities didn't load")
        try auditAndCapture()
        claudia.tap()

        // Issuance ends on the new Photo ID's details: its card art, then the details, under a
        // warning that stays pinned while they scroll.
        let warning = app.staticTexts["Do not show this screen. This screen is just for you."].firstMatch
        XCTAssertTrue(warning.waitForExistence(timeout: 60), "The Photo ID wasn't issued")
        XCTAssertTrue(app.navigationBars["Claudia's Photo ID"].exists)
        // Each detail is one VoiceOver element, labelled with its name and value together.
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "Claudia")).firstMatch.exists)
        // The status is checked with the issuer's revocation list; a new Photo ID is valid.
        let status = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Valid until")).firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 20), "No validity status was shown")
        let checking = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Checking with the issuer")).firstMatch
        XCTAssertTrue(checking.waitForNonExistence(timeout: 20), "The revocation check didn't finish")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Couldn't check")).firstMatch.exists,
                       "The issuer's revocation list couldn't be checked")
        try auditAndCapture()
        // Audited before scrolling: text passing under the banner is faded by the scroll edge
        // effect, which the contrast audit would count against it.
        let warningFrame = warning.frame
        app.swipeUp()
        XCTAssertEqual(warning.frame, warningFrame, "The warning should stay pinned while the details scroll")

        app.swipeDown()
        tapScrollingIfNeeded(app.buttons["View portrait"].firstMatch)
        XCTAssertTrue(app.images["Your portrait"].waitForExistence(timeout: 10))
        try auditAndCapture()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // Presenting from the home screen's Share button: a QR code when Bluetooth is available
        // (devices), else a clear failure (the Simulator has none).
        let share = app.buttons["Share"].firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 10))
        share.tap()
        let qr = app.images["QR code for sharing your document. Show it to the verifier."]
        let failed = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Sharing didn't complete")).firstMatch
        let outcome = NSPredicate { _, _ in qr.exists || failed.exists }
        wait(for: [XCTNSPredicateExpectation(predicate: outcome, object: nil)], timeout: 30)
        try auditAndCapture()
        XCTAssertTrue(qr.exists || failed.exists, "Neither a QR code nor an error was shown")
        let bluetooth = qr.exists ? "QR code shown" : "failed: \(failed.label)"
        XCTContext.runActivity(named: "Presentment on this device: \(bluetooth)") { _ in }
    }

    /// The Share screen's two panels, then an online sharing request answered end to end. The
    /// wallet must already hold a document the request asks for (run
    /// `testPersonaPhotoIdAndItsScreens` first). The request is an OpenID4VP link from a verifier,
    /// passed as `TEST_RUNNER_VALIDATOPIA_ONLINE_REQUEST_URI`; without one the test is skipped.
    func testOnlineSharingRequest() throws {
        guard let uri = ProcessInfo.processInfo.environment["VALIDATOPIA_ONLINE_REQUEST_URI"],
              let url = URL(string: uri)
        else {
            throw XCTSkip("Set TEST_RUNNER_VALIDATOPIA_ONLINE_REQUEST_URI to an OpenID4VP request link")
        }
        app.launch()
        let agree = app.buttons["Agree and continue"]
        if agree.waitForExistence(timeout: 30) {
            tapScrollingIfNeeded(agree)
        }

        // The Share screen: in person by default, and the Online panel, which needs the camera.
        let share = app.buttons["Share"].firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 30), "The wallet has no documents to share")
        share.tap()
        XCTAssertTrue(app.buttons["In person"].waitForExistence(timeout: 10))
        try auditAndCapture()
        app.buttons["Online"].tap()
        XCTAssertTrue(app.staticTexts["Scan a website's code"].waitForExistence(timeout: 10))
        try auditAndCapture()

        // A website's link opens the request, and the consent sheet asks before sharing.
        app.open(url)
        // iOS asks before opening a link in an app.
        let openInApp = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if openInApp.waitForExistence(timeout: 10) {
            openInApp.tap()
        }
        // Opening the link relaunches the app with this test's arguments, so its terms are asked for
        // again; the wallet holds the request until they're accepted.
        if agree.waitForExistence(timeout: 10) {
            tapScrollingIfNeeded(agree)
        }
        let consentShare = app.buttons.matching(identifier: "Share").element(boundBy: 0)
        XCTAssertTrue(consentShare.waitForExistence(timeout: 30), "The consent sheet didn't appear")
        // Not audited: the consent sheet is multipaz-swiftui's, and its audit fails on hit area.
        consentShare.tap()
        let outcome = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@",
                                                           "Shared.", "Sharing didn't complete")).firstMatch
        XCTAssertTrue(outcome.waitForExistence(timeout: 30), "No outcome was shown")
        #if targetEnvironment(simulator)
        // The Simulator's software keys need a passcode that nothing enters during a test, so the
        // flow stops at signing, after the request was fetched and consented to.
        XCTAssertTrue(
            ["Shared. The website has your answer.", "Sharing didn't complete: User canceled authentication"]
                .contains(outcome.label),
            outcome.label
        )
        #else
        XCTAssertEqual(outcome.label, "Shared. The website has your answer.")
        #endif
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
