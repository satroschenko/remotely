package app.remotely.data.repo

import app.remotely.data.api.OperatorMessage
import app.remotely.data.api.ZalaParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorMessageTest {
    private val day = 24 * 3600_000L

    @Test fun readsTheRussianMessage() {
        val body = "Portal_Msg_En=Dear subscribers\nPortal_Msg_Ru=Уважаемые абоненты! Новый канал (53 кнопка).  \nPortal_Msg_style=color:#fff\n"
        val m = ZalaParsers.operatorMessage(body, "Fri, 30 Oct 2026 10:04:45 GMT")
        assertEquals("Уважаемые абоненты! Новый канал (53 кнопка).", m?.text)
        assertEquals(1_793_354_685_000L, m?.publishedAtMs)
    }

    @Test fun escapesAndNoDate() {
        val m = ZalaParsers.operatorMessage("Portal_Msg_Ru=\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442", null)
        assertEquals("Привет", m?.text)
        assertNull(m?.publishedAtMs)
    }

    @Test fun nothingToShow() {
        assertNull(ZalaParsers.operatorMessage("Portal_Msg_Ru=   \n", null))
        assertNull(ZalaParsers.operatorMessage("Other=1", null))
        assertNull(ZalaParsers.operatorMessage("Portal_Msg_Ru=Текст", "not a date")?.publishedAtMs)
    }

    @Test fun fiveTimesThenUntilANewOne() {
        val m = OperatorMessage("Текст", publishedAtMs = 0)
        var seen = OperatorMessageLogic.Seen(null, 0)
        repeat(5) {
            assertTrue(OperatorMessageLogic.shouldShow(m, nowMs = day, seen = seen, enabled = true))
            seen = OperatorMessageLogic.afterShown(m, seen)
        }
        assertFalse(OperatorMessageLogic.shouldShow(m, day, seen, true))
        val other = OperatorMessage("Другой текст", publishedAtMs = 0)
        assertTrue(OperatorMessageLogic.shouldShow(other, day, seen, true))
        assertEquals(1, OperatorMessageLogic.afterShown(other, seen).shows)
    }

    @Test fun oldOrSwitchedOffIsNotShown() {
        val seen = OperatorMessageLogic.Seen(null, 0)
        assertFalse(OperatorMessageLogic.shouldShow(OperatorMessage("Текст", 0), nowMs = 8 * day, seen = seen, enabled = true))
        assertTrue(OperatorMessageLogic.shouldShow(OperatorMessage("Текст", null), nowMs = 8 * day, seen = seen, enabled = true))
        assertFalse(OperatorMessageLogic.shouldShow(OperatorMessage("Текст", 0), nowMs = day, seen = seen, enabled = false))
    }
}
