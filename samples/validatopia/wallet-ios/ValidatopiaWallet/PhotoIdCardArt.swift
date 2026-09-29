import UIKit

/// Validatopia Photo ID card art, drawn on the device because the issuer doesn't supply any. The
/// same design as the Android wallet's.
///
/// It follows the NZ DISTF "flash pass" guidance
/// (https://github.com/nz-trust-framework/DISTF-reference-architecture/blob/main/guidance/FLASH-PASS.md):
/// the card appears on the presenting screen and in the consent sheet, so it shows only the
/// credential type and its provider. It carries no name, portrait or other identifying information,
/// and avoids anything resembling a physical document or its security features. White on navy is
/// 17:1, green on navy 7.2:1.
enum PhotoIdCardArt {
    /// The same art for every Photo ID: nothing on it identifies the holder.
    static let image: UIImage = render()

    // ISO/IEC 7810 ID-1 aspect ratio (85.60 × 53.98 mm), a familiar wallet-card shape.
    private static let size = CGSize(width: 1012, height: 638)

    private static func render() -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            let w = size.width
            let h = size.height
            let navy = UIColor(hex: ValidatopiaColors.shared.NAVY)
            let green = UIColor(hex: ValidatopiaColors.shared.GREEN)
            navy.setFill()
            context.fill(CGRect(origin: .zero, size: size))

            // Three rolling hills, far to near, in deepening tints of the brand green.
            for (index, alpha) in [0.22, 0.40, 0.70].enumerated() {
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
                green.withAlphaComponent(alpha).setFill()
                hill.fill()
            }

            let title = NSMutableAttributedString(
                string: "Photo ",
                attributes: [.font: UIFont.systemFont(ofSize: 60), .foregroundColor: UIColor.white]
            )
            title.append(NSAttributedString(
                string: "ID",
                attributes: [.font: UIFont.systemFont(ofSize: 60, weight: .bold), .foregroundColor: UIColor.white]
            ))
            title.draw(at: CGPoint(x: 56, y: 52))
            NSAttributedString(
                string: "Validatopia",
                attributes: [.font: UIFont.systemFont(ofSize: 30, weight: .semibold), .foregroundColor: green]
            ).draw(at: CGPoint(x: 58, y: 136))

            // Provider credit, bottom right, on the darkest hill.
            if let logo = UIImage(named: "Valid8LogoWhite") {
                let logoWidth: CGFloat = 250
                let logoHeight = logoWidth * logo.size.height / logo.size.width
                let logoOrigin = CGPoint(x: w - logoWidth - 48, y: h - logoHeight - 44)
                NSAttributedString(
                    string: "Powered by",
                    attributes: [.font: UIFont.systemFont(ofSize: 18), .foregroundColor: UIColor.white]
                ).draw(at: CGPoint(x: logoOrigin.x, y: logoOrigin.y - 28))
                logo.draw(in: CGRect(origin: logoOrigin, size: CGSize(width: logoWidth, height: logoHeight)))
            }
        }
    }
}
