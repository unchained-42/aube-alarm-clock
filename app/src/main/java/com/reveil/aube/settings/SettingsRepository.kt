package com.reveil.aube.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.reveil.aube.R
import com.reveil.aube.qr.DEFAULT_QR_PAYLOAD
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID

private const val TAG = "AubeSettings"

/**
 * Both stores replace a corrupted file with empty preferences instead of throwing on every
 * read forever. This isn't hypothetical: a forced hardware reboot (power held ~10 s) during
 * a ring left aube_settings.preferences_pb as 61 bytes of zeros on a real device — the
 * file's size had reached disk, its contents hadn't — and from then on every single read
 * (BootReceiver, the alarm screen, the home screen) crashed with a CorruptionException. The
 * settings in that file were already gone at that point; the only thing the crash added was
 * an app that could never ring again. Losing settings is recoverable; that isn't.
 */
private val Context.dataStore by preferencesDataStore(
    name = "aube_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)


/**
 * Wake window expressed in minutes-from-midnight. [earliest] is the earliest the alarm may
 * fire (light-sleep detection can bring it forward from [latest]); [latest] is the hard
 * deadline that always fires regardless of what the sensor sees.
 */
data class WakeWindow(val earliestMinute: Int, val latestMinute: Int) {
    init {
        require(earliestMinute in 0..1439 && latestMinute in 0..1439) { "Out of range minute" }
        require(earliestMinute <= latestMinute) { "earliest must be <= latest" }
    }
}

/** A single post-wake nudge: fully user-owned — add, edit, delete, reorder freely. */
data class CustomReminder(
    val id: String,
    val enabled: Boolean,
    val delayMinutes: Int,
    val message: String
)

data class AlarmSettings(
    val alarmEnabled: Boolean,
    val weekdayWindow: WakeWindow,
    val weekendWindow: WakeWindow,
    val useSeparateWeekend: Boolean,
    val dawnDurationMinutes: Int,
    val trackingLeadMinutes: Int,
    /**
     * Null means "use the built-in Aube code" — every install already has a working dismiss
     * code with zero setup. Non-null means the user scanned their own barcode/QR to replace
     * it. Always resolve through [effectiveQrPayload], never read this raw.
     */
    val qrPayload: String?,
    val musicUri: String?,
    val vibrationEnabled: Boolean,
    /**
     * The last day whose alarm was already fired/dismissed. [AlarmScheduler] must never
     * offer this day (or anything before it) again — otherwise dismissing early in the
     * window finds "today" still technically valid and re-arms the exact same alarm,
     * which looks like the alarm looping and never actually turning off.
     */
    val lastHandledDate: LocalDate?,
    val reminders: List<CustomReminder>,
    val onboardingCompleted: Boolean,
    /**
     * A just-this-once replacement for a single date, set by editing the "Demain"/
     * "Aujourd'hui" time directly on Home. Doesn't touch the recurring Semaine/Week-end
     * schedule at all — it only wins for that one date, then stops applying on its own
     * once that date is in the past (no explicit "clear" step needed).
     */
    val oneTimeOverrideDate: LocalDate?,
    val oneTimeOverrideWindow: WakeWindow?,
    /**
     * Set during onboarding: how long before the hard deadline the alarm locks itself.
     * [com.reveil.aube.ui.HomeScreen] disables the enable/disable switch, the one-time time
     * edit, and both weekday/weekend sliders once now falls inside this window, so a
     * half-asleep decision to turn the alarm off or push it later isn't available in the one
     * stretch of time it would actually get used.
     */
    val targetSleepMinutes: Int,
    /**
     * Minute of the day at which the charge guard starts reminding you to plug the phone in
     * for the night, if it isn't already — chosen during onboarding, editable in Settings.
     * Null means no reminder. See [com.reveil.aube.charge.ChargeGuard]: a fixed "30 minutes
     * before bedtime" was the first design, and was wrong for the same reason a snooze is —
     * a chirp in the stretch where you're actually drifting off is exactly the disturbance
     * this app exists to avoid. So the person picks the moment, once, while awake.
     */
    val chargeReminderMinute: Int?
) {
    fun windowFor(dayOfWeekIsWeekend: Boolean): WakeWindow =
        if (dayOfWeekIsWeekend && useSeparateWeekend) weekendWindow else weekdayWindow

    fun windowFor(date: LocalDate, dayOfWeekIsWeekend: Boolean): WakeWindow =
        if (date == oneTimeOverrideDate && oneTimeOverrideWindow != null) oneTimeOverrideWindow
        else windowFor(dayOfWeekIsWeekend)

    /** What actually gets compared against a scanned barcode — see [qrPayload]. */
    val effectiveQrPayload: String get() = qrPayload ?: DEFAULT_QR_PAYLOAD

    val usingDefaultQrCode: Boolean get() = qrPayload.isNullOrBlank()
}

private val KEY_ALARM_ENABLED = booleanPreferencesKey("alarm_enabled")
private val KEY_WD_EARLIEST = intPreferencesKey("wd_earliest")
private val KEY_WD_LATEST = intPreferencesKey("wd_latest")
private val KEY_WE_EARLIEST = intPreferencesKey("we_earliest")
private val KEY_WE_LATEST = intPreferencesKey("we_latest")
private val KEY_USE_WEEKEND = booleanPreferencesKey("use_weekend")
private val KEY_DAWN_MINUTES = intPreferencesKey("dawn_minutes")
private val KEY_LEAD_MINUTES = intPreferencesKey("lead_minutes")
private val KEY_QR_PAYLOAD = stringPreferencesKey("qr_payload")
private val KEY_MUSIC_URI = stringPreferencesKey("music_uri")
private val KEY_LAST_HANDLED_DATE = stringPreferencesKey("last_handled_date")
private val KEY_VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
private val KEY_REMINDERS = stringPreferencesKey("reminders_v2")
private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
private val KEY_OVERRIDE_DATE = stringPreferencesKey("override_date")
private val KEY_OVERRIDE_EARLIEST = intPreferencesKey("override_earliest")
private val KEY_OVERRIDE_LATEST = intPreferencesKey("override_latest")
private val KEY_RINGING_UNRESOLVED = booleanPreferencesKey("ringing_unresolved")
private const val RINGING_FLAG_FILE = "aube_ringing_state"
private const val RINGING_FLAG_KEY = "ringing_unresolved"
private val KEY_TARGET_SLEEP_MINUTES = intPreferencesKey("target_sleep_minutes")
private const val DEFAULT_TARGET_SLEEP_MINUTES = 480 // 8h
private val KEY_CHARGE_REMINDER_MINUTE = intPreferencesKey("charge_reminder_minute")

// Plain-text field/record separators rather than JSON, to avoid pulling in a serialization
// dependency for what's really just a short local list. These control characters can't be
// typed from a soft keyboard, so they're safe delimiters for free-form reminder text.
private const val FIELD_SEP = ''
private const val RECORD_SEP = ''

// Seeded once per install in the user's language at that time, then fully user-editable —
// later changing the device language does not retranslate reminders already saved.
private fun defaultReminders(context: Context) = listOf(
    CustomReminder("water", true, 2, context.getString(R.string.reminder_default_water)),
    CustomReminder("light", true, 8, context.getString(R.string.reminder_default_light)),
    CustomReminder("breakfast", true, 25, context.getString(R.string.reminder_default_breakfast))
)

private fun encodeReminders(reminders: List<CustomReminder>): String =
    reminders.joinToString(RECORD_SEP.toString()) { r ->
        listOf(r.id, r.enabled.toString(), r.delayMinutes.toString(), r.message).joinToString(FIELD_SEP.toString())
    }

private fun decodeReminders(raw: String?, context: Context): List<CustomReminder> {
    if (raw.isNullOrEmpty()) return defaultReminders(context)
    val records = raw.split(RECORD_SEP)
    val decoded = records.mapNotNull { record ->
        val parts = record.split(FIELD_SEP)
        if (parts.size != 4) return@mapNotNull null
        val delay = parts[2].toIntOrNull() ?: return@mapNotNull null
        CustomReminder(id = parts[0], enabled = parts[1].toBoolean(), delayMinutes = delay, message = parts[3])
    }
    // A record that fails to parse is silently dropped by mapNotNull above — logging here is
    // the only way a corrupted store (a stray delimiter character, a truncated write) would
    // ever be visible in a bug report instead of just quietly losing a reminder.
    if (decoded.size < records.size) {
        Log.w(TAG, "decodeReminders: dropped ${records.size - decoded.size} malformed record(s)")
    }
    return decoded
}

/**
 * The UI never lets you create more than a 45-minute early-wake gap (see the slider range in
 * [com.reveil.aube.ui.WakeWindowEditor]), but earlier app versions had bugs that could persist
 * a much larger one — e.g. a stale `earliestMinute` from a completely different time of day
 * left behind under a newly-set `latestMinute`. Reapplying the same cap on every read heals
 * any such already-corrupted DataStore value automatically, instead of leaving it stuck until
 * the user happens to re-edit that exact window.
 */
private const val MAX_EARLY_WAKE_GAP_MINUTES = 45

private fun sanitizeWindow(window: WakeWindow): WakeWindow =
    if (window.latestMinute - window.earliestMinute > MAX_EARLY_WAKE_GAP_MINUTES) {
        window.copy(earliestMinute = window.latestMinute - MAX_EARLY_WAKE_GAP_MINUTES)
    } else {
        window
    }

class SettingsRepository(
    private val context: Context,
    // Defaults to the real per-process DataStore singleton every production call site gets
    // for free. Tests inject their own isolated instance instead — `Context.dataStore` is a
    // single JVM-wide delegate (the first Context to touch it wins, for every Context after
    // that, regardless of which one asked), so without this seam, state written by one test
    // leaks into whichever test happens to run next in the same JVM.
    private val dataStore: DataStore<Preferences> = context.dataStore
) {

    val settings: Flow<AlarmSettings> = dataStore.data.map { prefs ->
        AlarmSettings(
            // Defaults to off: a fresh install shouldn't silently schedule an alarm before
            // the reliability checks (exact alarms, battery, MIUI autostart...) are done.
            alarmEnabled = prefs[KEY_ALARM_ENABLED] ?: false,
            weekdayWindow = sanitizeWindow(WakeWindow(
                earliestMinute = prefs[KEY_WD_EARLIEST] ?: (7 * 60),
                latestMinute = prefs[KEY_WD_LATEST] ?: (7 * 60 + 30)
            )),
            weekendWindow = sanitizeWindow(WakeWindow(
                earliestMinute = prefs[KEY_WE_EARLIEST] ?: (8 * 60 + 30),
                latestMinute = prefs[KEY_WE_LATEST] ?: (9 * 60 + 15)
            )),
            useSeparateWeekend = prefs[KEY_USE_WEEKEND] ?: true,
            dawnDurationMinutes = prefs[KEY_DAWN_MINUTES] ?: 25,
            trackingLeadMinutes = prefs[KEY_LEAD_MINUTES] ?: 30,
            qrPayload = prefs[KEY_QR_PAYLOAD],
            musicUri = prefs[KEY_MUSIC_URI],
            vibrationEnabled = prefs[KEY_VIBRATION_ENABLED] ?: true,
            lastHandledDate = prefs[KEY_LAST_HANDLED_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            reminders = decodeReminders(prefs[KEY_REMINDERS], context),
            // Anyone updating from a version before onboarding existed already has real
            // settings written (the alarm toggle, at least) — treat that as "already set
            // up" so an existing install doesn't get funneled through onboarding again.
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: (prefs[KEY_ALARM_ENABLED] != null),
            oneTimeOverrideDate = prefs[KEY_OVERRIDE_DATE]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            oneTimeOverrideWindow = if (prefs[KEY_OVERRIDE_EARLIEST] != null && prefs[KEY_OVERRIDE_LATEST] != null) {
                // A one-time override is meant to be pinned exactly (earliest == latest, see
                // HomeScreen's HeroNextAlarm) — sanitizing here too in case an old override
                // was written before that pin existed.
                sanitizeWindow(WakeWindow(prefs[KEY_OVERRIDE_EARLIEST]!!, prefs[KEY_OVERRIDE_LATEST]!!))
            } else null,
            targetSleepMinutes = prefs[KEY_TARGET_SLEEP_MINUTES] ?: DEFAULT_TARGET_SLEEP_MINUTES,
            chargeReminderMinute = prefs[KEY_CHARGE_REMINDER_MINUTE]
        )
    }

    suspend fun setAlarmEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ALARM_ENABLED] = enabled }
    }

    suspend fun setWeekdayWindow(window: WakeWindow) {
        dataStore.edit {
            it[KEY_WD_EARLIEST] = window.earliestMinute
            it[KEY_WD_LATEST] = window.latestMinute
        }
    }

    suspend fun setWeekendWindow(window: WakeWindow) {
        dataStore.edit {
            it[KEY_WE_EARLIEST] = window.earliestMinute
            it[KEY_WE_LATEST] = window.latestMinute
        }
    }

    suspend fun setUseSeparateWeekend(use: Boolean) {
        dataStore.edit { it[KEY_USE_WEEKEND] = use }
    }

    suspend fun setDawnDurationMinutes(minutes: Int) {
        dataStore.edit { it[KEY_DAWN_MINUTES] = minutes }
    }

    suspend fun setQrPayload(payload: String?) {
        dataStore.edit {
            if (payload == null) it.remove(KEY_QR_PAYLOAD) else it[KEY_QR_PAYLOAD] = payload
        }
    }

    suspend fun setMusicUri(uri: String?) {
        dataStore.edit {
            if (uri == null) it.remove(KEY_MUSIC_URI) else it[KEY_MUSIC_URI] = uri
        }
    }

    suspend fun setLastHandledDate(date: LocalDate) {
        dataStore.edit { it[KEY_LAST_HANDLED_DATE] = date.toString() }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_VIBRATION_ENABLED] = enabled }
    }

    suspend fun setReminders(reminders: List<CustomReminder>) {
        dataStore.edit { it[KEY_REMINDERS] = encodeReminders(reminders) }
    }

    suspend fun setTargetSleepMinutes(minutes: Int) {
        dataStore.edit { it[KEY_TARGET_SLEEP_MINUTES] = minutes }
    }

    suspend fun setChargeReminderMinute(minute: Int?) {
        dataStore.edit { if (minute == null) it.remove(KEY_CHARGE_REMINDER_MINUTE) else it[KEY_CHARGE_REMINDER_MINUTE] = minute }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setOneTimeOverride(date: LocalDate, window: WakeWindow) {
        dataStore.edit {
            it[KEY_OVERRIDE_DATE] = date.toString()
            it[KEY_OVERRIDE_EARLIEST] = window.earliestMinute
            it[KEY_OVERRIDE_LATEST] = window.latestMinute
        }
    }

    suspend fun clearOneTimeOverride() {
        dataStore.edit {
            it.remove(KEY_OVERRIDE_DATE)
            it.remove(KEY_OVERRIDE_EARLIEST)
            it.remove(KEY_OVERRIDE_LATEST)
        }
    }

    /**
     * Set true the moment [com.reveil.aube.ringing.AlarmRingingService] starts, cleared only
     * by a real dismiss. A reboot kills that service (and everything else) regardless of any
     * in-app hardening — screen pinning, the overlay, none of it survives the OS actually
     * restarting — so on its own a reboot would be the easiest possible bypass: hold the
     * power button, and the next thing you see is "see you tomorrow." [BootReceiver] checks
     * this flag and resumes ringing immediately instead, if it's still true after boot.
     */
    suspend fun setAlarmRinging(active: Boolean) {
        // The one value here that must survive exactly the event most likely to destroy this
        // file (see the corruption handler's doc): the flag lives in its own SharedPreferences
        // file, written synchronously with commit() — which fsyncs before its atomic rename,
        // unlike DataStore 1.1's okio-based writer — in device-protected storage, so it's
        // also readable in the window between boot and first unlock. Still mirrored into
        // DataStore for anything (tests included) that reads it from there.
        ringingFlagPrefs().edit().putBoolean(RINGING_FLAG_KEY, active).commit()
        dataStore.edit { it[KEY_RINGING_UNRESOLVED] = active }
    }

    suspend fun isAlarmRingingUnresolved(): Boolean =
        ringingFlagPrefs().getBoolean(RINGING_FLAG_KEY, false) ||
            (dataStore.data.first()[KEY_RINGING_UNRESOLVED] ?: false)

    private fun ringingFlagPrefs() =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(RINGING_FLAG_FILE, Context.MODE_PRIVATE)


    companion object {
        fun newReminderId(): String = UUID.randomUUID().toString()
    }
}
