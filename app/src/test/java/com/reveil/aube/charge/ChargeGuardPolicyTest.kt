package com.reveil.aube.charge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChargeGuardPolicyTest {

    private val minute = 60_000L

    @Test
    fun `plugged in never nags, whatever the level or time`() {
        assertNull(ChargeGuardPolicy.nagIntervalMillis(3, plugged = true, inNightWindow = true))
        assertNull(ChargeGuardPolicy.nagIntervalMillis(90, plugged = true, inNightWindow = false))
    }

    @Test
    fun `healthy battery during the day is left alone`() {
        assertNull(ChargeGuardPolicy.nagIntervalMillis(40, plugged = false, inNightWindow = false))
        assertNull(ChargeGuardPolicy.nagIntervalMillis(100, plugged = false, inNightWindow = false))
    }

    @Test
    fun `the lower the battery, the sparser the nag`() {
        assertEquals(1 * minute, ChargeGuardPolicy.nagIntervalMillis(39, false, false))
        assertEquals(1 * minute, ChargeGuardPolicy.nagIntervalMillis(30, false, false))
        assertEquals(2 * minute, ChargeGuardPolicy.nagIntervalMillis(29, false, false))
        assertEquals(5 * minute, ChargeGuardPolicy.nagIntervalMillis(19, false, false))
        assertEquals(15 * minute, ChargeGuardPolicy.nagIntervalMillis(9, false, false))
        assertEquals(30 * minute, ChargeGuardPolicy.nagIntervalMillis(4, false, false))
        assertEquals(30 * minute, ChargeGuardPolicy.nagIntervalMillis(0, false, false))
    }

    @Test
    fun `night window nags a healthy battery, but low battery keeps its own cadence`() {
        assertEquals(ChargeGuardPolicy.NIGHT_INTERVAL_MS, ChargeGuardPolicy.nagIntervalMillis(80, false, inNightWindow = true))
        // 4% at night: the battery-preserving 30 min wins over the 3 min night cadence.
        assertEquals(30 * minute, ChargeGuardPolicy.nagIntervalMillis(4, false, inNightWindow = true))
        // 35% at night: 1 min is already faster than the night cadence.
        assertEquals(1 * minute, ChargeGuardPolicy.nagIntervalMillis(35, false, inNightWindow = true))
    }

    @Test
    fun `night window runs from before bedtime until the wake window opens`() {
        val zone = ZoneId.of("Europe/Paris")
        val earliest = ZonedDateTime.of(2026, 9, 16, 6, 30, 0, 0, zone)
        val latest = ZonedDateTime.of(2026, 9, 16, 7, 0, 0, 0, zone)
        val sleep = 480 // 8h -> bedtime 23:00, nagging from 22:30
        assertEquals(false, ChargeGuard.inNightWindow(ZonedDateTime.of(2026, 9, 15, 22, 29, 0, 0, zone), earliest, latest, sleep))
        assertEquals(true, ChargeGuard.inNightWindow(ZonedDateTime.of(2026, 9, 15, 22, 30, 0, 0, zone), earliest, latest, sleep))
        assertEquals(true, ChargeGuard.inNightWindow(ZonedDateTime.of(2026, 9, 16, 3, 0, 0, 0, zone), earliest, latest, sleep))
        assertEquals(false, ChargeGuard.inNightWindow(ZonedDateTime.of(2026, 9, 16, 6, 30, 0, 0, zone), earliest, latest, sleep))
    }
}
