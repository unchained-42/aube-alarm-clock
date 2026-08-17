package com.reveil.aube.ringing

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/** The square region [SCAN_TARGET_RATIO] of the frame that the analyzer actually decodes. */
const val SCAN_TARGET_RATIO = 0.7f

/** Dims everything outside the square [SCAN_TARGET_RATIO] actually decodes, with corner
 * brackets marking it — so where you aim matches what's actually analyzed. */
@Composable
fun ScanOverlay() {
    val dimColor = Color.Black.copy(alpha = 0.45f)
    val cornerColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetSize = minOf(size.width, size.height) * SCAN_TARGET_RATIO
        val left = (size.width - targetSize) / 2f
        val top = (size.height - targetSize) / 2f
        val rect = Rect(left, top, left + targetSize, top + targetSize)

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val dimPaint = Paint().apply { color = dimColor.toArgb(); style = Paint.Style.FILL }
            val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            val saveCount = native.saveLayer(null, null)
            native.drawRect(0f, 0f, size.width, size.height, dimPaint)
            native.drawRect(rect.left, rect.top, rect.right, rect.bottom, clearPaint)
            native.restoreToCount(saveCount)
        }

        val cornerLength = 32.dp.toPx()
        val stroke = 3.dp.toPx()

        // Top-left
        drawLine(cornerColor, rect.topLeft, rect.topLeft.copy(x = rect.left + cornerLength), stroke)
        drawLine(cornerColor, rect.topLeft, rect.topLeft.copy(y = rect.top + cornerLength), stroke)
        // Top-right
        drawLine(cornerColor, rect.topRight, rect.topRight.copy(x = rect.right - cornerLength), stroke)
        drawLine(cornerColor, rect.topRight, rect.topRight.copy(y = rect.top + cornerLength), stroke)
        // Bottom-right
        drawLine(cornerColor, rect.bottomRight, rect.bottomRight.copy(x = rect.right - cornerLength), stroke)
        drawLine(cornerColor, rect.bottomRight, rect.bottomRight.copy(y = rect.bottom - cornerLength), stroke)
        // Bottom-left
        drawLine(cornerColor, rect.bottomLeft, rect.bottomLeft.copy(x = rect.left + cornerLength), stroke)
        drawLine(cornerColor, rect.bottomLeft, rect.bottomLeft.copy(y = rect.bottom - cornerLength), stroke)
    }
}
