package com.reveil.aube.charge

/**
 * The pure decision behind the charge guard: given the phone's power state, how long until
 * the next "plug me in" nag — or null for none. Kept free of Android so it can be unit-tested
 * as a table.
 *
 * The shape of the table is deliberate. A drained battery is the one way this phone can still
 * end up off (everything else is closed by hardcore mode), so below [LOW_BATTERY_PERCENT] the
 * guard nags until someone plugs it in — but the nag itself costs charge, so the lower the
 * battery, the sparser it gets: at 35% it can afford to be insistent, at 4% every chirp is
 * borrowed from the alarm's own ability to ring later. Above the threshold nothing happens
 * during the day. The evening is different — the alarm's dawn screen and ring will run for up
 * to hours, so an unplugged phone is a problem *now* even at 90% — so from the reminder time
 * the user picked (see [com.reveil.aube.settings.AlarmSettings.chargeReminderMinute]) it nags
 * regardless of level until the phone is plugged in. And the sleep window itself is different
 * again: nothing chirps there, ever. An alarm that wakes you at 4 am to plug it in has
 * defeated its own purpose; if the battery is going to run out overnight, the time to say so
 * was before you fell asleep, and that's when it does.
 */
object ChargeGuardPolicy {

    /** Where "now" falls relative to the user's sleep schedule. */
    enum class Phase {
        /** Nothing special: only a low battery nags. */
        DAY,
        /** From the user's chosen reminder time until bedtime: unplugged nags whatever the level. */
        PRE_SLEEP,
        /** From the target bedtime to the wake window: never a sound, whatever the level. */
        SLEEP
    }

    const val LOW_BATTERY_PERCENT = 40

    private const val MINUTE = 60_000L

    /** Interval between nags while unplugged at the evening reminder, battery permitting. */
    const val PRE_SLEEP_INTERVAL_MS = 3 * MINUTE
    /**
     * Above this, the evening reminder stays quiet: a phone at 80% will make it through the
     * night, the dawn ramp and the ring, so chirping about it would just be noise at the one
     * time of day noise is least welcome. Between this and [LOW_BATTERY_PERCENT] the evening
     * reminder is the only thing that nags — the daytime rules don't.
     */
    const val PRE_SLEEP_BATTERY_PERCENT = 50

    /** How long a reminder window lasts at minimum, even when the chosen time is at or past bedtime. */
    const val REMINDER_MIN_WINDOW_MINUTES = 30L
    /** A reminder set absurdly early relative to bedtime is clamped to this, not honoured all day. */
    const val REMINDER_MAX_WINDOW_MINUTES = 6 * 60L
    /** Suggested default during onboarding: this long before the derived bedtime. */
    const val DEFAULT_REMINDER_LEAD_MINUTES = 60

    fun nagIntervalMillis(batteryPercent: Int, plugged: Boolean, phase: Phase): Long? {
        if (plugged || phase == Phase.SLEEP) return null
        val byBattery = when {
            batteryPercent >= LOW_BATTERY_PERCENT -> null
            batteryPercent >= 30 -> 1 * MINUTE
            batteryPercent >= 20 -> 2 * MINUTE
            batteryPercent >= 10 -> 5 * MINUTE
            batteryPercent >= 5 -> 15 * MINUTE
            else -> 30 * MINUTE
        }
        return when {
            // Low battery wins over the pre-sleep cadence even when it's *slower*: at 4% the
            // point is to still have a phone at wake-up time, not to nag every 3 minutes.
            byBattery != null -> byBattery
            phase == Phase.PRE_SLEEP && batteryPercent <= PRE_SLEEP_BATTERY_PERCENT -> PRE_SLEEP_INTERVAL_MS
            else -> null
        }
    }
}
