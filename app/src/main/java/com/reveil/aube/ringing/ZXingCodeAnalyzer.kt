package com.reveil.aube.ringing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import kotlin.math.roundToInt

/**
 * Decodes only the centered square [SCAN_TARGET_RATIO] of each frame — matching
 * [ScanOverlay] — via ZXing directly on the Y-plane luminance, with a fallback attempt on the
 * inverted image. ML Kit's barcode scanner was tried first here and proved unreliable at
 * actually recognizing scans on real hardware; ZXing plus this exact technique (crop to a
 * centered target, try both polarities) is the approach the open-source QRAlarm project
 * (github.com/sweakpl/qralarm-android) uses for the same problem, and it's also the same
 * library this app already uses to *generate* the QR code, so the encoder and decoder agree
 * on the same format end to end.
 */
class ZXingCodeAnalyzer(private val onCodeFound: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader()

    override fun analyze(image: ImageProxy) {
        image.use { img ->
            val plane = img.planes.firstOrNull()
            if (plane == null) return
            val buffer = plane.buffer
            buffer.rewind()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val targetSize = (minOf(img.width, img.height) * SCAN_TARGET_RATIO).roundToInt()
            val left = (img.width - targetSize) / 2
            val top = (img.height - targetSize) / 2

            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, img.height, left, top, targetSize, targetSize, false
            )

            decode(BinaryBitmap(HybridBinarizer(source)))?.let { onCodeFound(it); return }
            decode(BinaryBitmap(HybridBinarizer(source.invert())))?.let { onCodeFound(it) }
        }
    }

    private fun decode(bitmap: BinaryBitmap): String? {
        reader.reset()
        return try {
            reader.decode(bitmap).text
        } catch (_: ReaderException) {
            null
        }
    }
}
