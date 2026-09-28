package org.multipaz.samples.validatopia.wallet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaColors
import org.multipaz.samples.validatopia.shared.R as SharedR

/**
 * Validatopia Photo ID card art, drawn on the device because the issuer doesn't supply any.
 *
 * It follows the NZ DISTF "flash pass" guidance
 * (https://github.com/nz-trust-framework/DISTF-reference-architecture/blob/main/guidance/FLASH-PASS.md):
 * the card appears on the presenting screen and in consent sheets, so it shows only the credential
 * type and its provider. It carries no name, portrait or other identifying information, and it
 * avoids anything that resembles a physical document or its security features. The art is a
 * decorative landscape in Validatopia navy and green; white on navy is 17:1, green on navy 7.2:1.
 */
internal class PhotoIdCardArt(private val context: Context) {
    private val textMeasurer by lazy {
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    private val logo: ImageBitmap by lazy {
        BitmapFactory.decodeResource(context.resources, SharedR.drawable.valid8_advisory_logo_white).asImageBitmap()
    }

    /** The same art for every Photo ID: nothing on it identifies the holder. */
    val image: ImageBitmap by lazy { render() }

    private fun render(): ImageBitmap {
        val bitmap = ImageBitmap(WIDTH, HEIGHT)
        val navy = ValidatopiaColors.NAVY.toColor()
        val green = ValidatopiaColors.GREEN.toColor()
        val white = Color.White
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()

        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(w, h),
        ) {
            drawRect(navy)

            // Three rolling hills, far to near, in deepening tints of the brand green.
            for ((index, alpha) in listOf(0.22f, 0.40f, 0.70f).withIndex()) {
                val top = h * (0.50f + index * 0.12f)
                val hill = Path().apply {
                    moveTo(0f, top + h * 0.10f)
                    cubicTo(w * 0.25f, top - h * 0.12f, w * 0.55f, top + h * 0.18f, w, top - h * 0.04f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(hill, green.copy(alpha = alpha))
            }

            drawText(
                textMeasurer = textMeasurer,
                text = buildAnnotatedString {
                    append("Photo ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("ID") }
                },
                topLeft = Offset(56f, 52f),
                style = TextStyle(fontSize = 60.sp, color = white),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "Validatopia",
                topLeft = Offset(58f, 136f),
                style = TextStyle(fontSize = 30.sp, color = green, fontWeight = FontWeight.SemiBold),
            )

            // Provider credit, bottom right, on the darkest hill.
            val logoWidth = 250f
            val logoHeight = logoWidth * logo.height / logo.width
            val logoTopLeft = Offset(w - logoWidth - 48f, h - logoHeight - 44f)
            drawText(
                textMeasurer = textMeasurer,
                text = "Powered by",
                topLeft = Offset(logoTopLeft.x, logoTopLeft.y - 28f),
                style = TextStyle(fontSize = 18.sp, color = white),
            )
            drawImage(
                image = logo,
                dstOffset = IntOffset(logoTopLeft.x.toInt(), logoTopLeft.y.toInt()),
                dstSize = IntSize(logoWidth.toInt(), logoHeight.toInt()),
            )
        }
        return bitmap
    }

    private fun String.toColor() = Color(0xFF000000.toInt() or removePrefix("#").toInt(16))

    private companion object {
        // ISO/IEC 7810 ID-1 aspect ratio (85.60 × 53.98 mm), a familiar wallet-card shape.
        const val WIDTH = 1012
        const val HEIGHT = 638
    }
}
