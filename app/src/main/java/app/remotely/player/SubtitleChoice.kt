package app.remotely.player

object SubtitleChoice {
    /**
     * Which of a channel's subtitle tracks to show. Zala marks them all as main subtitles; where there are
     * two Russian ones the first stays empty and the text comes in the last. Then any named language, then the first.
     */
    fun pick(languages: List<String?>): Int? {
        if (languages.isEmpty()) return null
        val ru = languages.indexOfLast { it?.startsWith("ru") == true }
        if (ru >= 0) return ru
        val named = languages.indexOfFirst { it != null && it != "un" && it != "und" }
        return if (named >= 0) named else 0
    }

    /** The source writes «й» as «ѝ». */
    fun fixText(text: String): String = text.replace('ѝ', 'й').replace('Ѝ', 'Й')
}
