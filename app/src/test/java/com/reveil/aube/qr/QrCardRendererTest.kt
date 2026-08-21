package com.reveil.aube.qr

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DEFAULT_QR_PAYLOAD]'s own doc comment documents a real incident: an em dash in this exact
 * string once made every scan of an otherwise-valid code fail to match, because ZXing's
 * encoder can silently fall back to a charset that can't represent it, breaking the
 * encode -> print -> scan -> decode round trip. Plain ASCII is what keeps that fixed; this
 * pins it so a future edit (a "nicer" typographic dash, a smart quote from a rich text paste)
 * can't reintroduce the same failure silently.
 */
@RunWith(RobolectricTestRunner::class)
class QrCardRendererTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `DEFAULT_QR_PAYLOAD is plain ASCII`() {
        val nonAscii = DEFAULT_QR_PAYLOAD.filter { it.code > 127 }
        assertTrue("found non-ASCII character(s): \"$nonAscii\" in DEFAULT_QR_PAYLOAD", nonAscii.isEmpty())
    }

    @Test
    fun `DEFAULT_QR_PAYLOAD is not blank`() {
        assertTrue(DEFAULT_QR_PAYLOAD.isNotBlank())
    }

    @Test
    fun `render produces a bitmap with the card's fixed aspect ratio`() {
        val widthPx = 1200
        val bitmap = QrCardRenderer.render(context, DEFAULT_QR_PAYLOAD, widthPx)

        assertEquals(widthPx, bitmap.width)
        assertEquals((widthPx * 1900f / 1400f).toInt(), bitmap.height)
    }

    @Test
    fun `render does not throw for a custom payload with accented characters`() {
        // A custom scanned code isn't guaranteed to be ASCII-only the way the built-in
        // default is — the explicit UTF-8 charset hint in renderQrWithBadge is what's
        // supposed to make this safe.
        QrCardRenderer.render(context, "Réveille-toi ! Café à 07h30 ☀", 800)
    }
}
