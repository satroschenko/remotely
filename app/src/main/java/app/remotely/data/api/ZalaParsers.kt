package app.remotely.data.api

import app.remotely.core.ZalaTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Pure parsing of portal responses. Tolerant to numbers-as-strings and missing fields. */
object ZalaParsers {
    val BAD_CREDENTIAL_CODES = setOf("70116101", "70116206")
    private val LOGIN_OK_CODES = setOf("0", "80106101", "80106102")
    private val SESSION_LOST_MARKERS = listOf("rebuildsession", "/login.jsp", "pcportalauth.jsp")

    fun isSessionLostUrl(url: String): Boolean = SESSION_LOST_MARKERS.any { url.contains(it) }

    fun json(body: String, url: String = ""): JsonObject {
        val t = body.trim()
        if (!t.startsWith("{")) throw SessionLostException("non-JSON from ${url.substringBefore('?').substringAfterLast('/')}")
        return Json.parseToJsonElement(t).jsonObject
    }

    fun JsonObject.str(key: String): String = when (val v = this[key]) {
        null -> ""
        is JsonPrimitive -> if (v.isString) v.content else v.content.takeIf { it != "null" } ?: ""
        else -> v.toString()
    }

    /** Portal double-escapes quotes in titles: `Т/с \"След\"`. */
    private fun JsonObject.text(key: String): String = str(key).replace("\\\"", "\"").replace("\\'", "'").trim()

    private fun JsonObject.code(): String = str("ReturnCode").ifEmpty { str("returncode") }.ifEmpty { str("returnCode") }

    private fun requireLoginOk(o: JsonObject) {
        val c = o.code()
        if (c in BAD_CREDENTIAL_CODES) throw BadCredentialsException(c)
        if (c !in LOGIN_OK_CODES) throw ZalaServerException(c, o.str("ErrorMsg"))
    }

    private fun requireOk(o: JsonObject) {
        val c = o.code()
        if (c != "0") throw ZalaServerException(c, o.str("errormsg").ifEmpty { o.str("ErrorMsg") })
    }

    fun loginEntry(body: String): LoginEntry {
        val o = json(body)
        requireLoginOk(o)
        val domain = o.str("serdomain")
        val port = o.str("serhttpsport").ifEmpty { "443" }
        if (domain.isEmpty()) throw ZalaServerException(o.code(), "no serdomain")
        return LoginEntry(
            epgBase = "https://$domain:$port",
            encryToken = o.str("EncryToken").ifEmpty { null },
            iemg = o.str("iemg"),
            authStr = o.str("AuthStr"),
            emgInfo = o.str("EmgInfo"),
        )
    }

    fun authenticator(body: String): String {
        val o = json(body)
        requireLoginOk(o)
        return o.str("authenticator").ifEmpty { throw ZalaServerException(o.code(), "no authenticator") }
    }

    fun userToken(body: String): UserToken {
        val o = json(body)
        requireLoginOk(o)
        val token = o.str("UserToken").ifEmpty { throw ZalaServerException(o.code(), "no token") }
        return UserToken(token, ZalaTime.parseMinsk(o.str("UserTokenExpiredTime")))
    }

    /** Returns the template frame id, e.g. "frame1255". */
    fun portalAuth(body: String): String {
        val o = json(body)
        requireLoginOk(o)
        val s = o.str("StypeUrl").ifEmpty { "1255" }
        return if (s.startsWith("frame")) s else "frame$s"
    }

    fun channels(body: String, epgBase: String): List<Channel> {
        val o = json(body)
        requireOk(o)
        val data = o["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val code = c.str("channelcode")
            val number = c.str("usermixno").toIntOrNull()
            if (code.isEmpty() || number == null) return@mapNotNull null
            Channel(
                code = code,
                name = c.text("channelname"),
                number = number,
                mixno = c.str("mixno"),
                columnCode = c.str("columncode").ifEmpty { ALL_CHANNELS_COLUMN },
                definition = c.str("definition").ifEmpty { "1" },
                logoUrl = logoUrl(c.str("posterimage"), epgBase),
                hasArchive = c.str("tvodavailable") == "1",
                nowTitle = c.text("prevuename"),
                nowStartMs = ZalaTime.parseUtc(c.str("utcbegintime")) ?: 0,
                nowEndMs = ZalaTime.parseUtc(c.str("utcendtime")) ?: 0,
            )
        }
    }

    /**
     * Channel categories; "Все каналы" (the full list) is left out. Lowest sortnum first: that puts the themes
     * (Детские, Спорт, Кино…) ahead of the service lists (HD, Общедоступный пакет).
     */
    fun columns(body: String): List<ChannelColumn> {
        val o = json(body)
        requireOk(o)
        val data = o["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val code = c.str("columncode")
            if (code.isEmpty() || code == ALL_CHANNELS_COLUMN || c.str("columntype") != "2") return@mapNotNull null
            ChannelColumn(code, c.text("columnname"), c.str("sortnum").toIntOrNull() ?: 0)
        }.sortedBy { it.sortNum }
    }

    const val ALL_CHANNELS_COLUMN = "0803"

    /** "../images/markurl/x.jpg" is relative to /iptvepg/frameNNNN/ → /iptvepg/images/markurl/x.jpg. */
    fun logoUrl(poster: String, epgBase: String): String? = when {
        poster.isBlank() -> null
        poster.startsWith("http") -> poster
        else -> "$epgBase/iptvepg/" + poster.removePrefix("../").removePrefix("/")
    }

    fun subscribedNumbers(body: String): Set<Int> {
        val o = json(body)
        requireOk(o)
        return o.str("data").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    fun programmes(body: String): List<Programme> {
        val o = json(body)
        requireOk(o)
        val data = o["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val start = ZalaTime.parseUtc(p.str("utcbegintime")) ?: return@mapNotNull null
            val end = ZalaTime.parseUtc(p.str("utcendtime")) ?: return@mapNotNull null
            Programme(
                code = p.str("prevuecode"),
                channelCode = p.str("channelcode"),
                title = p.text("prevuename"),
                description = p.text("description"),
                genre = p.text("genre"),
                startMs = start,
                endMs = end,
                recorded = p.str("istimeshift") == "1",
            )
        }.sortedBy { it.startMs }
    }

    fun archiveDepthMs(body: String): Long {
        val o = json(body)
        requireOk(o)
        val list = (o["data"] as? JsonObject)?.get("tvodchannellist") as? JsonArray
        val max = list?.mapNotNull { (it as? JsonObject)?.str("tvodtime")?.toLongOrNull() }?.maxOrNull()
        return (max ?: 172_800L) * 1000
    }

    /** sdk_queryblackout: `[["channelcode|id", "030000|205959,..."], ...]` → channel code → its daily breaks (UTC). */
    fun blackouts(body: String): Map<String, List<BlackoutWindow>> {
        val o = json(body)
        requireOk(o)
        val data = o["data"] as? JsonArray ?: return emptyMap()
        val out = mutableMapOf<String, List<BlackoutWindow>>()
        for (row in data) {
            val cells = (row as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() } ?: continue
            val code = cells.getOrNull(0)?.substringBefore('|')?.takeIf { it.isNotBlank() } ?: continue
            val windows = cells.getOrNull(1).orEmpty().split(',').mapNotNull { w ->
                val (b, e) = w.trim().split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
                BlackoutWindow(hhmmssToSec(b) ?: return@mapNotNull null, hhmmssToSec(e) ?: return@mapNotNull null)
            }
            if (windows.isNotEmpty()) out[code] = out[code].orEmpty() + windows
        }
        return out
    }

    private fun hhmmssToSec(t: String): Int? {
        if (t.length != 6 || !t.all { it.isDigit() }) return null
        return t.substring(0, 2).toInt() * 3600 + t.substring(2, 4).toInt() * 60 + t.substring(4, 6).toInt()
    }

    fun liveUrl(body: String): String {
        val o = json(body)
        requireOk(o)
        val first = (o["data"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ZalaServerException("empty", "no playurl")
        return first.str("httpsplayurl").ifEmpty { first.str("playurl") }
            .ifEmpty { throw ZalaServerException("empty", "no playurl") }
    }

    private val JS_PAIR = Regex("""(\w+)\s*:\s*"([^"]*)"""")

    /** get_authTvodresult returns a JS object literal with unquoted keys. Returns authid. */
    fun tvodAuthId(body: String): String {
        val t = body.trim()
        if (!t.startsWith("{")) throw SessionLostException("non-object from get_authTvodresult")
        val m = JS_PAIR.findAll(t).associate { it.groupValues[1] to it.groupValues[2] }
        val code = m["returnCode"] ?: ""
        if (code != "0") throw ArchiveUnavailableException(code)
        return m["authid"]?.takeIf { it.isNotEmpty() } ?: throw ArchiveUnavailableException("noauthid")
    }

    private val TVOD_URL = Regex("""var\s+tvodPlayUrl\s*=\s*"([^"]+)"""")

    fun tvodPlayUrl(html: String): String =
        TVOD_URL.find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: if (html.contains("rebuildsession")) throw SessionLostException("tvod page")
            else throw ArchiveUnavailableException("nourl")

    @Suppress("unused")
    private fun JsonElement.asObj() = this as? JsonObject
}
