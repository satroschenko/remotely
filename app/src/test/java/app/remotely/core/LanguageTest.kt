package app.remotely.core

import app.remotely.data.api.ZalaParsers
import app.remotely.data.repo.GenreNames
import app.remotely.data.store.Settings
import app.remotely.data.store.SettingsBackup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTest {
    @After fun back() { Lang.current = AppLanguage.RU }

    @Test fun textFollowsTheLanguage() {
        assertEquals("Сегодня", ZalaTime.dayLabel(java.time.LocalDate.of(2026, 11, 5), java.time.LocalDate.of(2026, 11, 5)))
        Lang.current = AppLanguage.BE
        assertEquals("Сёння", ZalaTime.dayLabel(java.time.LocalDate.of(2026, 11, 5), java.time.LocalDate.of(2026, 11, 5)))
        Lang.current = AppLanguage.EN
        assertEquals("Yesterday", ZalaTime.dayLabel(java.time.LocalDate.of(2026, 11, 4), java.time.LocalDate.of(2026, 11, 5)))
    }

    @Test fun genresTranslatedWordByWordUnknownKept() {
        assertEquals("Криминал, Детектив", GenreNames.translate("Криминал, Детектив", AppLanguage.RU))
        assertEquals("Крымінал, Дэтэктыў, Нешта", GenreNames.translate("Криминал, Детектив, Нешта", AppLanguage.BE))
        assertEquals("History, Archaeology, Information", GenreNames.translate("История, Археология, Информация", AppLanguage.EN))
        assertEquals("", GenreNames.translate("", AppLanguage.EN))
    }

    @Test fun operatorMessageInTheLanguageOrRussian() {
        val body = "Portal_Msg_Ru=Привет\nPortal_Msg_Bel=Прывітанне\nPortal_Msg_En=\n"
        assertEquals("Привет", ZalaParsers.operatorMessage(body, null, "ru")?.text)
        assertEquals("Прывітанне", ZalaParsers.operatorMessage(body, null, "be")?.text)
        assertEquals("Привет", ZalaParsers.operatorMessage(body, null, "en")?.text)
    }

    @Test fun languageGoesIntoTheServerBackup() {
        val json = SettingsBackup.encode(Settings(language = AppLanguage.BE), "1.28", 1L)
        assertEquals(AppLanguage.BE, SettingsBackup.decode(json, Settings())?.settings?.language)
        assertEquals(AppLanguage.EN, SettingsBackup.decode(json.replace("\"BE\"", "\"XX\""), Settings(language = AppLanguage.EN))?.settings?.language)
    }
}
