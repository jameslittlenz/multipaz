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
import org.multipaz.samples.validatopia.shared.branding.CardArtStyle
import org.multipaz.samples.validatopia.shared.branding.ValidatopiaCardArt
import org.multipaz.samples.validatopia.shared.R as SharedR
import java.util.concurrent.ConcurrentHashMap

/**
 * Validatopia card art, drawn on the device because the issuer doesn't supply any: the Photo ID's
 * design for every document, in the colors [ValidatopiaCardArt] gives its type, with the holder's
 * shortened name when the document carries one (see [ValidatopiaCardArt]).
 */
internal class DocumentCardArt(private val context: Context) {
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

    private val images = ConcurrentHashMap<Pair<CardArtStyle, String?>, ImageBitmap>()

    /** The art for a document of [style]'s type held by [holderName] ("Claudia H."), if known. */
    fun image(style: CardArtStyle, holderName: String?): ImageBitmap =
        images.getOrPut(style to holderName) { render(style, holderName) }

    private fun render(style: CardArtStyle, holderName: String?): ImageBitmap {
        val bitmap = ImageBitmap(WIDTH, HEIGHT)
        val hills = style.hills.toColor()
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()

        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(w, h),
        ) {
            drawRect(style.background.toColor())

            // Three rolling hills, far to near, in deepening tints.
            for ((index, alpha) in ValidatopiaCardArt.hillAlphas.withIndex()) {
                val top = h * (0.50f + index * 0.12f)
                val hill = Path().apply {
                    moveTo(0f, top + h * 0.10f)
                    cubicTo(w * 0.25f, top - h * 0.12f, w * 0.55f, top + h * 0.18f, w, top - h * 0.04f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(hill, hills.copy(alpha = alpha.toFloat()))
            }

            drawText(
                textMeasurer = textMeasurer,
                text = buildAnnotatedString {
                    append(style.titleLead)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(style.titleEmphasis) }
                },
                topLeft = Offset(56f, 52f),
                style = TextStyle(fontSize = 60.sp, color = style.title.toColor()),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = style.subtitleText,
                topLeft = Offset(58f, 136f),
                style = TextStyle(fontSize = 30.sp, color = style.subtitle.toColor(), fontWeight = FontWeight.SemiBold),
            )

            // Provider credit, bottom right, on the darkest hill.
            val logoWidth = 250f
            val logoHeight = logoWidth * logo.height / logo.width
            val logoTopLeft = Offset(w - logoWidth - 48f, h - logoHeight - BOTTOM_MARGIN)

            // The holder's name, bottom left, level with the bottom of the logo.
            if (holderName != null) {
                val name = textMeasurer.measure(
                    text = holderName,
                    style = TextStyle(fontSize = 54.sp, color = style.title.toColor()),
                )
                drawText(name, topLeft = Offset(58f, h - BOTTOM_MARGIN - name.size.height))
            }
            drawText(
                textMeasurer = textMeasurer,
                text = "Powered by",
                topLeft = Offset(logoTopLeft.x, logoTopLeft.y - 28f),
                style = TextStyle(fontSize = 18.sp, color = ValidatopiaCardArt.CREDIT.toColor()),
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
        const val BOTTOM_MARGIN = 44f
    }
}
