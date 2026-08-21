package com.reveil.aube.settings

import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [LocaleHelper] is what every Activity/Service/the Application itself calls from
 * `attachBaseContext` — a mistake here (wrong context returned, stale locale) shows up as
 * "half the app is in French, half isn't" rather than a crash, which makes it easy to miss
 * without a test pinning the exact contract.
 */
@RunWith(RobolectricTestRunner::class)
class LocaleHelperTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `getStoredTag is null before anything is ever set`() {
        assertNull(LocaleHelper.getStoredTag(context))
    }

    @Test
    fun `setStoredTag then getStoredTag round trips`() {
        LocaleHelper.setStoredTag(context, "ja")
        assertEquals("ja", LocaleHelper.getStoredTag(context))
    }

    @Test
    fun `setStoredTag with null clears a previously stored tag`() {
        LocaleHelper.setStoredTag(context, "de")
        LocaleHelper.setStoredTag(context, null)
        assertNull(LocaleHelper.getStoredTag(context))
    }

    @Test
    fun `wrap returns the same context untouched when no override is stored`() {
        assertSame(context, LocaleHelper.wrap(context))
    }

    @Test
    fun `wrap applies the stored locale to both resources and the JVM default`() {
        LocaleHelper.setStoredTag(context, "ar")
        val wrapped = LocaleHelper.wrap(context)

        assertEquals("ar", wrapped.resources.configuration.locales.get(0).language)
        // HomeScreen's weekday formatting reads Locale.getDefault() directly, not resources —
        // both must move together or that formatting silently stays in the previous language.
        assertEquals("ar", Locale.getDefault().language)
    }
}
