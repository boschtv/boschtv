package nl.boschtv.tv.data.xtream

import java.net.URLDecoder
import java.net.URLEncoder

/** One IPTV subscription kept on this TV. [id] also names the catalog that was built for it. */
data class Subscription(val id: Int, val source: Source) {
    /** "example.com · john": how it is shown in Settings. */
    val label get() = source.host.removePrefix("http://").removePrefix("https://") + " · " + source.username
}

/** The saved subscriptions as one text (a line each), the way they are kept in the settings store. */
object Subscriptions {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    fun encode(list: List<Subscription>): String = list.joinToString("\n") { "${it.id} ${enc(it.source.host)} ${enc(it.source.username)} ${enc(it.source.password)}" }

    fun decode(text: String?): List<Subscription> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split(' ')
        if (p.size != 4) return@mapNotNull null
        Subscription(p[0].toIntOrNull() ?: return@mapNotNull null, Source(dec(p[1]), dec(p[2]), dec(p[3])))
    }

    /** [list] with [src] in it: the same server and user keeps its place (new password), anything else is added. Returns the list and the id. */
    fun with(list: List<Subscription>, src: Source): Pair<List<Subscription>, Int> {
        val same = list.firstOrNull { it.source.host == src.host && it.source.username == src.username }
        if (same != null) return list.map { if (it.id == same.id) it.copy(source = src) else it } to same.id
        val id = (list.maxOfOrNull { it.id } ?: -1) + 1
        return (list + Subscription(id, src)) to id
    }
}
