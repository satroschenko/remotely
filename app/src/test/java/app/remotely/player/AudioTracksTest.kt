package app.remotely.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTracksTest {
    @Test fun namesFromZalaCodes() {
        assertEquals("Русский", AudioTracks.name("ru", 0))
        assertEquals("Английский", AudioTracks.name("eng", 1))
        assertEquals("Английский", AudioTracks.name("en-US", 1))
        assertEquals("FI", AudioTracks.name("fi", 1))
        assertEquals("Дорожка 2", AudioTracks.name(null, 1))
        assertEquals("Дорожка 3", AudioTracks.name("und", 2))
        assertEquals("EN", AudioTracks.short("eng", 1))
        assertEquals("2", AudioTracks.short("", 1))
    }

    @Test fun switchingGoesRound() {
        assertEquals(1, AudioTracks.next(2, 0))
        assertEquals(0, AudioTracks.next(2, 1))
        assertEquals(2, AudioTracks.next(3, 1))
        assertEquals(0, AudioTracks.next(0, 0))
    }

    @Test fun rememberedLanguageIsFoundAgain() {
        assertEquals(1, AudioTracks.preferred(listOf("ru", "en"), "en"))
        assertEquals(1, AudioTracks.preferred(listOf("rus", "en-GB"), "en"))
        assertNull(AudioTracks.preferred(listOf("ru", "en"), "de"))
        assertNull(AudioTracks.preferred(listOf("ru", "en"), null))
        assertNull(AudioTracks.preferred(listOf(null, null), "en"))
    }
}
