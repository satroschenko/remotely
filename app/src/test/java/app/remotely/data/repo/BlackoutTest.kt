package app.remotely.data.repo

import app.remotely.data.api.BlackoutWindow
import app.remotely.data.api.ZalaParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BlackoutTest {
    private fun utc(t: String) = Instant.parse("2026-10-29T${t}Z").toEpochMilli()

    @Test fun parsesTheServerAnswer() {
        val body = """{"timestamp":"20261014210141","errormsg":"","returncode":"0","data":[["ch1|1364","030000|205959"],["ch2|1364","220000|015959,100000|103000"]]}"""
        val m = ZalaParsers.blackouts(body)
        assertEquals(listOf(BlackoutWindow(3 * 3600, 20 * 3600 + 59 * 60 + 59)), m["ch1"])
        assertEquals(2, m["ch2"]!!.size)
        assertEquals(emptyMap<String, List<BlackoutWindow>>(), ZalaParsers.blackouts("""{"returncode":"0","data":[]}"""))
    }

    @Test fun insideTheDaytimeWindowUntilItsEnd() {
        val w = listOf(BlackoutWindow(3 * 3600, 20 * 3600 + 59 * 60 + 59)) // 06:00–23:59:59 in Minsk
        assertEquals(utc("21:00:00"), BlackoutLogic.until(w, utc("10:22:03")))
        assertEquals(utc("21:00:00"), BlackoutLogic.until(w, utc("03:00:00")))
        assertEquals(utc("21:00:00"), BlackoutLogic.until(w, utc("20:59:59")))
        assertNull(BlackoutLogic.until(w, utc("21:00:00")))
        assertNull(BlackoutLogic.until(w, utc("02:59:59")))
    }

    @Test fun windowOverMidnight() {
        val w = listOf(BlackoutWindow(22 * 3600, 1 * 3600 + 59 * 60 + 59))
        assertEquals(Instant.parse("2026-10-30T02:00:00Z").toEpochMilli(), BlackoutLogic.until(w, utc("23:30:00")))
        assertEquals(utc("02:00:00"), BlackoutLogic.until(w, utc("00:30:00")))
        assertNull(BlackoutLogic.until(w, utc("12:00:00")))
    }
}
