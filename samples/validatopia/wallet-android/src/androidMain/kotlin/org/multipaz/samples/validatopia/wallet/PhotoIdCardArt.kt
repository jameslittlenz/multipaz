package org.multipaz.samples.validatopia.wallet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.multipaz.document.Document
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaColors
import org.multipaz.samples.validatopia.shared.R as SharedR

/**
 * Validatopia Photo ID card art, drawn on the device because the issuer doesn't supply any.
 *
 * ID-1 proportions, navy with green accents. It carries the holder's name, a TEST marking, and
 * "Powered by" with the white VALID8 Advisory logo. Text colours: white on navy is
 * 17:1 and green on navy is 7.2:1.
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

    fun render(document: Document): ImageBitmap {
        val bitmap = ImageBitmap(WIDTH, HEIGHT)
        val navy = ValidatopiaColors.NAVY.toColor()
        val green = ValidatopiaColors.GREEN.toColor()
        val white = Color.White

        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        ) {
            drawRect(navy)

            // A large check mark, echoing the "validated" mark in the VALID8 logo.
            val check = Path().apply {
                moveTo(WIDTH * 0.66f, HEIGHT * 0.30f)
                lineTo(WIDTH * 0.76f, HEIGHT * 0.52f)
                lineTo(WIDTH * 0.95f, HEIGHT * 0.06f)
            }
            drawPath(
                path = check,
                color = green.copy(alpha = 0.35f),
                style = Stroke(width = 34f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawRect(green, topLeft = Offset(0f, HEIGHT - 18f), size = Size(WIDTH.toFloat(), 18f))

            drawText(
                textMeasurer = textMeasurer,
                text = "VALIDATOPIA",
                topLeft = Offset(56f, 48f),
                style = TextStyle(fontSize = 54.sp, color = white, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "PHOTO ID",
                topLeft = Offset(56f, 118f),
                style = TextStyle(fontSize = 34.sp, color = green, fontWeight = FontWeight.Bold, letterSpacing = 6.sp),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = (document.displayName ?: "Photo ID holder").uppercase(),
                topLeft = Offset(56f, 300f),
                style = TextStyle(fontSize = 46.sp, color = white, fontWeight = FontWeight.SemiBold),
                size = Size(WIDTH * 0.62f, 140f),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "TEST DOCUMENT · NOT VALID FOR REAL USE",
                topLeft = Offset(56f, 450f),
                style = TextStyle(fontSize = 22.sp, color = green, letterSpacing = 1.sp),
            )

            // "Powered by" + the white logo, bottom right.
            val logoWidth = 280f
            val logoHeight = logoWidth * logo.height / logo.width
            val logoTopLeft = Offset(WIDTH - logoWidth - 56f, HEIGHT - logoHeight - 56f)
            drawText(
                textMeasurer = textMeasurer,
                text = "Powered by",
                topLeft = Offset(logoTopLeft.x, logoTopLeft.y - 30f),
                style = TextStyle(fontSize = 20.sp, color = white),
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
        // ISO/IEC 7810 ID-1 aspect ratio (85.60 × 53.98 mm).
        const val WIDTH = 1012
        const val HEIGHT = 638
    }
}
