package app.remotely.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ZalaTimeTest {
    @Test fun parsesUtcAndMinsk() {
        val utc = ZalaTime.parseUtc("2026.10.15 13:00:00")!!
        val minsk = ZalaTime.parseMinsk("2026.10.15 16:00:00")!!
        assertEquals(utc, minsk) // Minsk is UTC+3
        assertEquals("2026.10.15 13:00:00", ZalaTime.formatUtc(utc))
        assertEquals("16:00", ZalaTime.hhmm(utc))
    }

    @Test fun badInputIsNull() = assertNull(ZalaTime.parseUtc("garbage"))

    @Test fun dayLabels() {
        val today = LocalDate.of(2026, 10, 15)
        assertEquals("Сегодня", ZalaTime.dayLabel(today, today))
        assertEquals("Вчера", ZalaTime.dayLabel(today.minusDays(1), today))
        assertEquals("Позавчера", ZalaTime.dayLabel(today.minusDays(2), today))
        assertEquals("Завтра", ZalaTime.dayLabel(today.plusDays(1), today))
        assertEquals("12.10", ZalaTime.dayLabel(today.minusDays(3), today))
    }

    @Test fun dayRangeIsMinskMidnightToMidnight() {
        val r = ZalaTime.dayRange(LocalDate.of(2026, 10, 15))
        assertEquals(ZalaTime.parseUtc("2026.10.14 21:00:00"), r.first)
        assertEquals(ZalaTime.parseUtc("2026.10.15 21:00:00")!! - 1, r.last)
    }
}
