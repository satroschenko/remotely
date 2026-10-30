package app.remotely.data.store

import app.remotely.ui.theme.FontSize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Settings kept in the user's data cell on the Zala server, saved and loaded only on demand.
 *
 * Our own JSON: `{"format":"remotely-settings","version":1,"app":"1.21","savedAt":ms,"settings":{...}}`.
 * Reading never trusts the cell: each setting is read on its own, a missing or broken one keeps the
 * current value and unknown keys are skipped, so a newer app may add fields and an older one still loads.
 */
object SettingsBackup {
    const val FORMAT = "remotely-settings"
    const val VERSION = 1
    /** The cell held 64 KB in a test; 133 channels in every list take about 15 KB. */
    const val MAX_CHARS = 60_000
    private const val MAX_CODES = 2_000
    private const val MAX_CODE_LENGTH = 64

    data class Loaded(val settings: Settings, val savedAtMs: Long?, val appVersion: String?)

    /** Favourites, hidden channels, channel order and preferences; not the last channel or update bookkeeping. */
    fun encode(s: Settings, appVersion: String, nowMs: Long): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("app", appVersion)
        put("savedAt", nowMs)
        putJsonObject("settings") {
            putJsonArray("favorites") { s.favorites.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("hidden") { s.hidden.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("order") { s.order.forEach { add(JsonPrimitive(it)) } }
            put("fontSize", s.fontSize.name)
            put("quality", s.quality.name)
            put("subtitles", s.subtitles)
            put("autostart", s.autostart)
            put("autoCheckUpdates", s.autoCheckUpdates)
        }
    }.toString()

    /** [current] with the saved values over it; null when [text] is not our backup at all. */
    fun decode(text: String, current: Settings): Loaded? {
        if (text.length > MAX_CHARS) return null
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if (root.string("format") != FORMAT) return null
        val s = root["settings"] as? JsonObject ?: return null
        val loaded = current.copy(
            favorites = s.codes("favorites") ?: current.favorites,
            hidden = s.codes("hidden")?.toSet() ?: current.hidden,
            order = s.codes("order") ?: current.order,
            fontSize = s.enum<FontSize>("fontSize") ?: current.fontSize,
            quality = s.enum<Quality>("quality") ?: current.quality,
            subtitles = s.bool("subtitles") ?: current.subtitles,
            autostart = s.bool("autostart") ?: current.autostart,
            autoCheckUpdates = s.bool("autoCheckUpdates") ?: current.autoCheckUpdates,
        )
        return Loaded(loaded, (root["savedAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }, root.string("app")?.take(20))
    }

    /** For the server: percent-encoded, as the official client stores its own data. */
    fun wrap(json: String): String = URLEncoder.encode(json, "UTF-8").replace("+", "%20")

    /** The server hands the value back encoded once or twice more: decode until it is JSON. */
    fun unwrap(value: String): String? {
        var v = value.trim()
        repeat(5) {
            if (v.startsWith("{")) return v
            if (!v.contains('%')) return null
            v = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull()?.trim() ?: return null
        }
        return v.takeIf { it.startsWith("{") }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private inline fun <reified E : Enum<E>> JsonObject.enum(key: String): E? =
        string(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

    /** Channel codes: strings only, no blanks, no repeats, sane lengths. */
    private fun JsonObject.codes(key: String): List<String>? {
        val arr = this[key] as? JsonArray ?: return null
        return arr.asSequence()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_CODE_LENGTH }
            .distinct()
            .take(MAX_CODES)
            .toList()
    }
}
