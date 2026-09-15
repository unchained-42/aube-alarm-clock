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
 * during the day; at night it's different — the alarm's dawn screen and ring will run for up
 * to hours, so an unplugged phone at bedtime is a problem *now* even at 90%.
 */
object ChargeGuardPolicy {
    const val LOW_BATTERY_PERCENT = 40

    private const val MINUTE = 60_000L

    /** Interval between nags while unplugged in the evening/night with a healthy battery. */
    const val NIGHT_INTERVAL_MS = 3 * MINUTE

    /** Minutes before the target bedtime at which "plug it in for the night" nagging starts. */
    const val BEDTIME_LEAD_MINUTES = 30L

    fun nagIntervalMillis(batteryPercent: Int, plugged: Boolean, inNightWindow: Boolean): Long? {
        if (plugged) return null
        val byBattery = when {
            batteryPercent >= LOW_BATTERY_PERCENT -> null
            batteryPercent >= 30 -> 1 * MINUTE
            batteryPercent >= 20 -> 2 * MINUTE
            batteryPercent >= 10 -> 5 * MINUTE
            batteryPercent >= 5 -> 15 * MINUTE
            else -> 30 * MINUTE
        }
        return when {
            // Low battery wins over the night cadence even when it's *slower*: at 4% the
            // point is to still have a phone at wake-up time, not to nag every 3 minutes.
            byBattery != null -> byBattery
            inNightWindow -> NIGHT_INTERVAL_MS
            else -> null
        }
    }
}
