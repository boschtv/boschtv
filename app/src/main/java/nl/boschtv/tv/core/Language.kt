package nl.boschtv.tv.core

/**
 * Every content language the app recognises in provider names, by code ("┃DE┃", "FR:", "[PL]") or by
 * word ("German Movies", "Turkse series", "Bollywood"). The [selectable] ones can be chosen as home
 * language in Instellingen; the others are only recognised so they can be filtered out.
 *
 * To support a new language: add an entry here (codes as providers write them, words in that language
 * and in Dutch/English), and a line in CoreTest. Nothing else in the app needs to change.
 */
enum class Language(
    val label: String,
    val codes: Set<String>,
    words: String,
    val selectable: Boolean = false,
    /** TMDB locale and watch region for trending lists and descriptions. */
    val locale: String = "en-US",
) {
    DUTCH("Nederlands", setOf("NL", "NED", "NLD", "HOLLAND", "DUTCH", "NL-SUB", "NL-AUDIO"), """dutch|nederlands\w*|netherlands|holland\w*""", true, "nl-NL"),
    FLEMISH("Vlaams (België)", setOf("BE", "VL", "BEL"), """vlaams\w*|flemish|belgi\w*""", true, "nl-BE"),
    ENGLISH("Engels", setOf("EN", "UK", "US", "USA", "USS", "GB", "CA", "CA EN", "AU", "IE", "NZ", "ENG"), """english|engelse?|british|american|united kingdom|united states""", true, "en-US"),
    GERMAN("Duits", setOf("DE", "GER", "AT", "CH", "DEU"), """german|deutsch\w*|duitse?""", true, "de-DE"),
    FRENCH("Frans", setOf("FR", "FRA"), """french|fran[cç]ais\w*|franse?""", true, "fr-FR"),
    SPANISH("Spaans", setOf("ES", "ESP", "LAT", "MX"), """spanish|espa[nñ]ol\w*|spaanse?|latino""", true, "es-ES"),
    ITALIAN("Italiaans", setOf("IT", "ITA"), """italian\w*|italiano|italiaanse?""", true, "it-IT"),
    PORTUGUESE("Portugees", setOf("PT", "BR", "POR"), """portugu\w+|brazil\w*|brasil\w*""", true, "pt-PT"),
    POLISH("Pools", setOf("PL", "POL"), """polish|polski|poolse?""", true, "pl-PL"),
    TURKISH("Turks", setOf("TR", "TUR"), """turk\w*|t[uü]rk[cç]e""", true, "tr-TR"),
    ARABIC("Arabisch", setOf("AR", "ARA"), """arab\w*""", true, "ar-AE"),
    // Recognised only, to keep them out.
    GREEK("Grieks", setOf("GR", "GRE"), """greek|griekse?"""),
    ALBANIAN("Albanees", setOf("AL", "ALB"), """albanian|shqip"""),
    ROMANIAN("Roemeens", setOf("RO", "ROM"), """romanian|roemeense?"""),
    RUSSIAN("Russisch", setOf("RU", "RUS", "UA"), """russian|russische?|ukrain\w*"""),
    NORDIC("Scandinavisch", setOf("SE", "NO", "DK", "FI", "IS", "SWE", "NOR", "DAN"), """swedish|norwegian|danish|finnish|nordic|scandinavi\w*"""),
    BALKAN("Balkan", setOf("EXYU", "HR", "RS", "BA", "BG", "SI", "MK"), """balkan|ex-?yu\w*|serbian|croatian|bulgarian"""),
    CENTRAL_EU("Midden-Europees", setOf("HU", "CZ", "SK", "HUN", "CZE"), """hungarian|czech|slovak"""),
    SOUTH_ASIAN("Zuid-Aziatisch", setOf("IN", "PK", "HINDI", "PAK", "BD"), """hindi|india\w*|bollywood|tamil|telugu|punjabi|urdu|pakistan\w*|bengali|malayalam"""),
    PERSIAN("Perzisch", setOf("IR", "AF", "KURD", "KU"), """persian|farsi|iran\w*|afghan\w*|kurd\w*"""),
    EAST_ASIAN("Aziatisch", setOf("ASIA", "KR", "JP", "CN", "TH", "VN", "PH", "ID", "MY", "KOR", "JAP", "CHN"), """asia\w*|korea\w*|japan\w*|chinese|chin[ae]\w*|thai|vietnam\w*|filipino|indonesia\w*"""),
    AFRICAN("Afrikaans", setOf("AFR", "NG", "ZA"), """african|nollywood|nigeria\w*"""),
    /** A code between explicit tag markers ("┃XY┃") that is no language we know: a foreign one. */
    OTHER("Overig", emptySet(), """\b\B""");

    private val words = Regex("""\b($words)\b""", RegexOption.IGNORE_CASE)
    val region: String get() = locale.substringAfter('-')

    companion object {
        private val byCode: Map<String, Language> = entries.flatMap { l -> l.codes.map { it to l } }.toMap()
        val allCodes: Set<String> get() = byCode.keys
        val choices: List<Language> = entries.filter { it.selectable }

        fun ofCode(code: String?): Language? = code?.let { byCode[it.uppercase()] }

        /** First language named in words, or null. */
        fun ofWords(text: String): Language? = entries.firstOrNull { it != OTHER && it.words.containsMatchIn(text) }

        fun named(name: String?): Language = entries.firstOrNull { it.name == name && it.selectable } ?: DUTCH

        /** The TV's own language, as a starting point for new installs (Dutch on a Belgian TV is Flemish). */
        fun ofDevice(locale: java.util.Locale = java.util.Locale.getDefault()): Language = when (locale.language) {
            "nl" -> if (locale.country == "BE") FLEMISH else DUTCH
            "de" -> GERMAN
            "fr" -> FRENCH
            "es" -> SPANISH
            "it" -> ITALIAN
            "pt" -> PORTUGUESE
            "pl" -> POLISH
            "tr" -> TURKISH
            "ar" -> ARABIC
            else -> ENGLISH
        }
    }
}
