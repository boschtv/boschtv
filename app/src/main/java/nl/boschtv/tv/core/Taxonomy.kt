package nl.boschtv.tv.core

/** Real genres. Provider categories are only ever used as a signal to derive these. */
enum class Genre(val label: String, pattern: String) {
    ACTION("Actie", """\b(acti(e|on))\b"""),
    ADVENTURE("Avontuur", """\b(adventure|avontuur)"""),
    COMEDY("Komedie", """\b(comedy|komedie|comedie|cabaret|humor)"""),
    DRAMA("Drama", """\b(drama|soap)"""),
    THRILLER("Thriller", """\bthriller"""),
    CRIME("Misdaad", """\b(crime|misdaad|krimi)"""),
    ROMANCE("Romantiek", """\broman(ce|tiek|tisch|tic)"""),
    SCIFI("Sciencefiction", """\b(sci[- ]?fi|science[- ]?fiction)"""),
    FANTASY("Fantasy", """\bfantas(y|ie)"""),
    HORROR("Horror", """\b(horror|griezel)"""),
    MYSTERY("Mysterie", """\bmyster"""),
    FAMILY("Familie", """\bfamil(y|ie)\b"""),
    KIDS("Kids", """\b(kids?|kinderen|kinder|jeugd|baby|peuter|k3|nick\w*|cartoon|sinterklaas)\b"""),
    ANIMATION("Animatie", """\b(animati(e|on)|cartoon|tekenfilm)"""),
    ANIME("Anime", """\banime\b"""),
    DOCUMENTARY("Documentaire", """\b(docu\w*|national geographic)"""),
    HISTORY("Historisch", """\b(histor\w+|geschiedenis)"""),
    WAR("Oorlog", """\b(war|oorlog)\b"""),
    WESTERN("Western", """\bwestern"""),
    MUSIC("Muziek", """\b(musi[ck]\w*|muziek|concert\w*|festivals?)"""),
    REALITY("Reality", """\b(reality|talk|90 day)"""),
    SPORT("Sport", """\b(sports?|f1|mxgp|motocross|darts|nascar|ufc|wwe|glory|pfl|dazn|feyenoord|vro+m|race cafe|boks|formula1?|espn)\b""");

    val bit: Long = 1L shl ordinal
    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    companion object {
        /** Bitmask of every genre mentioned in free text ("Action & Adventure / Komedie", "NL ACTIE"). */
        fun mask(text: String?): Long {
            if (text.isNullOrBlank()) return 0
            var m = 0L
            for (g in entries) if (g.regex.containsMatchIn(text)) m = m or g.bit
            return m
        }

        fun of(mask: Long): List<Genre> = entries.filter { mask and it.bit != 0L }
        fun labels(mask: Long, max: Int = 3): String = of(mask).take(max).joinToString(", ") { it.label }
    }
}

/** Streaming services, recognised from provider category names. Order = display priority. */
enum class Service(val label: String, pattern: String, val color: Long) {
    NETFLIX("Netflix", "netflix", 0xFFE50914),
    PRIME("Prime Video", "prime|amazon", 0xFF1399FF),
    DISNEY("Disney+", "disney", 0xFF0E47BA),
    VIDEOLAND("Videoland", "videoland", 0xFFE4003A),
    HBO("HBO Max", """hbo|\bmax\b""", 0xFF7B2FF7),
    APPLE("Apple TV+", "apple", 0xFF8E8E93),
    SKYSHOWTIME("SkyShowtime", "skyshowtime", 0xFF6E3CE6),
    VIAPLAY("Viaplay", "viaplay", 0xFFE4126B),
    NPO("NPO Start", """\bnpo\b""", 0xFFFF6D00),
    NLZIET("NLZIET", "nlziet", 0xFF00B3A1),
    PARAMOUNT("Paramount+", "paramount", 0xFF0064FF),
    DISCOVERY("Discovery+", "discovery", 0xFF2175D9),
    PATHE("Pathé Thuis", """path[eé]""", 0xFFF5B400),
    HULU("Hulu", "hulu", 0xFF1CE783),
    PEACOCK("Peacock", "peacock", 0xFF5B5B5B),
    STREAMZ("Streamz", "streamz", 0xFF00C2B2),
    VTM("VTM GO", "vtm", 0xFFE3004F),
    VRT("VRT MAX", "vrt", 0xFF5541F0),
    BBC("BBC", """\bbbc\b""", 0xFFB80000),
    CANAL("Canal+", "canal", 0xFF2B2B2B),
    KIJK("KIJK", """\bkijk\b""", 0xFF00A9E0),
    MGM("MGM+", """\bmgm""", 0xFFB5892F),
    CRUNCHYROLL("Crunchyroll", "crunchyroll", 0xFFF47521),
    NATGEO("National Geographic", "national geographic", 0xFFFFCC00);

    val bit: Long = 1L shl ordinal
    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    companion object {
        fun mask(text: String): Long {
            var m = 0L
            for (s in entries) if (s.regex.containsMatchIn(text)) m = m or s.bit
            return m
        }

        fun of(mask: Long): List<Service> = entries.filter { mask and it.bit != 0L }
    }
}

/** Editorial signals that provider categories carry besides genre and service. */
object Flags {
    const val TOP = 1
    const val CINEMA = 2
    const val CHRISTMAS = 4
    const val SINTERKLAAS = 8

    private val top = Regex("""\btop ?\d{2,3}\b""", RegexOption.IGNORE_CASE)
    private val cinema = Regex("""\b(bioscoop|in cinemas?)\b""", RegexOption.IGNORE_CASE)
    private val christmas = Regex("""\b(kerst\w*|christmas|xmas)\b""", RegexOption.IGNORE_CASE)
    private val sint = Regex("""\bsinterklaas\b""", RegexOption.IGNORE_CASE)

    fun of(text: String): Int {
        var f = 0
        if (top.containsMatchIn(text)) f = f or TOP
        if (cinema.containsMatchIn(text)) f = f or CINEMA
        if (christmas.containsMatchIn(text)) f = f or CHRISTMAS
        if (sint.containsMatchIn(text)) f = f or SINTERKLAAS
        return f
    }
}

/**
 * What the viewer wants to see: their [home] language, English as fallback for films/series and for
 * live TV, and (for Dutch viewers) Flemish titles and channels.
 */
data class ContentPrefs(
    val english: Boolean = true,
    val belgian: Boolean = true,
    val englishLive: Boolean = false,
    val home: Language = Language.DUTCH,
) {
    /** Where a language stands for this viewer. */
    fun relate(l: Language): Lang = when {
        l == home -> Lang.HOME
        home == Language.FLEMISH && l == Language.DUTCH -> Lang.HOME
        home == Language.DUTCH && l == Language.FLEMISH -> Lang.REGIONAL
        l == Language.ENGLISH -> Lang.EN
        else -> Lang.OTHER
    }

    /** Whether a language this viewer sees as [rel] is wanted, for films/series or live TV. */
    fun wants(rel: Lang, live: Boolean): Boolean = when (rel) {
        Lang.HOME -> true
        Lang.REGIONAL -> belgian
        Lang.EN -> if (live) englishLive else english
        Lang.OTHER -> false
    }
}

/**
 * What a provider category means for us. `lang == null` means the category is dropped entirely.
 * [labelled] tells whether the language came from the category itself; when it did not, the title's
 * own label ("NL: Dune") decides per title.
 */
data class CategoryInfo(val lang: Lang?, val genres: Long, val services: Long, val flags: Int, val label: String, val labelled: Boolean = true)

/**
 * How a provider writes its catalog, measured once per sync before anything is classified. Providers
 * that put a language on most categories get strict filtering (unlabelled = odd extras); providers
 * that hardly label anything get everything kept, because there an unlabelled category is the norm.
 */
data class ProviderProfile(val labelsLanguages: Boolean) {
    companion object {
        val LABELLED = ProviderProfile(true)

        fun of(categoryNames: Collection<String>): ProviderProfile {
            if (categoryNames.isEmpty()) return LABELLED
            val labelled = categoryNames.count { Tags.language(Tags.of(it)) != null || Language.ofWords(Tags.strip(it)) != null }
            return ProviderProfile(labelled * 10 >= categoryNames.size * 3)   // at least 30% labelled
        }
    }
}

object Categories {
    /** Never shown, whatever the language. */
    private val adult = Regex("""\b(adults?|xxx|18\+|porn\w*)\b""", RegexOption.IGNORE_CASE)
    private val titleCase = Regex("""\b([A-Z])([A-Z]+)\b""")

    fun classify(name: String, live: Boolean, prefs: ContentPrefs, profile: ProviderProfile = ProviderProfile.LABELLED): CategoryInfo {
        val body = Tags.strip(name)
        val tagged = Tags.language(Tags.of(name))
        val named = Language.ofWords(body)
        // A foreign language named inside a home-labelled category wins: "┃NL┃ TURKS NU TE ZIEN", "┃NL┃ BOLLYWOOD".
        val language = if (named != null && prefs.relate(named) == Lang.OTHER) named else tagged ?: named
        val lang = when (val rel = language?.let(prefs::relate)) {
            null -> when {
                // A provider that hardly labels languages: unlabelled is simply its main content.
                !profile.labelsLanguages -> Lang.HOME
                // A labelling provider: unlabelled live groups are mostly international event feeds.
                live -> if (prefs.englishLive) Lang.EN else null
                else -> Lang.HOME
            }
            else -> rel.takeIf { prefs.wants(it, live) }
        }
        if (lang == null || adult.containsMatchIn(body)) return CategoryInfo(null, 0, 0, 0, body, language != null)
        return CategoryInfo(
            lang = lang,
            genres = if (live) 0 else Genre.mask(body),
            services = if (live) 0 else Service.mask(body),
            flags = if (live) 0 else Flags.of(body),
            label = pretty(body),
            labelled = language != null,
        )
    }

    /** "NEDERLAND HD | TERUGKIJKEN ⏺" -> "Nederland HD". */
    private fun pretty(body: String): String {
        val first = body.split('|', '┃').first().replace(Regex("""[\p{So}\p{Sk}]"""), "").trim()
        return titleCase.replace(first) { m ->
            val w = m.value
            if (w.length <= 3 || w.any(Char::isDigit)) w else m.groupValues[1] + m.groupValues[2].lowercase()
        }.ifBlank { body }
    }
}
