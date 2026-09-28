package org.multipaz.samples.validatopia.shared.branding

import kotlin.math.pow

/** WCAG 2.2 relative luminance and contrast ratio, per https://www.w3.org/TR/WCAG22/#dfn-contrast-ratio. */
object ContrastRatio {
    /** The contrast ratio between two `"#RRGGBB"` colors, in the range `[1.0, 21.0]`. */
    fun of(firstHex: String, secondHex: String): Double {
        val first = relativeLuminance(firstHex)
        val second = relativeLuminance(secondHex)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(hex: String): Double {
        val r = linearize(channel(hex, startIndex = 1))
        val g = linearize(channel(hex, startIndex = 3))
        val b = linearize(channel(hex, startIndex = 5))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channel(hex: String, startIndex: Int): Double =
        hex.substring(startIndex, startIndex + 2).toInt(16) / 255.0

    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}
