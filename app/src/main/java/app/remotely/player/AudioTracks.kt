package app.remotely.player

import app.remotely.core.tr

/** What the stream on screen carries: its sound tracks (language or null each), subtitles, the height of the picture. */
data class StreamTracks(
    val audio: List<String?> = emptyList(),
    val selectedAudio: Int = 0,
    val subtitles: Boolean = false,
    val videoHeight: Int = 0,
    /** The best picture the channel gives at full screen under the quality setting; the list preview itself is capped. */
    val bestHeight: Int = 0,
) {
    /** «720p»: what is really on screen, it follows the stream in «Авто»; null until the picture is known. */
    val qualityLabel: String? get() = videoHeight.takeIf { it > 0 }?.let { "${it}p" }

    /** For the list preview: what the channel will be at full screen. */
    val bestQualityLabel: String? get() = bestHeight.takeIf { it > 0 }?.let { "${it}p" }

    companion object {
        /** The tallest of [heights] within [maxHeight] (0 = no limit); below every track, the lowest is what plays. */
        fun bestHeight(heights: List<Int>, maxHeight: Int): Int {
            val within = if (maxHeight > 0) heights.filter { it <= maxHeight } else heights
            return within.maxOrNull() ?: heights.minOrNull() ?: 0
        }
    }
}

/** Names of sound tracks as Zala tags them (ISO 639 codes, sometimes none). */
object AudioTracks {
    const val DEFAULT_LANGUAGE = "ru"

    /** Language names: Russian, Belarusian, English. */
    private val RU = Triple("Русский", "Руская", "Russian")
    private val EN = Triple("Английский", "Англійская", "English")
    private val BE = Triple("Белорусский", "Беларуская", "Belarusian")
    private val UK = Triple("Украинский", "Украінская", "Ukrainian")
    private val DE = Triple("Немецкий", "Нямецкая", "German")
    private val FR = Triple("Французский", "Французская", "French")
    private val ES = Triple("Испанский", "Іспанская", "Spanish")
    private val IT = Triple("Итальянский", "Італьянская", "Italian")
    private val PL = Triple("Польский", "Польская", "Polish")
    private val NAMES = mapOf(
        "ru" to RU, "rus" to RU, "en" to EN, "eng" to EN, "be" to BE, "bel" to BE, "uk" to UK, "ukr" to UK,
        "de" to DE, "deu" to DE, "ger" to DE, "fr" to FR, "fra" to FR, "fre" to FR, "es" to ES, "spa" to ES,
        "it" to IT, "ita" to IT, "pl" to PL, "pol" to PL,
    )

    private fun code(lang: String?): String? = lang?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() && it != "und" && it != "un" }

    /** «Русский», «English»; an unknown code as it is; no code: «Дорожка 2». */
    fun name(lang: String?, index: Int): String =
        code(lang)?.let { c -> NAMES[c]?.let { tr(it.first, it.second, it.third) } ?: c.uppercase() } ?: tr("Дорожка", "Дарожка", "Track") + " ${index + 1}"

    /** For the channel banner: «RU», «EN»; no code: the track number. */
    fun short(lang: String?, index: Int): String = code(lang)?.take(2)?.uppercase() ?: "${index + 1}"

    /** The track after [selected], round. */
    fun next(count: Int, selected: Int): Int = if (count <= 0) 0 else (selected + 1).mod(count)

    /** The track to start with: the one in the language chosen before for the channel, else none (Zala's first). */
    fun preferred(languages: List<String?>, chosen: String?): Int? =
        chosen?.let { want -> languages.indexOfFirst { code(it) == code(want) }.takeIf { it >= 0 } }
}
