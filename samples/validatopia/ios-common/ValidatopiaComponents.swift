import SwiftUI

/// "Powered by" plus the VALID8 Advisory logo, which switches to its white version in dark mode.
/// VoiceOver reads it as one phrase.
struct PoweredByValid8: View {
    var logoMaxWidth: CGFloat = 160

    var body: some View {
        VStack(spacing: 4) {
            Text("Powered by")
                .font(.caption)
                .foregroundStyle(Brand.onSurfaceVariant)
            Image("Valid8Logo")
                .resizable()
                .scaledToFit()
                .frame(maxWidth: logoMaxWidth)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Powered by VALID8 Advisory")
    }
}

/// The Validatopia brand block: "Validatopia", the app's name, then ``PoweredByValid8``.
struct ValidatopiaBrandHeader: View {
    let appName: String

    var body: some View {
        VStack(spacing: 12) {
            VStack(spacing: 2) {
                Text("Validatopia")
                    .font(.largeTitle.bold())
                    .foregroundStyle(Brand.onBackground)
                Text(appName)
                    .font(.title2)
                    .foregroundStyle(Brand.onSurfaceVariant)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            PoweredByValid8()
        }
        .frame(maxWidth: .infinity)
    }
}

/// Shown while the app initialises. There's no time limit; it goes when the app is ready.
struct ValidatopiaLaunchView: View {
    let appName: String

    var body: some View {
        VStack(spacing: 32) {
            ValidatopiaBrandHeader(appName: appName)
            ProgressView()
                .controlSize(.large)
                .accessibilityLabel("Starting")
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A section heading, exposed to VoiceOver as a heading.
struct SectionHeading: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.headline)
            .foregroundStyle(Brand.onBackground)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A spinner with a status message. VoiceOver announces the message when it appears or changes.
struct ProgressRow: View {
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text(text)
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
        .announcing(text)
    }
}

/// A full-width prominent button, at least 44 points tall however small the text.
struct PrimaryButton: View {
    let title: String
    var systemImage: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            label.frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
    }

    @ViewBuilder private var label: some View {
        if let systemImage {
            Label(title, systemImage: systemImage)
        } else {
            Text(title)
        }
    }
}

/// A full-width secondary button, at least 44 points tall.
struct SecondaryButton: View {
    let title: String
    var role: ButtonRole?
    let action: () -> Void

    var body: some View {
        Button(role: role, action: action) {
            Text(title).frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
    }
}

/// A standard screen body: scrolls so nothing clips at the largest Dynamic Type sizes, with the
/// Validatopia background and content padding.
struct ValidatopiaScreen<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                content()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
        }
        .background(Brand.background.ignoresSafeArea())
    }
}

extension View {
    /// Posts a VoiceOver announcement of `message` whenever it changes, like a polite live region.
    func announcing(_ message: String?) -> some View {
        onChange(of: message, initial: true) { _, newValue in
            if let newValue {
                AccessibilityNotification.Announcement(newValue).post()
            }
        }
    }
}

extension Error {
    /// A message for the user: the Kotlin exception's message when there is one, followed by its
    /// cause's, which often holds the actual reason ("Failed while advertising: Bluetooth is turned off").
    var userMessage: String {
        guard let throwable = kotlinException, let message = throwable.message else {
            return localizedDescription
        }
        if let cause = throwable.cause?.message, !message.contains(cause) {
            return "\(message): \(cause)"
        }
        return message
    }
}

extension KotlinByteArray {
    /// The bytes as `Data`.
    func toData() -> Data {
        toNSData() as Data
    }
}

extension Error {
    /// The Kotlin exception behind this error, if it came from Kotlin.
    var kotlinException: KotlinThrowable? {
        (self as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }
}
