package com.reveil.aube.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** One of the languages this app ships translations for. [displayName] is always shown in
 * that language's own script — a language picker conventionally shows autonyms ("Deutsch",
 * "日本語"), not translations of the language name into whatever's currently selected. */
data class AppLanguage(val tag: String, val displayName: String)

val SUPPORTED_LANGUAGES = listOf(
    AppLanguage("fr", "Français"),
    AppLanguage("en", "English"),
    AppLanguage("de", "Deutsch"),
    AppLanguage("es", "Español"),
    AppLanguage("zh", "中文"),
    AppLanguage("pt", "Português"),
    AppLanguage("ru", "Русский"),
    AppLanguage("ja", "日本語"),
    AppLanguage("ar", "العربية"),
    AppLanguage("hi", "हिन्दी")
)

private const val PREFS_NAME = "aube_locale"
private const val KEY_LANGUAGE_TAG = "language_tag"

/**
 * A manual, dependency-free per-app language override — minSdk 26 rules out relying solely on
 * Android 13's native per-app language API (LocaleManager), so every Activity/Service/the
 * Application itself instead wraps its own base Context in [attachBaseContext] via [wrap].
 * Each of those has an independently-resolved Resources instance, so the override has to be
 * applied at every one of those entry points, not just once globally.
 *
 * Backed by plain SharedPreferences rather than the DataStore-based SettingsRepository used
 * elsewhere in the app: attachBaseContext runs synchronously, before any coroutine/Flow read
 * could complete, so the stored value needs to be readable instantly.
 */
object LocaleHelper {
    fun getStoredTag(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LANGUAGE_TAG, null)

    fun setStoredTag(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (tag == null) remove(KEY_LANGUAGE_TAG) else putString(KEY_LANGUAGE_TAG, tag)
        }.apply()
    }

    fun wrap(context: Context): Context {
        val tag = getStoredTag(context) ?: return context
        val locale = Locale.forLanguageTag(tag)
        // Also needed for non-resource locale-sensitive code (e.g. HomeScreen's weekday
        // formatting via Locale.getDefault()), not just strings.xml lookups.
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
