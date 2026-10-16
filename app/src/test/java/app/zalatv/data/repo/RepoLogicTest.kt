package app.zalatv.data.repo

import app.zalatv.core.ZalaTime
import app.zalatv.data.api.Channel
import app.zalatv.data.api.Programme
import app.zalatv.data.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoLogicTest {
    private fun ch(code: String, n: Int, archive: Boolean = true) =
        Channel(code, code.uppercase(), n, "$n", "0803", "1", null, archive)

    private val snap = ChannelSnapshot(listOf(ch("c", 3), ch("a", 1), ch("b", 2), ch("x", 9)), setOf(1, 2, 3))

    @Test fun subscribedSortedByNumber() {
        val l = ChannelArranger.arrange(snap, Settings())
        assertEquals(listOf("a", "b", "c"), l.all.map { it.code })
    }

    @Test fun emptySubscriptionShowsAll() {
        val l = ChannelArranger.arrange(snap.copy(subscribed = emptySet()), Settings())
        assertEquals(4, l.all.size)
    }

    @Test fun hiddenOrderAndFavorites() {
        val s = Settings(hidden = setOf("b"), order = listOf("c"), favorites = listOf("c", "b", "zz", "a"))
        val l = ChannelArranger.arrange(snap, s)
        assertEquals(listOf("c", "a"), l.all.map { it.code })
        assertEquals(listOf("c", "a"), l.favorites.map { it.code }) // hidden/unknown dropped
        assertEquals(listOf("c", "a", "b"), l.manageable.map { it.code })
    }

    private val now = ZalaTime.parseMinsk("2026.10.15 22:00:00")!!

    @Test fun daysLimitedByDepth() {
        assertEquals(listOf("Позавчера", "Вчера", "Сегодня", "Завтра"), EpgLogic.days(now, EpgLogic.DEFAULT_DEPTH_MS).map { it.label })
        assertEquals(listOf("Вчера", "Сегодня", "Завтра"), EpgLogic.days(now, 24 * 3600_000L).map { it.label })
    }

    private fun p(start: String, end: String) =
        Programme("p$start", "a", "T", "", "", ZalaTime.parseMinsk("2026.10.$start")!!, ZalaTime.parseMinsk("2026.10.$end")!!, true)

    @Test fun currentNextAndRecording() {
        val list = listOf(p("15 21:00:00", "15 21:30:00"), p("15 21:30:00", "15 22:30:00"), p("15 22:30:00", "15 23:00:00"))
        assertEquals(list[1], EpgLogic.current(list, now))
        assertEquals(list[2], EpgLogic.next(list, now))
        val c = ch("a", 1)
        assertTrue(EpgLogic.canPlayRecording(list[0], c, now, EpgLogic.DEFAULT_DEPTH_MS))
        assertTrue(EpgLogic.canPlayRecording(list[1], c, now, EpgLogic.DEFAULT_DEPTH_MS)) // start-over
        assertFalse(EpgLogic.canPlayRecording(list[2], c, now, EpgLogic.DEFAULT_DEPTH_MS)) // future
        assertFalse(EpgLogic.canPlayRecording(list[0], ch("a", 1, archive = false), now, EpgLogic.DEFAULT_DEPTH_MS))
        assertFalse(EpgLogic.canPlayRecording(p("13 20:00:00", "13 21:00:00"), c, now, EpgLogic.DEFAULT_DEPTH_MS))
        assertEquals(0.5f, EpgLogic.progress(list[1].startMs, list[1].endMs, now), 0.001f)
    }
}
