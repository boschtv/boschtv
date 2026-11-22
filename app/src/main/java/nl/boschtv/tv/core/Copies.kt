package nl.boschtv.tv.core

/**
 * One file the provider has for an episode. Providers list the same series several times (per language
 * category, per quality), each with its own files, often on different servers. [listing] is the series
 * id it came from; [kbps] is 0 when the provider does not say how heavy the file is.
 */
data class EpisodeCopy(val listing: Long, val id: String, val container: String?, val kbps: Int)

object Copies {
    /**
     * What to play instead when [current] keeps stopping to load: the best-looking copy that is clearly
     * lighter (at most three quarters of its weight). A copy of unknown weight is a last resort, since it
     * may sit on a faster server. Nothing in [tried] is offered again, so this only ever steps down.
     */
    fun lighter(all: List<EpisodeCopy>, current: EpisodeCopy, tried: Set<String>): EpisodeCopy? {
        val rest = all.filter { it.id != current.id && it.id !in tried }
        val known = rest.filter { it.kbps > 0 }
        val pick = if (current.kbps > 0) known.filter { it.kbps <= current.kbps * 3 / 4 }.maxByOrNull { it.kbps }
        else known.minByOrNull { it.kbps }
        return pick ?: rest.firstOrNull { it.kbps == 0 }
    }
}
