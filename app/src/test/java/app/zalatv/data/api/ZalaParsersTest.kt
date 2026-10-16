package app.zalatv.data.api

import app.zalatv.core.ZalaTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZalaParsersTest {
    private val epg = "https://itvepg177.tv.zala.by:443"

    @Test fun loginEntry() {
        val e = ZalaParsers.loginEntry(Fixtures.LOGIN1_OK)
        assertEquals(epg, e.epgBase)
        assertEquals("ENC123", e.encryToken)
    }

    @Test fun unknownUserIsBadCredentials() {
        val ex = assertThrows(BadCredentialsException::class.java) { ZalaParsers.loginEntry(Fixtures.LOGIN1_NO_USER) }
        assertEquals("70116101", ex.code)
    }

    @Test fun wrongPasswordIsBadCredentials() {
        assertThrows(BadCredentialsException::class.java) { ZalaParsers.userToken(Fixtures.LOGIN3_BAD_PWD) }
    }

    @Test fun userTokenAndExpiry() {
        val t = ZalaParsers.userToken(Fixtures.LOGIN3_OK)
        assertEquals("TOKEN1", t.token)
        assertEquals(ZalaTime.parseMinsk("2026.10.16 22:28:30"), t.expiresAtMs)
        assertEquals("AUTHHEX", ZalaParsers.authenticator(Fixtures.LOGIN2_OK))
        assertEquals("frame1255", ZalaParsers.portalAuth(Fixtures.LOGIN4_OK))
        assertEquals("frame1255", ZalaParsers.portalAuth("""{"ReturnCode":"0","StypeUrl":"1255"}"""))
    }

    @Test fun channelsSkipInvalidAndResolveLogo() {
        val list = ZalaParsers.channels(Fixtures.CHANNELS, epg)
        assertEquals(2, list.size)
        val ont = list[1]
        assertEquals("ОНТ", ont.name)
        assertEquals(2, ont.number)
        assertEquals("3", ont.mixno)
        assertEquals("1", ont.definition)
        assertTrue(ont.hasArchive)
        assertEquals("$epg/iptvepg/images/markurl/mark_2.jpg", ont.logoUrl)
        assertEquals("Чудо (12+)", ont.nowTitle)
        assertEquals(ZalaTime.parseUtc("2026.10.15 18:35:00"), ont.nowStartMs)
    }

    @Test fun escapedQuotesAreCleaned() {
        val body = "{\"returncode\":\"0\",\"data\":[{\"channelname\":\"X\",\"usermixno\":1,\"channelcode\":\"c\",\"prevuename\":\"Т/с \\\\\\\"След\\\\\\\" (16+)\"}]}"
        assertEquals("Т/с \"След\" (16+)", ZalaParsers.channels(body, epg)[0].nowTitle)
    }

    @Test fun logoVariants() {
        assertNull(ZalaParsers.logoUrl("", epg))
        assertEquals("http://x/y.png", ZalaParsers.logoUrl("http://x/y.png", epg))
    }

    @Test fun subscribed() = assertEquals(setOf(149, 1, 2, 7), ZalaParsers.subscribedNumbers(Fixtures.SUBSCRIBED))

    @Test fun programmesSortedWithUtcTimes() {
        val p = ZalaParsers.programmes(Fixtures.EPG)
        assertEquals(listOf("Наши новости (12+)", "Второе"), p.map { it.title })
        assertEquals(ZalaTime.parseUtc("2026.10.14 13:00:00"), p[0].startMs)
        assertFalse(p[0].recorded)
        assertTrue(p[1].recorded)
        assertEquals("00000000070072979524", p[1].code)
    }

    @Test fun archiveDepthIsMax() = assertEquals(172_800_000L, ZalaParsers.archiveDepthMs(Fixtures.TVOD_DEPTH))

    @Test fun liveUrlPrefersHttps() {
        assertTrue(ZalaParsers.liveUrl(Fixtures.PLAYURL).contains("AuthInfo=B"))
        assertThrows(ZalaServerException::class.java) { ZalaParsers.liveUrl(Fixtures.PLAYURL_NULL_PARAM) }
    }

    @Test fun tvodAuth() {
        assertEquals("1344173021", ZalaParsers.tvodAuthId(Fixtures.TVOD_AUTH_OK))
        val ex = assertThrows(ArchiveUnavailableException::class.java) { ZalaParsers.tvodAuthId(Fixtures.TVOD_AUTH_LOCKED) }
        assertEquals("98", ex.code)
    }

    @Test fun tvodPlayUrlExtracted() {
        val u = ZalaParsers.tvodPlayUrl(Fixtures.TVOD_PAGE)
        assertTrue(u.startsWith("https://itv2.tv.zala.by:6430/x/manifest.mpd?AuthInfo=C"))
        assertTrue(u.endsWith("programend=20261014150000+00"))
    }

    @Test fun sessionLostDetection() {
        assertThrows(SessionLostException::class.java) { ZalaParsers.tvodPlayUrl(Fixtures.REBUILD_SESSION_HTML) }
        assertThrows(SessionLostException::class.java) { ZalaParsers.channels(Fixtures.REBUILD_SESSION_HTML, epg) }
        assertTrue(ZalaParsers.isSessionLostUrl("https://h/iptvepg/function/rebuildsessionrequest.jsp?frameurl=x"))
        assertFalse(ZalaParsers.isSessionLostUrl("https://h/iptvepg/frame1255/action/sdk_getchannellist.jsp"))
    }
}
