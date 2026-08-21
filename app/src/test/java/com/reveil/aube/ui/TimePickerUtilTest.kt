package com.reveil.aube.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimePickerUtilTest {

    @Test
    fun `formats minutes as zero-padded HH mm`() {
        assertEquals("00:00", formatMinutes(0))
        assertEquals("01:30", formatMinutes(90))
        assertEquals("07:05", formatMinutes(7 * 60 + 5))
        assertEquals("23:59", formatMinutes(23 * 60 + 59))
    }
}
