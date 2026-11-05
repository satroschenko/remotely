package app.remotely.data.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
    private const val MAX_LANGUAGE_LENGTH = 16
    private const val MAX_MINUTES = 24 * 60

    data class Loaded(val settings: Settings, val savedAtMs: Long?, val appVersion: String?)

    /**
     * What is the same on every device: favourites, hidden channels, channel order, subtitles, genre colours,
     * the sound chosen per channel, stopping in a broadcast break. Quality, font size and autostart belong to the screen and stay on it
     * (older backups carry them; they are skipped).
     */
    fun encode(s: Settings, appVersion: String, nowMs: Long): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("app", appVersion)
        put("savedAt", nowMs)
        putJsonObject("settings") {
            putJsonArray("favorites") { s.favorites.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("hidden") { s.hidden.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("order") { s.order.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("audio") { s.audio.forEach { (code, language) -> put(code, language) } }
            put("subtitles", s.subtitles)
            put("genreColors", s.genreColors)
            put("autoCheckUpdates", s.autoCheckUpdates)
            put("breakStopMinutes", s.breakStopMinutes)
            put("operatorMessages", s.operatorMessages)
            put("language", s.language.name)
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
            audio = s.languages("audio") ?: current.audio,
            subtitles = s.bool("subtitles") ?: current.subtitles,
            genreColors = s.bool("genreColors") ?: current.genreColors,
            autoCheckUpdates = s.bool("autoCheckUpdates") ?: current.autoCheckUpdates,
            breakStopMinutes = s.minutes("breakStopMinutes") ?: current.breakStopMinutes,
            operatorMessages = s.bool("operatorMessages") ?: current.operatorMessages,
            language = s.string("language")?.let { n -> app.remotely.core.AppLanguage.entries.firstOrNull { it.name == n } } ?: current.language,
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

    private fun JsonObject.minutes(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..MAX_MINUTES }

    /** Channel code → language code; anything but short strings is dropped. */
    private fun JsonObject.languages(key: String): Map<String, String>? {
        val obj = this[key] as? JsonObject ?: return null
        return obj.entries.asSequence()
            .mapNotNull { (code, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.let { code.trim() to it } }
            .filter { (code, l) -> code.isNotEmpty() && code.length <= MAX_CODE_LENGTH && l.isNotEmpty() && l.length <= MAX_LANGUAGE_LENGTH }
            .take(MAX_CODES)
            .toMap()
    }

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
