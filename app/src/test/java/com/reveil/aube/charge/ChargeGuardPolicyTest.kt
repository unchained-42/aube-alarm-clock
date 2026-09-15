package com.reveil.aube.charge

import com.reveil.aube.charge.ChargeGuardPolicy.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChargeGuardPolicyTest {

    private val minute = 60_000L

    @Test
    fun `plugged in never nags, whatever the level or time`() {
        assertNull(ChargeGuardPolicy.nagIntervalMillis(3, plugged = true, phase = Phase.PRE_SLEEP))
        assertNull(ChargeGuardPolicy.nagIntervalMillis(90, plugged = true, phase = Phase.DAY))
    }

    @Test
    fun `healthy battery during the day is left alone`() {
        assertNull(ChargeGuardPolicy.nagIntervalMillis(40, plugged = false, phase = Phase.DAY))
        assertNull(ChargeGuardPolicy.nagIntervalMillis(100, plugged = false, phase = Phase.DAY))
    }

    @Test
    fun `the lower the battery, the sparser the nag`() {
        assertEquals(1 * minute, ChargeGuardPolicy.nagIntervalMillis(39, false, Phase.DAY))
        assertEquals(1 * minute, ChargeGuardPolicy.nagIntervalMillis(30, false, Phase.DAY))
        assertEquals(2 * minute, ChargeGuardPolicy.nagIntervalMillis(29, false, Phase.DAY))
        assertEquals(5 * minute, ChargeGuardPolicy.nagIntervalMillis(19, false, Phase.DAY))
        assertEquals(15 * minute, ChargeGuardPolicy.nagIntervalMillis(9, false, Phase.DAY))
        assertEquals(30 * minute, ChargeGuardPolicy.nagIntervalMillis(4, false, Phase.DAY))
        assertEquals(30 * minute, ChargeGuardPolicy.nagIntervalMillis(0, false, Phase.DAY))
    }

    @Test
    fun `before bedtime, unplugged nags a healthy battery, but low battery keeps its own cadence`() {
        assertEquals(ChargeGuardPolicy.PRE_SLEEP_INTERVAL_MS, ChargeGuardPolicy.nagIntervalMillis(80, false, Phase.PRE_SLEEP))
        // 4%: the battery-preserving 30 min wins over the 3 min pre-sleep cadence.
        assertEquals(30 * minute, ChargeGuardPolicy.nagIntervalMillis(4, false, Phase.PRE_SLEEP))
        // 35%: 1 min is already faster than the pre-sleep cadence.
        assertEquals(1 * minute, ChargeGuardPolicy.nagIntervalMillis(35, false, Phase.PRE_SLEEP))
    }

    @Test
    fun `asleep, nothing ever chirps — not even at 1 percent`() {
        assertNull(ChargeGuardPolicy.nagIntervalMillis(1, plugged = false, phase = Phase.SLEEP))
        assertNull(ChargeGuardPolicy.nagIntervalMillis(35, plugged = false, phase = Phase.SLEEP))
        assertNull(ChargeGuardPolicy.nagIntervalMillis(90, plugged = false, phase = Phase.SLEEP))
    }

    @Test
    fun `phases follow the chosen reminder time, the derived bedtime and the wake window`() {
        val zone = ZoneId.of("Europe/Paris")
        val earliest = ZonedDateTime.of(2026, 9, 16, 6, 30, 0, 0, zone)
        val latest = ZonedDateTime.of(2026, 9, 16, 7, 0, 0, 0, zone)
        val sleep = 480 // 8h -> bedtime 23:00
        fun at(d: Int, h: Int, m: Int, reminder: Int?) =
            ChargeGuard.phaseAt(ZonedDateTime.of(2026, 9, d, h, m, 0, 0, zone), earliest, latest, sleep, reminder)
        val r22 = 22 * 60
        assertEquals(Phase.DAY, at(15, 21, 59, r22))
        assertEquals(Phase.PRE_SLEEP, at(15, 22, 0, r22))
        assertEquals(Phase.PRE_SLEEP, at(15, 22, 59, r22))
        assertEquals(Phase.SLEEP, at(15, 23, 0, r22))
        assertEquals(Phase.SLEEP, at(16, 3, 0, r22))
        assertEquals(Phase.SLEEP, at(16, 6, 29, r22))
        assertEquals(Phase.DAY, at(16, 6, 30, r22))
        // No reminder configured: never PRE_SLEEP, still silent while asleep.
        assertEquals(Phase.DAY, at(15, 22, 30, null))
        assertEquals(Phase.SLEEP, at(16, 3, 0, null))
    }

    @Test
    fun `a reminder at or after bedtime still gets its half hour, and an absurdly early one is clamped`() {
        val zone = ZoneId.of("Europe/Paris")
        val earliest = ZonedDateTime.of(2026, 9, 16, 6, 30, 0, 0, zone)
        val latest = ZonedDateTime.of(2026, 9, 16, 7, 0, 0, 0, zone)
        val sleep = 480 // bedtime 23:00
        fun at(d: Int, h: Int, m: Int, reminder: Int) =
            ChargeGuard.phaseAt(ZonedDateTime.of(2026, 9, d, h, m, 0, 0, zone), earliest, latest, sleep, reminder)
        // Reminder 23:30, past the derived bedtime: honoured 23:30–00:00, silent around it.
        assertEquals(Phase.SLEEP, at(15, 23, 15, 23 * 60 + 30))
        assertEquals(Phase.PRE_SLEEP, at(15, 23, 45, 23 * 60 + 30))
        assertEquals(Phase.SLEEP, at(16, 0, 0, 23 * 60 + 30))
        // Reminder 09:00 with bedtime 23:00: six hours, not fourteen.
        assertEquals(Phase.PRE_SLEEP, at(15, 12, 0, 9 * 60))
        assertEquals(Phase.DAY, at(15, 15, 0, 9 * 60))
    }
}
