package app.remotely.data.store

import app.remotely.data.api.ZalaParsers
import app.remotely.ui.theme.FontSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder

class SettingsBackupTest {
    private val mine = Settings(
        favorites = listOf("ch1", "ch2"), hidden = setOf("ch9"), order = listOf("ch2", "ch1"), lastChannel = "ch1",
        fontSize = FontSize.XLARGE, quality = Quality.P720, autostart = true, subtitles = true, autoCheckUpdates = false,
        announcedUpdate = "1.20",
    )

    @Test fun roundTripKeepsSettingsButNotDeviceState() {
        val text = SettingsBackup.encode(mine, "1.21", 1_000L)
        val current = Settings(lastChannel = "chX", announcedUpdate = "1.19")
        val loaded = SettingsBackup.decode(text, current)!!
        assertEquals(mine.copy(lastChannel = "chX", announcedUpdate = "1.19"), loaded.settings)
        assertEquals(1_000L, loaded.savedAtMs)
        assertEquals("1.21", loaded.appVersion)
    }

    @Test fun serverEncodingIsUndone() {
        val text = SettingsBackup.encode(mine, "1.21", 1L)
        val wrapped = SettingsBackup.wrap(text)
        assertEquals(text, SettingsBackup.unwrap(wrapped))
        // The portal returns it encoded once more.
        assertEquals(text, SettingsBackup.unwrap(URLEncoder.encode(wrapped, "UTF-8")))
        assertEquals(text, SettingsBackup.unwrap(text))
    }

    @Test fun garbageIsNotOurs() {
        val cur = Settings()
        for (junk in listOf("", "null", "[]", "{", "not json", "{\"a\":1}", "{\"format\":\"other\",\"settings\":{}}",
            "{\"format\":\"remotely-settings\"}", "{\"format\":\"remotely-settings\",\"settings\":[1]}", "x".repeat(70_000))) {
            assertNull(junk, SettingsBackup.decode(junk, cur))
        }
        assertNull(SettingsBackup.unwrap("hello"))
        assertNull(SettingsBackup.unwrap("%zz%"))
        assertNull(SettingsBackup.unwrap("%2"))
    }

    @Test fun brokenFieldsKeepCurrentValues() {
        val text = """{"format":"remotely-settings","version":99,"savedAt":"yesterday","settings":{
            "favorites":"ch1","hidden":[1,null,{"a":1},"",["x"],"ok"],"order":[" ch3 ","ch3","ch4"],
            "fontSize":"HUGE","quality":720,"subtitles":"yes","autostart":null,"future":{"x":[1,2]}}}"""
        val loaded = SettingsBackup.decode(text, mine)
        assertNotNull(loaded)
        val s = loaded!!.settings
        assertEquals(mine.favorites, s.favorites)
        assertEquals(setOf("ok"), s.hidden)
        assertEquals(listOf("ch3", "ch4"), s.order)
        assertEquals(mine.fontSize, s.fontSize)
        assertEquals(mine.quality, s.quality)
        assertEquals(mine.subtitles, s.subtitles)
        assertEquals(mine.autostart, s.autostart)
        assertEquals(mine.autoCheckUpdates, s.autoCheckUpdates)
        assertNull(loaded.savedAtMs)
    }

    @Test fun longListsAndCodesAreCut() {
        val codes = (1..3000).joinToString(",") { "\"c$it\"" }
        val text = """{"format":"remotely-settings","settings":{"order":[$codes,"${"z".repeat(200)}"]}}"""
        assertEquals(2000, SettingsBackup.decode(text, Settings())!!.settings.order.size)
    }

    @Test fun userDataReply() {
        assertEquals("%257B%257D", ZalaParsers.userData("""{"returncode":"0","errormsg":"success","datavalue":"%257B%257D"}"""))
        assertNull(ZalaParsers.userData("""{"returncode":"1","errormsg":""}"""))
        assertNull(ZalaParsers.userData("""{"returncode":"0","datavalue":""}"""))
    }
}
