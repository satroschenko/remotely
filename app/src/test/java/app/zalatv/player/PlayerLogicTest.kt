package app.zalatv.player

import app.zalatv.core.ZalaTime
import app.zalatv.data.api.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTest {
    @Test fun bufferingTooLong() {
        val w = Watchdog(12_000)
        assertFalse(w.tick(0, EngineState.BUFFERING, true, 0))
        assertFalse(w.tick(12_000, EngineState.BUFFERING, true, 0))
        assertTrue(w.tick(12_001, EngineState.BUFFERING, true, 0))
    }

    @Test fun frozenWhileReady() {
        val w = Watchdog(12_000)
        assertFalse(w.tick(0, EngineState.READY, true, 5))
        assertFalse(w.tick(10_000, EngineState.READY, true, 6))
        assertFalse(w.tick(22_000, EngineState.READY, true, 6))
        assertTrue(w.tick(22_001, EngineState.READY, true, 6))
    }

    @Test fun pausedNeverStalls() {
        val w = Watchdog(12_000)
        assertFalse(w.tick(0, EngineState.READY, false, 1))
        assertFalse(w.tick(60_000, EngineState.READY, false, 1))
    }
}

class SeekAccumulatorTest {
    @Test fun tapsAccumulateAndSettle() {
        val s = SeekAccumulator()
        s.press(+1, big = false, isRepeat = false, now = 0)
        s.press(+1, big = false, isRepeat = false, now = 300)
        s.press(-1, big = true, isRepeat = false, now = 500)
        assertEquals(60_000L - 300_000L, s.pendingMs)
        assertNull(s.takeIfSettled(1_000))
        assertEquals(-240_000L, s.takeIfSettled(1_300))
        assertNull(s.takeIfSettled(5_000))
    }

    @Test fun holdAccelerates() {
        val s = SeekAccumulator()
        s.press(+1, false, false, 0) // 30s
        s.press(+1, false, true, 100) // throttled
        s.press(+1, false, true, 300) // 30s (held < 2s)
        s.press(+1, false, true, 2_500) // 60s
        s.press(+1, false, true, 4_500) // 5min
        assertEquals(30_000L + 30_000 + 60_000 + 300_000, s.pendingMs)
    }
}

class ArchiveTimelineTest {
    private fun t(s: String) = ZalaTime.parseMinsk("2026.10.$s")!!
    private fun p(code: String, a: String, b: String) = Programme(code, "c", code, "", "", t(a), t(b), true)

    private val a = p("A", "15 18:00:00", "15 19:00:00")
    private val b = p("B", "15 19:00:00", "15 20:00:00")
    private val c = p("C", "15 20:30:00", "15 21:00:00") // gap 20:00-20:30
    private val d = p("D", "15 21:00:00", "15 23:00:00") // on air at 22:00
    private val now = t("15 22:00:00")
    private val tl = ArchiveTimeline(listOf(d, b, a, c), now, 48 * 3600_000L)

    @Test fun resolvesInsideProgramme() =
        assertEquals(TimelinePoint.InProgramme(b, 47 * 60_000L), tl.resolve(t("15 19:47:00")))

    @Test fun crossesBackIntoPrevious() {
        // At B+2min, seek back 5 min → A at 57 min.
        val target = b.startMs + 2 * 60_000 - 5 * 60_000
        assertEquals(TimelinePoint.InProgramme(a, 57 * 60_000L), tl.resolve(target))
    }

    @Test fun gapJumpsToNextStart() = assertEquals(TimelinePoint.InProgramme(c, 0), tl.resolve(t("15 20:10:00")))

    @Test fun nearNowIsLive() {
        assertEquals(TimelinePoint.Live, tl.resolve(now - 10_000))
        assertEquals(TimelinePoint.Live, tl.resolve(now + 3600_000))
    }

    @Test fun clampsToArchiveWindow() {
        val r = ArchiveTimeline(listOf(p("OLD", "13 21:00:00", "13 23:00:00")), now, 48 * 3600_000L).resolve(t("13 19:00:00"))
        assertEquals(TimelinePoint.InProgramme(p("OLD", "13 21:00:00", "13 23:00:00"), 60 * 60_000L), r)
    }

    @Test fun endedGoesToNextOrContinuesStartOver() {
        assertEquals(TimelinePoint.InProgramme(b, 0), tl.onEnded(a, 3600_000))
        // Start-over of D cut at 21:40 → reload D at same position.
        assertEquals(TimelinePoint.InProgramme(d, 40 * 60_000L), tl.onEnded(d, 40 * 60_000L))
        // Caught up with now → live.
        assertEquals(TimelinePoint.Live, tl.onEnded(d, 59 * 60_000L + 50_000))
    }
}
