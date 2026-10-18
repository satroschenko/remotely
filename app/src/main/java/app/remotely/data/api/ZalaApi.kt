package app.remotely.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Cookie jar we can wipe on re-login. Keyed by host+name. */
class MemoryCookieJar : CookieJar {
    private val cookies = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies["${it.domain}|${it.path}|${it.name}"] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.values.removeIf { it.expiresAt < now }
        return cookies.values.filter { it.matches(url) }
    }

    fun clear() = cookies.clear()
}

fun zalaHttpClient(cookieJar: CookieJar): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .proxy(java.net.Proxy.NO_PROXY)
    .build()

/**
 * Stateless calls to the Zala (ZTE IPTV) portal. Session state lives in [SessionManager];
 * the HTTP session cookie lives in the client's cookie jar.
 */
class ZalaApi(
    private val client: OkHttpClient,
    private val cookieJar: MemoryCookieJar,
    private val entryHosts: List<String> = listOf("https://tv.zala.by:443", "https://slaveeas.tv.zala.by:443"),
) {
    private object C {
        const val TERMINAL_FLAG = "16"
        const val OS_TYPE = "2"
        const val CLIENT_ID = "zxyzxy"
        const val MAC = "000000000000"
    }

    private fun now() = System.currentTimeMillis().toString()

    private suspend fun get(url: HttpUrl): String = execute(Request.Builder().url(url).build())

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            if (ZalaParsers.isSessionLostUrl(finalUrl)) throw SessionLostException("redirect")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    private fun HttpUrl.Builder.terminal() = apply {
        addQueryParameter("TerminalFlag", C.TERMINAL_FLAG)
        addQueryParameter("TerminalOsType", C.OS_TYPE)
        addQueryParameter("iscors", "1")
        addQueryParameter("timestamp", now())
    }

    /** Full 4-step login. Throws [BadCredentialsException] for wrong login/password. */
    suspend fun login(login: String, password: String): Session {
        cookieJar.clear()
        val entry = loginEntry(login)
        val epg = entry.epgBase
        val encry = entry.encryToken ?: now()

        val auth = ZalaParsers.authenticator(get("$epg/iptvepg/datasource/getauthenticator.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("encrytoken", encry)
            .addQueryParameter("userid", login)
            .addQueryParameter("iemg", entry.iemg)
            .addQueryParameter("authStr", entry.authStr)
            .addQueryParameter("emgInfo", entry.emgInfo)
            .addQueryParameter("userpwd", password)
            .addQueryParameter("EpgServer", epg)
            .addQueryParameter("stbid", C.CLIENT_ID)
            .addQueryParameter("mac", C.MAC)
            .terminal().build()))

        val tokenUrl = "$epg/iptvepg/datasource/mobilegetusertoken.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("EncryToken", encry)
            .addQueryParameter("UserID", login)
            .addQueryParameter("EpgServer", epg)
            .addQueryParameter("clientId", C.CLIENT_ID)
            .addQueryParameter("Authenticator", auth)
            .terminal()
        if (entry.iemg == "1") {
            tokenUrl.addQueryParameter("AuthStr", entry.authStr).addQueryParameter("EmgInfo", entry.emgInfo)
        }
        val token = ZalaParsers.userToken(get(tokenUrl.build()))

        val portalUrl = "$epg/iptvepg/datasource/mobileportalauth.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("UserID", login)
            .addQueryParameter("UserToken", token.token)
            .addQueryParameter("STBID", C.CLIENT_ID)
            .terminal()
        if (entry.iemg == "1") {
            portalUrl.addQueryParameter("AuthStr", entry.authStr).addQueryParameter("EmgInfo", entry.emgInfo)
        }
        val frame = ZalaParsers.portalAuth(get(portalUrl.build()))
        val frameBase = "$epg/iptvepg/$frame"

        // Initializes the server-side portal session bound to our JSESSIONID.
        get("$frameBase/frameset_builder.html".toHttpUrl().newBuilder()
            .addQueryParameter("islogin", "1")
            .addQueryParameter("userToken", token.token)
            .addQueryParameter("lan", "ru").build())

        return Session(
            epgBase = epg,
            frameBase = frameBase,
            userToken = token.token,
            expiresAtMs = token.expiresAtMs ?: (System.currentTimeMillis() + 23 * 3600_000L),
        )
    }

    private suspend fun loginEntry(login: String): LoginEntry {
        var lastError: IOException? = null
        for (host in entryHosts) {
            try {
                return ZalaParsers.loginEntry(get("$host/iptvepg/datasource/mobilelogin.jsp".toHttpUrl().newBuilder()
                    .addQueryParameter("Action", "Login")
                    .addQueryParameter("UserID", login)
                    .terminal().build()))
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("no entry host")
    }

    suspend fun heartbeat(s: Session) {
        get("${s.epgBase}/iptvepg/heartbeat.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("timestamp", now()).build())
    }

    suspend fun channels(s: Session): List<Channel> = ZalaParsers.channels(
        get("${s.frameBase}/action/sdk_getchannellist.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("columncode", "0803").build()),
        s.epgBase,
    )

    suspend fun subscribedNumbers(s: Session): Set<Int> =
        ZalaParsers.subscribedNumbers(get("${s.frameBase}/action/sdk_getsubscribechannel.jsp".toHttpUrl()))

    suspend fun archiveDepthMs(s: Session): Long = ZalaParsers.archiveDepthMs(
        get("${s.epgBase}/iptvepg/frame1258/sdk_querytvodtstime.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("terminalflag", C.TERMINAL_FLAG)
            .addQueryParameter("mediaservices", "2")
            .addQueryParameter("refreshchannel", "1").build()),
    )

    suspend fun programmes(s: Session, channelCode: String, fromMs: Long, toMs: Long): List<Programme> =
        ZalaParsers.programmes(get("${s.epgBase}/iptvepg/frame1258/sdk_getprevuellist.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("channelcode", channelCode)
            .addQueryParameter("utcbegintime", app.remotely.core.ZalaTime.formatUtc(fromMs))
            .addQueryParameter("utcendtime", app.remotely.core.ZalaTime.formatUtc(toMs - 1000)).build()))

    suspend fun liveUrl(s: Session, ch: Channel): String = ZalaParsers.liveUrl(execute(
        Request.Builder()
            .url("${s.frameBase}/action/sdk_getbatchtvplayurl.jsp")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(FormBody.Builder()
                .add("channelcodes", ch.code)
                .add("columncodes", ch.columnCode)
                .add("channelnostr", ch.mixno)
                .add("definition", ch.definition)
                .add("jitpdrmType", "")
                .add("isSpecial", "0")
                .add("profile", "")
                .build())
            .build(),
    ))

    /** Recording of a programme (also works for the one on air: manifest ends at "now"). */
    suspend fun archiveUrl(s: Session, ch: Channel, programmeCode: String): String {
        val authId = ZalaParsers.tvodAuthId(get("${s.frameBase}/action/get_authTvodresult.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("columnid", ch.columnCode)
            .addQueryParameter("channelid", ch.code)
            .addQueryParameter("contentcode", programmeCode)
            .addQueryParameter("mixno", ch.number.toString())
            .addQueryParameter("leefocus", "0").build()))
        return ZalaParsers.tvodPlayUrl(get("${s.frameBase}/channel_tvod_play.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("authid", authId)
            .addQueryParameter("contentcode", programmeCode)
            .addQueryParameter("channelcode", ch.code)
            .addQueryParameter("columncode", ch.columnCode)
            .addQueryParameter("definition", ch.definition)
            .addQueryParameter("leefocus", "0")
            .addQueryParameter("jitpdrmType", "NO").build()))
    }
}
