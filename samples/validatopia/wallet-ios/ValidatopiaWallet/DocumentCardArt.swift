import UIKit

/// Validatopia card art, drawn on the device because the issuer doesn't supply any: one design for
/// every document, in the colors `ValidatopiaCardArt` gives its type. The same design as the
/// Android wallet's. See `ValidatopiaCardArt` for how it follows the NZ DISTF "flash pass" guidance:
/// nothing on it identifies the holder.
@MainActor
enum DocumentCardArt {
    // ISO/IEC 7810 ID-1 aspect ratio (85.60 × 53.98 mm), a familiar wallet-card shape.
    private static let size = CGSize(width: 1012, height: 638)

    private static var pngs: [String: ByteString] = [:]

    /// The art, as PNG, for every document of `style`'s type.
    static func png(for style: CardArtStyle) -> ByteString {
        let key = style.titleLead + style.titleEmphasis
        if let png = pngs[key] {
            return png
        }
        let png = render(style).pngData()!.toByteString()
        pngs[key] = png
        return png
    }

    private static func render(_ style: CardArtStyle) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            let w = size.width
            let h = size.height
            let hills = UIColor(hex: style.hills)
            UIColor(hex: style.background).setFill()
            context.fill(CGRect(origin: .zero, size: size))

            // Three rolling hills, far to near, the nearest opaque.
            for (index, alpha) in ValidatopiaCardArt.shared.hillAlphas.enumerated() {
                let top = h * (0.50 + CGFloat(index) * 0.12)
                let hill = UIBezierPath()
                hill.move(to: CGPoint(x: 0, y: top + h * 0.10))
                hill.addCurve(
                    to: CGPoint(x: w, y: top - h * 0.04),
                    controlPoint1: CGPoint(x: w * 0.25, y: top - h * 0.12),
                    controlPoint2: CGPoint(x: w * 0.55, y: top + h * 0.18)
                )
                hill.addLine(to: CGPoint(x: w, y: h))
                hill.addLine(to: CGPoint(x: 0, y: h))
                hill.close()
                hills.withAlphaComponent(alpha.doubleValue).setFill()
                hill.fill()
            }

            let titleColor = UIColor(hex: style.title)
            let title = NSMutableAttributedString(
                string: style.titleLead,
                attributes: [.font: UIFont.systemFont(ofSize: 60), .foregroundColor: titleColor]
            )
            title.append(NSAttributedString(
                string: style.titleEmphasis,
                attributes: [.font: UIFont.systemFont(ofSize: 60, weight: .bold), .foregroundColor: titleColor]
            ))
            title.draw(at: CGPoint(x: 56, y: 52))
            NSAttributedString(
                string: "Validatopia",
                attributes: [
                    .font: UIFont.systemFont(ofSize: 30, weight: .semibold),
                    .foregroundColor: UIColor(hex: style.subtitle),
                ]
            ).draw(at: CGPoint(x: 58, y: 136))

            // Provider credit, bottom right, on the nearest hill.
            if let logo = UIImage(named: "Valid8LogoWhite") {
                let logoWidth: CGFloat = 250
                let logoHeight = logoWidth * logo.size.height / logo.size.width
                let logoOrigin = CGPoint(x: w - logoWidth - 48, y: h - logoHeight - 44)
                NSAttributedString(
                    string: "Powered by",
                    attributes: [
                        .font: UIFont.systemFont(ofSize: 18),
                        .foregroundColor: UIColor(hex: ValidatopiaCardArt.shared.CREDIT),
                    ]
                ).draw(at: CGPoint(x: logoOrigin.x, y: logoOrigin.y - 28))
                logo.draw(in: CGRect(origin: logoOrigin, size: CGSize(width: logoWidth, height: logoHeight)))
            }
        }
    }
}
