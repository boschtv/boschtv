package nl.boschtv.tv

import com.squareup.moshi.JsonReader
import nl.boschtv.tv.core.Categories
import nl.boschtv.tv.core.CategoryInfo
import nl.boschtv.tv.core.ContentPrefs
import nl.boschtv.tv.core.Flags
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Language
import nl.boschtv.tv.core.ProviderProfile
import nl.boschtv.tv.core.Service
import okio.buffer
import okio.source
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Developer tool, not a test: shows how BoschTV reads a provider's catalog, category by category,
 * using exactly the rules the app uses. Run it through `tools/provider-report.sh`; it is skipped in
 * normal test runs. Writes app/build/provider-report.txt.
 */
class ProviderReport {
    @Test fun report() {
        val server = System.getProperty("xtream.server").orEmpty().trimEnd('/')
        assumeTrue("no provider given", server.isNotBlank())
        val user = System.getProperty("xtream.user").orEmpty()
        val pass = System.getProperty("xtream.pass").orEmpty()
        val home = Language.named(System.getProperty("xtream.language"))
        val prefs = ContentPrefs(home = home)

        fun get(action: String?): Any? {
            val url = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (action?.let { "&action=$it" } ?: "")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "BoschTV/2.0 (Android TV)")   // some providers block unknown clients
            conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) error("The provider answered HTTP ${conn.responseCode} for ${action ?: "login"}.")
            return conn.inputStream.source().buffer().use { JsonReader.of(it).readJsonValue() }
        }
        fun categories(action: String): List<String> =
            (get(action) as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("category_name")?.toString() }

        // Check the login first: an expired or wrong account gives empty or refused lists.
        val status = ((get(null) as? Map<*, *>)?.get("user_info") as? Map<*, *>)?.get("status")?.toString()
        check(status.equals("Active", ignoreCase = true)) { "Account status is \"${status ?: "unknown (wrong server or login?)"}\", not Active." }

        val sets = listOf("Films" to categories("get_vod_categories"), "Series" to categories("get_series_categories"), "Live" to categories("get_live_categories"))
        val profile = ProviderProfile.of(sets.flatMap { it.second })
        val out = StringBuilder()
        out.appendLine("BoschTV provider report · home language: ${home.label} · English fallback on")
        out.appendLine(if (profile.labelsLanguages) "This provider labels languages: unlabelled categories count as home language (films/series) or are left out (live)."
            else "This provider hardly labels languages: everything is kept, titles' own labels still filter.")
        for ((kind, names) in sets) {
            val live = kind == "Live"
            val infos = names.map { it to Categories.classify(it, live, prefs, profile) }
            val used = infos.filter { it.second.lang != null }
            out.appendLine()
            out.appendLine("== $kind: ${used.size} of ${names.size} categories used")
            if (!live) {
                val bare = used.count { it.second.genres == 0L && it.second.services == 0L && it.second.flags == 0 }
                out.appendLine("   $bare used categories carry no genre, service or flag (titles there get genres from their details later)")
            }
            out.appendLine("-- used")
            used.forEach { (n, i) -> out.appendLine("   ${describe(i, live).padEnd(46)} $n") }
            out.appendLine("-- left out")
            infos.filter { it.second.lang == null }.forEach { (n, _) -> out.appendLine("   $n") }
        }
        val file = File("build/provider-report.txt")
        file.writeText(out.toString())
        println("Report written to ${file.absolutePath}")
    }

    private fun describe(i: CategoryInfo, live: Boolean): String = buildList {
        add(i.lang!!.name.lowercase() + if (!i.labelled) "?" else "")
        if (!live) {
            Genre.of(i.genres).forEach { add(it.label) }
            Service.of(i.services).forEach { add("[${it.label}]") }
            if (i.flags and Flags.CINEMA != 0) add("bioscoop")
            if (i.flags and Flags.TOP != 0) add("top")
            if (i.flags and Flags.CHRISTMAS != 0) add("kerst")
        }
    }.joinToString(" ")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
