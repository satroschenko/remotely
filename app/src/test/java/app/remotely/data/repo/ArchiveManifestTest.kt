package app.remotely.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.io.encoding.Base64

/** Real answers of 31.10.2026 for РТР Беларусь, «Юмор! Юмор!! Юмор!!!» 11:55–14:00 UTC (tokens replaced). */
class ArchiveManifestTest {
    private fun res(name: String) = javaClass.getResource("/archive/$name")!!.readText()
    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    private val start = utc("2026-10-31T11:55:00Z")
    private val end = utc("2026-10-31T14:00:00Z")
    private val w1 = ArchiveManifest.Window("https://itv1.tv.zala.by:6430/cpsp777/2/ch1/manifest.mpd?AuthInfo=X&a=1", res("tstv1.mpd"))
    private val w2 = ArchiveManifest.Window("https://itv2.tv.zala.by:6430/cpsp777/2/ch1/manifest.mpd?AuthInfo=X&a=2", res("tstv2.mpd"))

    @Test fun cutRecordingIsSeenAsShort() {
        assertEquals(702_000L, ArchiveManifest.durationMs(res("tvod_short.mpd")))
        assertNull(ArchiveManifest.durationMs("not xml"))
        assertNull(ArchiveManifest.durationMs("<MPD/>"))
    }

    @Test fun timeshiftAddressLikeTheOfficialClient() {
        val live = "https://itv.tv.zala.by:6430/cpsp777/2/ch1/manifest.mpd?AuthInfo=X&virtualDomain=cpsp777.live_hls.zte.com&IASHttpSessionId=Y"
        assertEquals(
            "https://itv.tv.zala.by:6430/cpsp777/2/ch1/manifest.mpd?AuthInfo=X&virtualDomain=cpsp777.tstv_hls.zte.com&IASHttpSessionId=Y&playseek=20261031115500-",
            ArchiveManifest.timeshiftUrl(live, start),
        )
        assertNull(ArchiveManifest.timeshiftUrl("https://other/manifest.mpd", start))
    }

    @Test fun windowEndIsWhereTheNextOneStarts() {
        assertEquals(utc("2026-10-31T13:35:02.277Z"), ArchiveManifest.windowEndMs(w1.mpd))
    }

    @Test fun twoWindowsMakeTheWholeProgramme() {
        val mpd = ArchiveManifest.fromTimeshift(listOf(w1, w2), start, end)
        assertNotNull(mpd)
        mpd!!
        assertEquals(end - start, ArchiveManifest.durationMs(mpd))
        assertTrue(mpd.contains("type=\"static\""))
        assertTrue(!mpd.contains("availabilityStartTime") && !mpd.contains("timeShiftBufferDepth"))
        // A period per window, each with the server it came from.
        assertEquals(2, Regex("<Period ").findAll(mpd).count())
        assertTrue(mpd.contains("<BaseURL>https://itv1.tv.zala.by:6430/cpsp777/2/ch1/</BaseURL>"))
        assertTrue(mpd.contains("<BaseURL>https://itv2.tv.zala.by:6430/cpsp777/2/ch1/</BaseURL>"))
        assertTrue(mpd.contains("start=\"PT6002.277S\""))
        // Position 0 is the programme start: offset = 11:55:00 since 2017-02-22T16:00Z.
        assertTrue(mpd.contains("presentationTimeOffset=\"305668500000\""))
        // The second period starts after the first one's last segment — no segment twice.
        val segmentStarts = Regex("""<S [^>]*\bt="(\d+)"""").findAll(mpd).map { it.groupValues[1] }.toList()
        assertEquals(segmentStarts.size / 2, segmentStarts.toSet().size)
        assertEquals(2, segmentStarts.count { it == "305674502277" })
        // Nothing after the programme end.
        assertTrue(segmentStarts.all { it.toLong() < 305676000000 })
    }

    @Test fun oneWindowIsAsMuchAsItHas() {
        val mpd = ArchiveManifest.fromTimeshift(listOf(w1), start, end)!!
        assertEquals(utc("2026-10-31T13:35:02.277Z") - start, ArchiveManifest.durationMs(mpd))
    }

    @Test fun junkGivesNothing() {
        assertNull(ArchiveManifest.fromTimeshift(emptyList(), start, end))
        assertNull(ArchiveManifest.fromTimeshift(listOf(ArchiveManifest.Window("https://x/m.mpd", "<html>error</html>")), start, end))
        assertNull(ArchiveManifest.fromTimeshift(listOf(w1), end + 3_600_000, end + 7_200_000))
        assertNull(ArchiveManifest.withBase("<<<", "https://x/m.mpd"))
    }

    @Test fun downloadedRecordingKeepsItsServer() {
        val mpd = ArchiveManifest.withBase(res("tvod_short.mpd"), "https://itv3.tv.zala.by:6430/cpsp777/2/ch1/manifest.mpd?AuthInfo=X")!!
        assertTrue(mpd.contains("<BaseURL>https://itv3.tv.zala.by:6430/cpsp777/2/ch1/</BaseURL>"))
        val uri = ArchiveManifest.dataUri(mpd)
        assertTrue(uri.startsWith("data:application/dash+xml;base64,"))
        assertEquals(1, uri.count { it == ',' })
        assertEquals(mpd, String(Base64.decode(uri.substringAfter(',')), Charsets.UTF_8))
    }
}
