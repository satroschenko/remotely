package app.remotely.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZalaApiTest {
    private val server = MockWebServer()
    private val jar = MemoryCookieJar()
    private lateinit var base: String
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return routes.entries.firstOrNull { path.endsWith(it.key) }?.value?.invoke(request)
                    ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setBody(body)

    private fun loginRoutes(step3: String = Fixtures.LOGIN3_OK) {
        // Entry points the client at our mock server as the EPG host.
        val host = server.hostName; val port = server.port
        routes["/mobilelogin.jsp"] = { json("""{"ReturnCode":"0","serdomain":"$host","serhttpsport":"$port","EncryToken":"E","iemg":"0","AuthStr":"","EmgInfo":""}""") }
        routes["/getauthenticator.jsp"] = { json(Fixtures.LOGIN2_OK) }
        routes["/mobilegetusertoken.jsp"] = { json(step3) }
        routes["/mobileportalauth.jsp"] = { json(Fixtures.LOGIN4_OK) }
        routes["/frameset_builder.html"] = { MockResponse().setBody("<html/>").addHeader("Set-Cookie", "JSESSIONID=S1; Path=/iptvepg") }
    }

    @Test fun badPasswordIsReported() = runBlocking {
        val httpApi = httpVariant()
        loginRoutes(step3 = Fixtures.LOGIN3_BAD_PWD)
        assertThrows(BadCredentialsException::class.java) { runBlocking { httpApi.login("user", "bad") } }
        Unit
    }

    @Test fun fullFlowOverHttp() = runBlocking {
        val httpApi = httpVariant()
        loginRoutes()
        val s = httpApi.login("user", "p@ss word")
        assertEquals("TOKEN1", s.userToken)
        assertTrue(s.frameBase.endsWith("/iptvepg/frame1255"))
        val reqs = (1..server.requestCount).map { server.takeRequest() }
        val auth = reqs.first { it.path!!.contains("getauthenticator") }
        assertEquals("p@ss word", auth.requestUrl!!.queryParameter("userpwd"))

        routes["/sdk_getchannellist.jsp"] = { json(Fixtures.CHANNELS) }
        routes["/sdk_getbatchtvplayurl.jsp"] = { json(Fixtures.PLAYURL) }
        val chans = httpApi.channels(s)
        assertEquals(2, chans.size)
        httpApi.liveUrl(s, chans[1])
        val post = server.takeRequest().let { server.takeRequest() }
        assertEquals("/iptvepg/frame1255/action/sdk_getbatchtvplayurl.jsp", post.path)
        assertEquals("S1", post.getHeader("Cookie")!!.substringAfter("JSESSIONID="))
        val form = post.body.readUtf8()
        assertTrue(form.contains("channelcodes=ch09031805520275474789"))
        assertTrue(form.contains("channelnostr=3"))
    }

    @Test fun sessionLostRedirectIsDetected() = runBlocking {
        val httpApi = httpVariant()
        loginRoutes()
        val s = httpApi.login("user", "x")
        routes["/sdk_getchannellist.jsp"] = { MockResponse().setResponseCode(302).addHeader("Location", "/iptvepg/function/rebuildsessionrequest.jsp?frameurl=x") }
        routes["/rebuildsessionrequest.jsp"] = { MockResponse().setBody(Fixtures.REBUILD_SESSION_HTML) }
        assertThrows(SessionLostException::class.java) { runBlocking { httpApi.channels(s) } }
        Unit
    }

    @Test fun archiveTwoSteps() = runBlocking {
        val httpApi = httpVariant()
        loginRoutes()
        val s = httpApi.login("user", "x")
        routes["/get_authTvodresult.jsp"] = { json(Fixtures.TVOD_AUTH_OK) }
        routes["/channel_tvod_play.jsp"] = { json(Fixtures.TVOD_PAGE) }
        val ch = ZalaParsers.channels(Fixtures.CHANNELS, s.epgBase)[1]
        val url = httpApi.archiveUrl(s, ch, "00000000070072979523")
        assertTrue(url.contains("programbegin="))
    }

    /** Same API but the login entry advertises a plain-http EPG host (MockWebServer). */
    private fun httpVariant(): ZalaApi {
        val host = server.hostName; val port = server.port
        return ZalaApi(
                zalaHttpClient(jar).newBuilder().addInterceptor { chain ->
                    val req = chain.request()
                    val url = if (req.url.isHttps) req.url.newBuilder().scheme("http").build() else req.url
                    chain.proceed(req.newBuilder().url(url).build())
                }.build(),
                jar,
                entryHosts = listOf("http://$host:$port"),
            )
    }

}
