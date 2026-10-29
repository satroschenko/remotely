package app.remotely.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleChoiceTest {
    @Test fun lastRussianTrackCarriesTheText() {
        assertEquals(1, SubtitleChoice.pick(listOf("ru", "ru")))
        assertEquals(1, SubtitleChoice.pick(listOf("ru", "ru", "un")))
        assertEquals(0, SubtitleChoice.pick(listOf("ru")))
    }

    @Test fun otherwiseANamedLanguageThenTheFirst() {
        assertEquals(0, SubtitleChoice.pick(listOf("en", "un")))
        assertEquals(0, SubtitleChoice.pick(listOf(null, "un")))
        assertNull(SubtitleChoice.pick(emptyList()))
    }

    @Test fun fixesTheBrokenShortI() {
        assertEquals("И вот сейчас такой", SubtitleChoice.fixText("И вот сеѝчас такоѝ"))
    }
}
