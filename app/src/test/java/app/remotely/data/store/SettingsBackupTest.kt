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
        fontSize = FontSize.XLARGE, quality = Quality.P720, autostart = true, subtitles = true, genreColors = true, autoCheckUpdates = false,
        announcedUpdate = "1.20", audio = mapOf("ch7" to "en"), showBalance = false,
    )

    @Test fun roundTripKeepsSettingsButNotDeviceState() {
        val text = SettingsBackup.encode(mine, "1.21", 1_000L)
        // A phone: its own quality, font and no autostart stay.
        val current = Settings(lastChannel = "chX", announcedUpdate = "1.19", quality = Quality.P360, fontSize = FontSize.MEDIUM, autostart = false)
        val loaded = SettingsBackup.decode(text, current)!!
        assertEquals(
            mine.copy(lastChannel = "chX", announcedUpdate = "1.19", quality = Quality.P360, fontSize = FontSize.MEDIUM, autostart = false),
            loaded.settings,
        )
        assertEquals(1_000L, loaded.savedAtMs)
        assertEquals("1.21", loaded.appVersion)
    }

    @Test fun olderBackupsLoadWithoutTheDeviceSettings() {
        val text = """{"format":"remotely-settings","version":1,"settings":{"favorites":["ch1"],"fontSize":"XLARGE","quality":"P720","autostart":true}}"""
        val cur = Settings(quality = Quality.P360)
        assertEquals(cur.copy(favorites = listOf("ch1")), SettingsBackup.decode(text, cur)!!.settings)
    }

    @Test fun soundChoicesAreChecked() {
        val text = """{"format":"remotely-settings","settings":{"audio":{"ch1":"en","ch2":1,"ch3":"","ch4":"${"x".repeat(40)}"," ":"ru"}}}"""
        assertEquals(mapOf("ch1" to "en"), SettingsBackup.decode(text, Settings())!!.settings.audio)
        val bad = """{"format":"remotely-settings","settings":{"audio":["en"]}}"""
        assertEquals(mine.audio, SettingsBackup.decode(bad, mine)!!.settings.audio)
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

    @Test fun breakStopTravelsBetweenDevices() {
        val text = SettingsBackup.encode(Settings(breakStopMinutes = 20), "1.24", 1L)
        assertEquals(20, SettingsBackup.decode(text, Settings())?.settings?.breakStopMinutes)
    }

    @Test fun brokenBreakStopKeepsTheCurrentValue() {
        for (bad in listOf("\"x\"", "-5", "99999", "1.5", "true", "null")) {
            val text = """{"format":"remotely-settings","settings":{"breakStopMinutes":$bad}}"""
            assertEquals(bad, 30, SettingsBackup.decode(text, Settings(breakStopMinutes = 30))?.settings?.breakStopMinutes)
        }
    }
}
