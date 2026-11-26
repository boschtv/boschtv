package nl.boschtv.tv.core

import java.text.Normalizer

/**
 * A language relative to the viewer: their [HOME] language, a [REGIONAL] variant they may add (Flemish
 * for a Dutch viewer), [EN] as fallback, or [OTHER] (filtered out).
 */
enum class Lang { HOME, REGIONAL, EN, OTHER }

/**
 * Language / country labels in provider names. Providers write them in many ways:
 * "┃NL┃ NETFLIX", "|NL| Films", "[NL] Drama", "(NL) Kids", "NL: Movies", "NL | News", "Movies | NL",
 * "Movies [EN]", or as words: "Dutch Movies", "Nederlandse films", "German Cinema". Which codes and
 * words mean which language is in [Language].
 */
object Tags {
    // Explicit tag syntax: whatever sits between the bars or brackets is meant as a label.
    private val bar = Regex("""^\s*[┃|]\s*([^┃|]{1,14}?)\s*[┃|]""")
    private val bracket = Regex("""^\s*[\[(]\s*([A-Za-z]{2,5})\s*[\])]""")
    // Looser forms: only accepted when the code is a known language, so "FBI: Most Wanted" stays a title.
    private val prefix = Regex("""^\s*([A-Z]{2,5})\s*(?:[|\-–•▎]\s|:\s*)""")
    private val suffix = Regex("""(?:\s[|┃\-–]\s*|\s[\[(])([A-Z]{2,5})[\])]?\s*$""")
    private val anyBar = Regex("""[┃|]\s*[^┃|]{1,14}?\s*[┃|]""")
    // The loosest form, a code and a space: "NL Landman", "DE Landman", "MULTI Landman".
    private val bare = Regex("""^\s*([A-Z]{2,5})\s+(?=\S)""")
    private val lower = Regex("""\p{Ll}""")

    /** A listing that carries several languages at once ("MULTI Landman"): a label, but no language. */
    const val MULTI = "MULTI"

    /** Labels that are not a country at all (PPV, UCL, 4K…): treated as no label. */
    private val notCountries = setOf("PPV", "UCL", "VIP", "VOD", "UHD", "4K", "8K", "HD", "FHD", "SD", "3D", "TOP", "NEW", "TV", "HEVC", "24/7", "LIVE", MULTI)

    /**
     * Codes that are also ordinary words at the start of a title ("IT Chapter Two", "US Marshals",
     * "NO Time to Die"): only read as a label with a separator ("IT: …", "┃IT┃ …"), never bare.
     * Longer codes are words too ("HOLLAND", "DUTCH", "HINDI"), so the bare form only takes two or three letters.
     */
    private val notBare = setOf("IT", "US", "IS", "NO", "IN", "ID", "MY", "AL", "BE", "CA", "BA", "SI", "AT", "AF", "KU", "PH", "AR", "ME")

    /**
     * A code and a space, read as a label only when that cannot be part of the title. The rest of the
     * name must have small letters: "DE KAMPIOENEN" (all capitals) is a Dutch title, "DE Landman" is German.
     */
    private fun bareCode(name: String): String? {
        val m = bare.find(name) ?: return null
        val code = m.groupValues[1]
        if (code == MULTI) return code
        if (code.length > 3 || code in notBare || code !in Language.allCodes) return null
        return code.takeIf { lower.containsMatchIn(name.substring(m.range.last + 1)) }
    }

    /** Upper-cased label, or null when the name has none. */
    fun of(name: String): String? {
        (bar.find(name) ?: bracket.find(name))?.let { return it.groupValues[1].trim().uppercase() }
        prefix.find(name)?.groupValues?.get(1)?.uppercase()?.let { if (it in Language.allCodes || it == MULTI) return it }
        bareCode(name)?.let { return it }
        suffix.find(name)?.groupValues?.get(1)?.uppercase()?.let { if (it in Language.allCodes || it == MULTI) return it }
        return null
    }

    /** The language a label stands for; [Language.OTHER] for an unknown country code, null for no label. */
    fun language(tag: String?): Language? = when {
        tag == null || tag in notCountries -> null
        else -> Language.ofCode(tag) ?: if (tag.length <= 3 && tag.all { it.isLetter() }) Language.OTHER else null
    }

    /** Removes every tag marker from a name. */
    fun strip(name: String): String {
        if (bar.containsMatchIn(name)) return anyBar.replace(name, " ").replace('┃', ' ').trim()
        var s = bracket.replace(name, "")
        fun known(code: String) = code.uppercase().let { it in Language.allCodes || it == MULTI }
        prefix.find(s)?.let { if (known(it.groupValues[1])) s = s.removeRange(it.range) }
        // "MULTI NL Landman": strip every bare label in front, not just the first.
        while (true) { val m = bare.find(s) ?: break; if (bareCode(s) == null) break; s = s.removeRange(m.range) }
        suffix.find(s)?.let { if (known(it.groupValues[1])) s = s.removeRange(it.range) }
        return s.replace('┃', ' ').trim()
    }
}

data class CleanTitle(val name: String, val year: Int?, val dubbed: Boolean, val quality: String?)

object Titles {
    private val dub = Regex("""(?i)nl[ -]?gesproken|nl[ -]audio|vlaams gesproken""")
    private val uhd = Regex("""(?i)\b(4k|uhd|2160p)\b""")
    private val dolby = Regex("""(?i)\bdolby(\s+vision)?\b""")
    private val hevc = Regex("""(?i)\b(hevc|x265|h265)\b""")
    private val yearParen = Regex("""[(\[]\s*((?:19|20)\d{2})(?:\s*-\s*(?:(?:19|20)\d{2})?)?\s*[)\]]""")
    private val yearTail = Regex("""\s[-–]\s*((?:19|20)\d{2})\s*$""")
    private val square = Regex("""\[[^\]]*]""")
    private val capsParen = Regex("""\((?=[^)]*[A-Z])[A-Z0-9+ \-]{2,16}\)""")
    private val dubParen = Regex("""(?i)\([^)]*gesproken[^)]*\)""")
    private val junk = Regex("""[\p{So}\p{Sk}\p{Lm}\p{Cf}]""")
    private val spaces = Regex("""\s{2,}""")
    private val episodeCode = Regex("""(?i)^.*?\bS\d{1,3}\s?E\d{1,4}\b\s*[-–:]?\s*""")
    private val marks = Regex("""\p{Mn}+""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")

    fun clean(raw: String): CleanTitle {
        var s = Tags.strip(raw)
        val dubbed = dub.containsMatchIn(s)
        val quality = when {
            dolby.containsMatchIn(s) -> "Dolby Vision"
            uhd.containsMatchIn(s) -> "4K"
            hevc.containsMatchIn(s) -> "HEVC"
            else -> null
        }
        var year = yearParen.findAll(s).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        s = yearParen.replace(s, " ")
        s = square.replace(s, " ")
        s = dubParen.replace(s, " ")
        s = capsParen.replace(s, " ")
        s = uhd.replace(s, " ")
        s = dolby.replace(s, " ")
        s = hevc.replace(s, " ")
        s = junk.replace(s, " ")
        s = spaces.replace(s, " ").trim()
        if (year == null) yearTail.find(s)?.let { year = it.groupValues[1].toIntOrNull(); s = s.removeRange(it.range) }
        s = s.trim(' ', '-', '–', ':', '|').trim()
        return CleanTitle(s.ifBlank { Tags.strip(raw).ifBlank { raw.trim() } }, year, dubbed, quality)
    }

    fun channel(raw: String): String =
        spaces.replace(junk.replace(square.replace(Tags.strip(raw), " "), " "), " ").trim().ifBlank { raw.trim() }

    /** "┃NL┃ Show (2026) - S01E02 - The title" -> "The title"; [fallback] when there is none. */
    fun episode(raw: String?, number: Int, fallback: (Int) -> String = { "Episode $it" }): String {
        val base = raw.orEmpty()
        val tail = if (episodeCode.containsMatchIn(base)) episodeCode.replace(base, "") else clean(base).name
        val t = spaces.replace(junk.replace(tail, " "), " ").trim()
        return t.ifBlank { fallback(number) }
    }

    private val placeholderEpisode = Regex("""^(?:episode|episodio|episodi|aflevering|afl|folge|capitulo|chapter|part|deel|ep|e)?\s*\d+$""")

    /** "Episodio 3", "Aflevering 3", "E03", "3": a name that says no more than the episode's number. */
    fun isPlaceholderEpisodeName(name: String): Boolean = fold(name).let { it.isBlank() || placeholderEpisode.matches(it) }

    /** Lower-case, accent-free, alphanumeric form used for search and de-duplication. */
    fun fold(s: String): String {
        val n = marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
        return nonAlnum.replace(n, " ").trim()
    }

    /** True for "☰☰☰ NL JEUGD PAKKET ☰☰☰"-style divider rows in channel lists. */
    /**
     * The provider's TMDB id, only when it really is one: digits, not zero. Some lists carry an IMDb id
     * ("tt0111161") or other text in that field; a key built from it would match nothing.
     */
    fun tmdbId(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.trimStart('0')?.takeIf { it.isNotEmpty() }

    fun isDivider(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return true
        val symbols = t.count { !it.isLetterOrDigit() && !it.isWhitespace() && it != '┃' }
        return symbols >= 6 && (t.first() == t.last() || t.startsWith("#") || t.startsWith("=") || t.startsWith("*"))
    }
}
