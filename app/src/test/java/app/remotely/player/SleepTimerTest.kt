package app.remotely.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    private val min = 60_000L

    @Test fun `off does nothing`() {
        assertEquals(SleepAction.NONE, SleepTimer().tick(10 * min, inBreak = false, breakMinutes = 0))
    }

    @Test fun `cycles through the choices and back to off`() {
        val t = SleepTimer()
        val seen = List(9) { t.cycle(0); t.minutes }
        assertEquals(listOf(15, 30, 45, 60, 75, 90, 105, 120, 0), seen)
    }

    @Test fun `warns once a minute before the end, then stops and switches off`() {
        val t = SleepTimer().apply { cycle(0); cycle(0) }
        assertEquals(SleepAction.NONE, t.tick(28 * min, false, 0))
        assertEquals(SleepAction.WARN, t.tick(29 * min, false, 0))
        assertEquals(SleepAction.NONE, t.tick(29 * min + 1, false, 0))
        assertEquals(SleepAction.STOP, t.tick(30 * min, false, 0))
        assertEquals(0, t.minutes)
        assertEquals(SleepAction.NONE, t.tick(31 * min, false, 0))
    }

    @Test fun `extending starts the chosen time over`() {
        val t = SleepTimer().apply { cycle(0); cycle(0) }
        t.tick(29 * min, false, 0)
        t.extend(29 * min, 0)
        assertEquals(SleepAction.NONE, t.tick(30 * min, false, 0))
        assertEquals(SleepAction.WARN, t.tick(58 * min, false, 0))
    }

    @Test fun `a break stops after its own minutes, counted from its start`() {
        val t = SleepTimer()
        assertEquals(SleepAction.NONE, t.tick(100 * min, true, 15))
        assertEquals(SleepAction.WARN, t.tick(114 * min, true, 15))
        assertEquals(SleepAction.STOP, t.tick(115 * min, true, 15))
    }

    @Test fun `break setting off never stops`() {
        val t = SleepTimer()
        t.tick(0, true, 0)
        assertEquals(SleepAction.NONE, t.tick(600 * min, true, 0))
    }

    @Test fun `the break count ends with the break`() {
        val t = SleepTimer()
        t.tick(0, true, 15)
        t.tick(10 * min, false, 15)
        assertEquals(SleepAction.NONE, t.tick(11 * min, true, 15))
        assertEquals(SleepAction.NONE, t.tick(16 * min, true, 15))
    }

    @Test fun `extending in a break gives its minutes again and keeps the timer`() {
        val t = SleepTimer().apply { cycle(0); cycle(0) }
        t.tick(0, true, 15)
        t.tick(14 * min, true, 15)
        t.extend(14 * min, 15)
        assertEquals(SleepAction.NONE, t.tick(15 * min, true, 15))
        assertEquals(SleepAction.STOP, t.tick(30 * min, true, 15))
    }

    @Test fun `label tells the time left`() {
        val t = SleepTimer()
        assertEquals("Выкл", t.label(0))
        t.cycle(0)
        t.cycle(0)
        assertEquals("30 мин", t.label(0))
        assertEquals("1 мин", t.label(29 * min + 30_000))
    }
}
