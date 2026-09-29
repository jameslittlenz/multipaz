import SwiftUI
import UIKit

/// Validatopia brand colours for SwiftUI.
///
/// The values come from the shared Kotlin `ValidatopiaColors` tokens, the single source of truth
/// for both platforms, and follow the system appearance: the light scheme is navy on white, the
/// dark scheme white and green on navy. Every pair used here is contrast-checked by
/// `ValidatopiaColorsTest` (WCAG 2.2 AA).
enum Brand {
    static let primary = color(\.primary)
    static let onPrimary = color(\.onPrimary)
    static let background = color(\.background)
    static let onBackground = color(\.onBackground)
    static let surfaceContainer = color(\.surfaceContainer)
    static let surfaceVariant = color(\.surfaceVariant)
    static let onSurfaceVariant = color(\.onSurfaceVariant)
    static let secondaryContainer = color(\.secondaryContainer)
    static let onSecondaryContainer = color(\.onSecondaryContainer)
    static let error = color(\.error)
    static let onError = color(\.onError)

    /// Trust-badge and status colours. Always paired with an icon and a word, never used alone.
    static let success = color(\.success)
    static let warning = color(\.warning)
    static let neutral = color(\.neutral)

    static let navy = Color(uiColor: UIColor(hex: ValidatopiaColors.shared.NAVY))
    static let green = Color(uiColor: UIColor(hex: ValidatopiaColors.shared.GREEN))

    private static func color(_ role: KeyPath<ValidatopiaColorScheme, String>) -> Color {
        let light = UIColor(hex: ValidatopiaColors.shared.light[keyPath: role])
        let dark = UIColor(hex: ValidatopiaColors.shared.dark[keyPath: role])
        return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }
}

extension UIColor {
    /// A colour from a `"#RRGGBB"` string.
    convenience init(hex: String) {
        let value = UInt32(hex.dropFirst(), radix: 16) ?? 0
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension View {
    /// Applies Validatopia branding to a whole app: navy (green in dark mode) as the tint, and the
    /// brand background behind every screen.
    func validatopiaBranding() -> some View {
        tint(Brand.primary)
            .background(Brand.background.ignoresSafeArea())
    }
}
