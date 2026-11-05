package app.remotely.data.repo

import app.remotely.core.tr

/**
 * Zala tags a programme with one to several of about a hundred genres («Драма, Криминал, Детектив»).
 * For the colour mark they are brought down to eight groups; with genres from several groups the first
 * group in this order wins, so a cartoon comedy is children's and a sports review is sport.
 */
enum class GenreGroup(val argb: Long) {
    KIDS(0xFFF5D90A), SPORT(0xFFF5A524), MUSIC(0xFF9B5DE5), NEWS(0xFF3E8EF7),
    MOVIES(0xFFE5484D), LEARNING(0xFF46A758), SHOWS(0xFFE93D82), OTHER(0xFF8B8D98);

    val colour: String get() = when (this) {
        KIDS -> tr("Жёлтый", "Жоўты", "Yellow")
        SPORT -> tr("Оранжевый", "Аранжавы", "Orange")
        MUSIC -> tr("Фиолетовый", "Фіялетавы", "Purple")
        NEWS -> tr("Синий", "Сіні", "Blue")
        MOVIES -> tr("Красный", "Чырвоны", "Red")
        LEARNING -> tr("Зелёный", "Зялёны", "Green")
        SHOWS -> tr("Розовый", "Ружовы", "Pink")
        OTHER -> tr("Серый", "Шэры", "Grey")
    }

    val label: String get() = when (this) {
        KIDS -> tr("детское", "дзіцячае", "children")
        SPORT -> tr("спорт", "спорт", "sport")
        MUSIC -> tr("музыка и концерты", "музыка і канцэрты", "music and concerts")
        NEWS -> tr("новости и информация", "навіны і інфармацыя", "news and information")
        MOVIES -> tr("кино и сериалы", "кіно і серыялы", "films and series")
        LEARNING -> tr("познавательное: природа, история, путешествия", "пазнавальнае: прырода, гісторыя, падарожжы", "factual: nature, history, travel")
        SHOWS -> tr("шоу, юмор, кулинария, увлечения", "шоу, гумар, кулінарыя, захапленні", "shows, humour, cooking, hobbies")
        OTHER -> tr("прочее", "іншае", "other")
    }
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

/**
 * Zala gives genres in Russian only, «Криминал, Детектив, Драма», whatever the portal language. The common ones are
 * translated here; an unknown word stays as it came.
 */
object GenreNames {
    /** Russian (lower case) to Belarusian and English. */
    private val NAMES: Map<String, Pair<String, String>> = mapOf(
        "детский" to ("дзіцячы" to "children's"), "анимация" to ("анімацыя" to "animation"), "сказка" to ("казка" to "fairy tale"),
        "аниме" to ("анімэ" to "anime"), "мультфильм" to ("мультфільм" to "cartoon"),
        "спорт" to ("спорт" to "sport"), "трансляция" to ("трансляцыя" to "broadcast"), "экстрим" to ("экстрым" to "extreme"),
        "авто-мото" to ("аўта-мота" to "motoring"), "рыбалка" to ("рыбалка" to "fishing"), "охота" to ("паляванне" to "hunting"),
        "фитнес" to ("фітнес" to "fitness"),
        "музыка" to ("музыка" to "music"), "концерт" to ("канцэрт" to "concert"), "клип" to ("кліп" to "music video"),
        "мюзикл" to ("мюзікл" to "musical"), "джаз" to ("джаз" to "jazz"), "чарт" to ("чарт" to "chart"),
        "танец" to ("танец" to "dance"), "фестиваль" to ("фестываль" to "festival"),
        "информация" to ("інфармацыя" to "information"), "репортаж" to ("рэпартаж" to "report"), "аналитика" to ("аналітыка" to "analysis"),
        "обзор" to ("агляд" to "review"), "общество" to ("грамадства" to "society"), "интервью" to ("інтэрв'ю" to "interview"),
        "политика" to ("палітыка" to "politics"), "экономика" to ("эканоміка" to "economy"), "прогноз погоды" to ("прагноз надвор'я" to "weather"),
        "тележурнал" to ("тэлечасопіс" to "TV magazine"), "публицистика" to ("публіцыстыка" to "current affairs"), "бизнес" to ("бізнес" to "business"),
        "новости" to ("навіны" to "news"),
        "драма" to ("драма" to "drama"), "мелодрама" to ("меладрама" to "melodrama"), "детектив" to ("дэтэктыў" to "detective"),
        "криминал" to ("крымінал" to "crime"), "боевик" to ("баявік" to "action"), "триллер" to ("трылер" to "thriller"),
        "фантастика" to ("фантастыка" to "science fiction"), "фэнтези" to ("фэнтэзі" to "fantasy"), "военный" to ("ваенны" to "war"),
        "ужасы" to ("жахі" to "horror"), "вестерн" to ("вестэрн" to "western"), "мистика" to ("містыка" to "mystery"),
        "приключения" to ("прыгоды" to "adventure"), "семейный" to ("сямейны" to "family"), "биография" to ("біяграфія" to "biography"),
        "короткометражка" to ("кароткаметражка" to "short film"), "экшн" to ("экшн" to "action"), "роман" to ("раман" to "romance"),
        "повесть" to ("аповесць" to "story"), "комедия" to ("камедыя" to "comedy"), "сериал" to ("серыял" to "series"),
        "фильм" to ("фільм" to "film"), "документальный" to ("дакументальны" to "documentary"),
        "просвещение" to ("асвета" to "education"), "история" to ("гісторыя" to "history"), "культура" to ("культура" to "culture"),
        "путешествия" to ("падарожжы" to "travel"), "природа" to ("прырода" to "nature"), "наука" to ("навука" to "science"),
        "обучение" to ("навучанне" to "learning"), "география" to ("геаграфія" to "geography"), "расследование" to ("расследаванне" to "investigation"),
        "технологии" to ("тэхналогіі" to "technology"), "медицина" to ("медыцына" to "medicine"), "здоровье" to ("здароўе" to "health"),
        "космос" to ("космас" to "space"), "археология" to ("археалогія" to "archaeology"), "лекции" to ("лекцыі" to "lectures"),
        "психология" to ("псіхалогія" to "psychology"), "религия" to ("рэлігія" to "religion"), "архитектура" to ("архітэктура" to "architecture"),
        "философия" to ("філасофія" to "philosophy"), "литература" to ("літаратура" to "literature"), "исследование" to ("даследаванне" to "research"),
        "развлечения" to ("забавы" to "entertainment"), "юмор" to ("гумар" to "humour"), "пародия" to ("пародыя" to "parody"),
        "шоу" to ("шоу" to "show"), "ток-шоу" to ("ток-шоу" to "talk show"), "кулинария" to ("кулінарыя" to "cooking"),
        "увлечения" to ("захапленні" to "hobbies"), "игра" to ("гульня" to "game"), "викторина" to ("віктарына" to "quiz"),
        "конкурс" to ("конкурс" to "contest"), "мода" to ("мода" to "fashion"), "красота" to ("прыгажосць" to "beauty"),
        "дизайн" to ("дызайн" to "design"), "ремонт" to ("рамонт" to "home improvement"), "садоводство" to ("садоўніцтва" to "gardening"),
        "дача" to ("лецішча" to "country house"), "домоводство" to ("хатняя гаспадарка" to "homemaking"), "хозяйство" to ("гаспадарка" to "household"),
        "советы" to ("парады" to "tips"), "стендап" to ("стэндап" to "stand-up"), "лотерея" to ("латарэя" to "lottery"),
        "интерактив" to ("інтэрактыў" to "interactive"), "астрология" to ("астралогія" to "astrology"), "эзотерика" to ("эзатэрыка" to "esoterica"),
        "социальный" to ("сацыяльны" to "social"), "видеожурнал" to ("відэачасопіс" to "video magazine"), "дневник" to ("дзённік" to "diary"),
    )

    /** «Криминал, Детектив» in [lang]: each known word translated, its capital letter kept. */
    fun translate(genre: String, lang: app.remotely.core.AppLanguage): String {
        if (lang == app.remotely.core.AppLanguage.RU || genre.isBlank()) return genre
        return genre.split(',').joinToString(", ") { part ->
            val word = part.trim()
            val t = NAMES[word.lowercase()]?.let { if (lang == app.remotely.core.AppLanguage.BE) it.first else it.second } ?: return@joinToString word
            if (word.firstOrNull()?.isUpperCase() == true) t.replaceFirstChar { it.uppercase() } else t
        }
    }
}
