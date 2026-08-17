package com.reveil.aube.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reveil.aube.R
import com.reveil.aube.qr.DEFAULT_QR_PAYLOAD
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "aube_settings")

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
     * True from the moment [com.reveil.aube.ringing.AlarmRingingService] starts until a real
     * dismiss. Anything that reacts to settings changes by rescheduling (see HomeScreen) must
     * check this first: rescheduling while a ring/dawn ramp is already underway restarts
     * [com.reveil.aube.tracking.SleepTrackingService] mid-cycle, which re-fires the whole
     * launch sequence — a second notification and sound on top of the one already ringing.
     */
    val ringingUnresolved: Boolean
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
    return raw.split(RECORD_SEP).mapNotNull { record ->
        val parts = record.split(FIELD_SEP)
        if (parts.size != 4) return@mapNotNull null
        val delay = parts[2].toIntOrNull() ?: return@mapNotNull null
        CustomReminder(id = parts[0], enabled = parts[1].toBoolean(), delayMinutes = delay, message = parts[3])
    }
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

class SettingsRepository(private val context: Context) {

    val settings: Flow<AlarmSettings> = context.dataStore.data.map { prefs ->
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
            ringingUnresolved = prefs[KEY_RINGING_UNRESOLVED] ?: false
        )
    }

    suspend fun setAlarmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ALARM_ENABLED] = enabled }
    }

    suspend fun setWeekdayWindow(window: WakeWindow) {
        context.dataStore.edit {
            it[KEY_WD_EARLIEST] = window.earliestMinute
            it[KEY_WD_LATEST] = window.latestMinute
        }
    }

    suspend fun setWeekendWindow(window: WakeWindow) {
        context.dataStore.edit {
            it[KEY_WE_EARLIEST] = window.earliestMinute
            it[KEY_WE_LATEST] = window.latestMinute
        }
    }

    suspend fun setUseSeparateWeekend(use: Boolean) {
        context.dataStore.edit { it[KEY_USE_WEEKEND] = use }
    }

    suspend fun setDawnDurationMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_DAWN_MINUTES] = minutes }
    }

    suspend fun setQrPayload(payload: String?) {
        context.dataStore.edit {
            if (payload == null) it.remove(KEY_QR_PAYLOAD) else it[KEY_QR_PAYLOAD] = payload
        }
    }

    suspend fun setMusicUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(KEY_MUSIC_URI) else it[KEY_MUSIC_URI] = uri
        }
    }

    suspend fun setLastHandledDate(date: LocalDate) {
        context.dataStore.edit { it[KEY_LAST_HANDLED_DATE] = date.toString() }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_VIBRATION_ENABLED] = enabled }
    }

    suspend fun setReminders(reminders: List<CustomReminder>) {
        context.dataStore.edit { it[KEY_REMINDERS] = encodeReminders(reminders) }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setOneTimeOverride(date: LocalDate, window: WakeWindow) {
        context.dataStore.edit {
            it[KEY_OVERRIDE_DATE] = date.toString()
            it[KEY_OVERRIDE_EARLIEST] = window.earliestMinute
            it[KEY_OVERRIDE_LATEST] = window.latestMinute
        }
    }

    suspend fun clearOneTimeOverride() {
        context.dataStore.edit {
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
        context.dataStore.edit { it[KEY_RINGING_UNRESOLVED] = active }
    }

    suspend fun isAlarmRingingUnresolved(): Boolean =
        context.dataStore.data.map { it[KEY_RINGING_UNRESOLVED] ?: false }.first()

    companion object {
        fun newReminderId(): String = UUID.randomUUID().toString()
    }
}
