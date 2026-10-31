package app.remotely.player

/** What the stream on screen carries: its sound tracks (language or null each) and whether it has subtitles. */
data class StreamTracks(
    val audio: List<String?> = emptyList(),
    val selectedAudio: Int = 0,
    val subtitles: Boolean = false,
)

/** Names of sound tracks as Zala tags them (ISO 639 codes, sometimes none). */
object AudioTracks {
    const val DEFAULT_LANGUAGE = "ru"

    private val NAMES = mapOf(
        "ru" to "Русский", "rus" to "Русский",
        "en" to "Английский", "eng" to "Английский",
        "be" to "Белорусский", "bel" to "Белорусский",
        "uk" to "Украинский", "ukr" to "Украинский",
        "de" to "Немецкий", "deu" to "Немецкий", "ger" to "Немецкий",
        "fr" to "Французский", "fra" to "Французский", "fre" to "Французский",
        "es" to "Испанский", "spa" to "Испанский",
        "it" to "Итальянский", "ita" to "Итальянский",
        "pl" to "Польский", "pol" to "Польский",
    )

    private fun code(lang: String?): String? = lang?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() && it != "und" && it != "un" }

    /** «Русский», «English»; an unknown code as it is; no code: «Дорожка 2». */
    fun name(lang: String?, index: Int): String = code(lang)?.let { NAMES[it] ?: it.uppercase() } ?: "Дорожка ${index + 1}"

    /** For the channel banner: «RU», «EN»; no code: the track number. */
    fun short(lang: String?, index: Int): String = code(lang)?.take(2)?.uppercase() ?: "${index + 1}"

    /** The track after [selected], round. */
    fun next(count: Int, selected: Int): Int = if (count <= 0) 0 else (selected + 1).mod(count)

    /** The track to start with: the one in the language chosen before for the channel, else none (Zala's first). */
    fun preferred(languages: List<String?>, chosen: String?): Int? =
        chosen?.let { want -> languages.indexOfFirst { code(it) == code(want) }.takeIf { it >= 0 } }
}
