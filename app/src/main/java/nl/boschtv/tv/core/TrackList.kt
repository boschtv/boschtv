package nl.boschtv.tv.core

/** One subtitle or audio track, as the file describes it. [language] is a code ("nl", "en-US"), [name] the file's own label. */
data class TrackInfo(val language: String?, val name: String?, val forced: Boolean = false, val hearingImpaired: Boolean = false)

/**
 * Makes sense of the long track lists of films taken from a streaming service (thirty subtitle
 * languages is normal): the viewer's languages come first, the rest goes behind "More languages".
 */
object TrackList {
    private val SDH = Regex("""\b(sdh|cc|hearing|hard of hearing|doven|slechthorend\w*|malentendants?|sordos|hörgeschädigte)\b""", RegexOption.IGNORE_CASE)
    private val FORCED = Regex("""\b(forced|foreign|gedwongen|forcé|forzados?)\b""", RegexOption.IGNORE_CASE)

    /** Files often only say it in the track's name: "English [SDH]", "Dutch (Forced)". */
    fun hearingImpaired(name: String?) = name != null && SDH.containsMatchIn(name)
    fun forced(name: String?) = name != null && FORCED.containsMatchIn(name)

    private fun code(language: String?) = language?.substringBefore('-')?.lowercase()

    /** Whether a track is in one of the viewer's languages ([preferred], most wanted first). */
    fun wanted(t: TrackInfo, preferred: List<String>) = code(t.language) in preferred

    /**
     * The order to show [tracks] in, as positions in the given list: the viewer's languages first, in the
     * order of [preferred]; within a language the plain track, then the one for the hard of hearing, then
     * the one with only the foreign dialogue; everything else after that, in the file's own order.
     */
    fun order(tracks: List<TrackInfo>, preferred: List<String>): List<Int> = tracks.indices.sortedWith(
        compareBy<Int> { i -> preferred.indexOf(code(tracks[i].language)).let { if (it < 0) preferred.size else it } }
            .thenBy { i -> if (wanted(tracks[i], preferred)) (if (tracks[i].forced) 2 else if (tracks[i].hearingImpaired) 1 else 0) else 0 }
            .thenBy { it })

    /**
     * Which tracks go behind "More languages": those not in the viewer's languages. A short list, or one
     * with nothing in the viewer's languages, is simply shown whole.
     */
    fun folded(tracks: List<TrackInfo>, preferred: List<String>): Set<Int> {
        if (tracks.size <= 6 || tracks.none { wanted(it, preferred) }) return emptySet()
        return tracks.indices.filterNot { wanted(tracks[it], preferred) }.toSet()
    }

    /** Labels that would read the same get a number: "English", "English 2". */
    fun distinct(labels: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return labels.map { l -> val n = (seen[l] ?: 0) + 1; seen[l] = n; if (n == 1) l else "$l $n" }
    }
}
