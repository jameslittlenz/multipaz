import XCTest

/// Accessibility audits (`performAccessibilityAudit`, WCAG 2.2 AA basics) of Validatopia Verify's
/// screens up to engagement. The result screen needs a wallet over Bluetooth, which the Simulator
/// doesn't have; it's exercised on devices.
final class ValidatopiaVerifyUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    func testHomeTrustAndEveryUseCase() throws {
        app.launch()
        XCTAssertTrue(app.staticTexts["What are you checking?"].waitForExistence(timeout: 30))
        try auditAndCapture()

        app.buttons["Trusted issuers"].tap()
        XCTAssertTrue(app.staticTexts["Photo ID issuer (IACA)"].waitForExistence(timeout: 10))
        try auditAndCapture()
        app.navigationBars.buttons.element(boundBy: 0).tap()

        for title in ["Venue entry", "Liquor store", "Parcel pickup", "Hotel check-in / KYC", "Cross-border travel"] {
            let useCase = app.buttons.containing(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
            var attempts = 0
            while !useCase.isHittable && attempts < 10 {
                app.swipeUp()
                attempts += 1
            }
            useCase.tap()
            XCTAssertTrue(app.buttons["Scan their QR code"].waitForExistence(timeout: 10), title)
            try auditAndCapture()
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
    }

    func testLargestAccessibilityTextSize() throws {
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.staticTexts["What are you checking?"].waitForExistence(timeout: 30))
        try app.performAccessibilityAudit(for: [.dynamicType, .textClipped])
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
