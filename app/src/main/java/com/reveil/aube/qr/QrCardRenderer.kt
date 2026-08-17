package com.reveil.aube.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.print.PrintHelper
import com.reveil.aube.R
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dismiss code every install starts with, active with zero setup. Scanning any other
 * barcode overrides it — see [com.reveil.aube.settings.AlarmSettings.effectiveQrPayload]. The
 * text itself is only ever read by a QR decoder, never shown on screen or on the printed card.
 *
 * Plain ASCII only, deliberately — see the CHARACTER_SET hint in [renderQrWithBadge] for why
 * an em dash here used to be enough to make every single scan of a perfectly valid code fail
 * to match: without an explicit charset, ZXing's encoder can fall back to Latin-1, which can't
 * represent that character, so the round trip through encode→print→scan→decode no longer
 * reproduces this exact string and the `==` comparison in QrDismissScreen never passes.
 */
const val DEFAULT_QR_PAYLOAD =
    "AUBE - Memento mori, carpe diem. Chaque scan est un choix : vivre ce jour, pas le dormir."

private object Palette {
    const val NIGHT = 0xFF14161F.toInt()
    const val NIGHT_SURFACE = 0xFF1D202D.toInt()
    const val DAWN = 0xFFE08A4F.toInt()
    const val TEXT_SECONDARY = 0xFF938D7C.toInt()
    const val CARD_BG = 0xFFF7F4EC.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
}

/** Renders the printable "Aube" card: wordmark, sun-badge QR code, and instructions. */
object QrCardRenderer {

    fun render(context: Context, payload: String, widthPx: Int = 1200): Bitmap {
        val heightPx = (widthPx * 1900f / 1400f).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Palette.CARD_BG)

        val framePad = widthPx * 0.026f
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx * 0.0022f
            color = Palette.NIGHT_SURFACE
        }
        canvas.drawRoundRect(
            RectF(framePad, framePad, widthPx - framePad, heightPx - framePad),
            widthPx * 0.034f, widthPx * 0.034f, framePaint
        )

        val markSize = widthPx * 0.093f
        val markCx = widthPx / 2f
        val markTop = heightPx * 0.079f
        drawSunMark(canvas, markCx, markTop + markSize / 2f, markSize / 2f, Palette.DAWN)

        val wordmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.NIGHT
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = widthPx * 0.066f
            letterSpacing = 0.20f
        }
        val wordmarkY = markTop + markSize + widthPx * 0.09f
        canvas.drawText("AUBE", markCx, wordmarkY, wordmarkPaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.TEXT_SECONDARY
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = widthPx * 0.024f
            letterSpacing = 0.22f
        }
        canvas.drawText(context.getString(R.string.qr_card_subtitle), markCx, wordmarkY + widthPx * 0.045f, subPaint)

        val qrTarget = (widthPx * 0.70f).toInt()
        val qrBitmap = renderQrWithBadge(payload, qrTarget)
        val qrX = (widthPx - qrBitmap.width) / 2f
        val qrY = heightPx * 0.300f
        val cardPad = widthPx * 0.023f

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, 0, 0, 0)
            maskFilter = BlurMaskFilter(widthPx * 0.02f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(
                qrX - cardPad, qrY - cardPad + widthPx * 0.006f,
                qrX + qrBitmap.width + cardPad, qrY + qrBitmap.height + cardPad + widthPx * 0.006f
            ),
            widthPx * 0.028f, widthPx * 0.028f, shadowPaint
        )
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WHITE }
        canvas.drawRoundRect(
            RectF(qrX - cardPad, qrY - cardPad, qrX + qrBitmap.width + cardPad, qrY + qrBitmap.height + cardPad),
            widthPx * 0.028f, widthPx * 0.028f, whitePaint
        )
        canvas.drawBitmap(qrBitmap, qrX, qrY, null)

        val instrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.NIGHT
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = widthPx * 0.0285f
        }
        val instrY = qrY + qrBitmap.height + cardPad + widthPx * 0.075f
        canvas.drawText(context.getString(R.string.qr_card_instruction), markCx, instrY, instrPaint)

        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Palette.TEXT_SECONDARY
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = widthPx * 0.0215f
        }
        canvas.drawText(context.getString(R.string.qr_card_tagline), markCx, instrY + widthPx * 0.042f, tagPaint)

        return bitmap
    }

    private fun renderQrWithBadge(payload: String, targetSizePx: Int): Bitmap {
        // Explicit UTF-8 so the encoder never silently drops into a lossy fallback charset
        // for any character outside it (see the DEFAULT_QR_PAYLOAD doc comment) — matters
        // for any future text here, and for a custom code containing accents typed elsewhere.
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val code = Encoder.encode(payload, ErrorCorrectionLevel.H, hints)
        val matrix: ByteMatrix = code.matrix
        val quietZone = 2
        val totalModules = matrix.width + quietZone * 2
        val moduleSize = (targetSizePx / totalModules).coerceAtLeast(4)
        val size = moduleSize * totalModules

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Palette.WHITE)

        val modulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.NIGHT }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WHITE }
        val radius = moduleSize * 0.28f

        // The three 7x7 finder patterns (the big corner squares) are what a scanner locks
        // onto first, using the precise 1:1:3:1:1 nested-square ratio to even recognize
        // "this is a QR code" before it reads a single data bit. Rounding them like the data
        // modules — which every module used to get uniformly — distorts that ratio and
        // measurably hurts detection, especially at an angle or with a shaky half-asleep
        // hand. So they're drawn as perfect sharp squares instead, and skipped below.
        val finderOrigins = listOf(0 to 0, (matrix.width - 7) to 0, 0 to (matrix.height - 7))
        fun inFinderRegion(x: Int, y: Int) = finderOrigins.any { (fx, fy) -> x in fx until fx + 7 && y in fy until fy + 7 }
        for ((fx, fy) in finderOrigins) {
            val left = (fx + quietZone) * moduleSize.toFloat()
            val top = (fy + quietZone) * moduleSize.toFloat()
            val outer = moduleSize * 7f
            canvas.drawRect(left, top, left + outer, top + outer, modulePaint)
            val whiteInset = moduleSize.toFloat()
            val whiteSize = moduleSize * 5f
            canvas.drawRect(left + whiteInset, top + whiteInset, left + whiteInset + whiteSize, top + whiteInset + whiteSize, whitePaint)
            val coreInset = moduleSize * 2f
            val coreSize = moduleSize * 3f
            canvas.drawRect(left + coreInset, top + coreInset, left + coreInset + coreSize, top + coreInset + coreSize, modulePaint)
        }

        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (inFinderRegion(x, y)) continue
                if (matrix.get(x, y).toInt() == 1) {
                    val left = (x + quietZone) * moduleSize.toFloat()
                    val top = (y + quietZone) * moduleSize.toFloat()
                    canvas.drawRoundRect(
                        RectF(left, top, left + moduleSize, top + moduleSize),
                        radius, radius, modulePaint
                    )
                }
            }
        }

        // Center sun badge: white knockout ring so surrounding modules stay clean, then the
        // Dawn-orange disc with the mark in reverse — safely inside error-correction H's ~30%
        // damage budget (the badge covers under 6% of the code's area).
        val badgeD = size * 0.24f
        val cx = size / 2f
        val cy = size / 2f
        canvas.drawCircle(cx, cy, badgeD / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WHITE })
        canvas.drawCircle(cx, cy, badgeD / 2f * 0.86f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DAWN })
        drawSunMark(canvas, cx, cy + badgeD * 0.02f, badgeD * 0.30f, Palette.WHITE)

        return bmp
    }

    /** A minimalist rising sun: a half-disc over a horizon line, with a few radiating rays. */
    private fun drawSunMark(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = r * 0.11f
        }
        val sunCy = cy + r * 0.18f

        val nRays = 7
        for (i in 0 until nRays) {
            val angle = Math.PI + (i.toDouble() / (nRays - 1)) * Math.PI
            val inner = r * 1.35
            val outer = r * (if (i % 2 == 0) 1.95 else 1.7)
            val x1 = cx + inner * cos(angle)
            val y1 = sunCy + inner * sin(angle)
            val x2 = cx + outer * cos(angle)
            val y2 = sunCy + outer * sin(angle)
            canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), strokePaint)
        }

        val hw = r * 1.55f
        canvas.drawLine(cx - hw, sunCy, cx + hw, sunCy, strokePaint)

        canvas.save()
        canvas.clipRect(cx - r, sunCy - r, cx + r, sunCy)
        canvas.drawCircle(cx, sunCy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        canvas.restore()
    }
}

/** Renders [payload] off the main thread and recomposes once ready; null while pending. */
@Composable
fun rememberQrCardBitmap(payload: String): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(payload) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(payload) {
        bitmap = withContext(Dispatchers.Default) { QrCardRenderer.render(context, payload) }
    }
    return bitmap
}

/** Opens the system print dialog on [bitmap] — includes "Save as PDF" as a target. */
fun printQrCard(context: Context, bitmap: Bitmap) {
    val printHelper = PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
    printHelper.printBitmap(context.getString(R.string.qr_card_print_job_title), bitmap)
}
