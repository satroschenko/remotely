package app.remotely.data.repo

/**
 * Zala tags a programme with one to several of about a hundred genres («Драма, Криминал, Детектив»).
 * For the colour mark they are brought down to eight groups; with genres from several groups the first
 * group in this order wins, so a cartoon comedy is children's and a sports review is sport.
 */
enum class GenreGroup(val argb: Long, val colour: String, val label: String) {
    KIDS(0xFFF5D90A, "Жёлтый", "детское"),
    SPORT(0xFFF5A524, "Оранжевый", "спорт"),
    MUSIC(0xFF9B5DE5, "Фиолетовый", "музыка и концерты"),
    NEWS(0xFF3E8EF7, "Синий", "новости и информация"),
    MOVIES(0xFFE5484D, "Красный", "кино и сериалы"),
    LEARNING(0xFF46A758, "Зелёный", "познавательное: природа, история, путешествия"),
    SHOWS(0xFFE93D82, "Розовый", "шоу, юмор, кулинария, увлечения"),
    OTHER(0xFF8B8D98, "Серый", "прочее"),
}

object Genres {
    private val WORDS: Map<GenreGroup, Set<String>> = mapOf(
        GenreGroup.KIDS to setOf("детский", "анимация", "сказка", "аниме"),
        GenreGroup.SPORT to setOf("спорт", "трансляция", "экстрим", "авто-мото", "рыбалка", "охота", "фитнес"),
        GenreGroup.MUSIC to setOf("музыка", "концерт", "клип", "мюзикл", "джаз", "чарт", "танец", "фестиваль"),
        GenreGroup.NEWS to setOf(
            "информация", "репортаж", "аналитика", "обзор", "общество", "интервью", "политика", "экономика",
            "прогноз погоды", "тележурнал", "публицистика", "бизнес",
        ),
        GenreGroup.MOVIES to setOf(
            "драма", "мелодрама", "детектив", "криминал", "боевик", "триллер", "фантастика", "фэнтези", "военный",
            "ужасы", "вестерн", "мистика", "приключения", "семейный", "биография", "короткометражка", "экшн",
            "роман", "повесть", "комедия",
        ),
        GenreGroup.LEARNING to setOf(
            "просвещение", "история", "культура", "путешествия", "природа", "наука", "обучение", "география",
            "расследование", "технологии", "медицина", "здоровье", "космос", "археология", "лекции", "психология",
            "религия", "архитектура", "философия", "литература", "исследование",
        ),
        GenreGroup.SHOWS to setOf(
            "развлечения", "юмор", "пародия", "шоу", "кулинария", "увлечения", "игра", "викторина", "конкурс",
            "мода", "красота", "дизайн", "ремонт", "садоводство", "дача", "домоводство", "хозяйство", "советы",
            "стендап", "лотерея", "интерактив", "астрология", "эзотерика", "социальный", "видеожурнал", "дневник",
        ),
    )

    /** Breaks in broadcasting come tagged «Трансляция» or «Информация»: they get no colour. */
    private val BREAK = Regex("""^(перерыв( в)? вещани|перапынак ў вяшчанн)""", RegexOption.IGNORE_CASE)

    /** The group of a programme's genre text; null when Zala gives no genre at all or it is a break. */
    fun of(genre: String?, title: String? = null): GenreGroup? {
        if (title != null && BREAK.containsMatchIn(title.trim())) return null
        val words = genre.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return GenreGroup.entries.firstOrNull { g -> words.any { it in WORDS[g].orEmpty() } } ?: GenreGroup.OTHER
    }
}
