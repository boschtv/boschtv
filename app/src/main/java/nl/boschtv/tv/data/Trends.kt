package nl.boschtv.tv.data

import androidx.room.withTransaction
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import nl.boschtv.tv.core.JsonObj
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.core.asObj
import nl.boschtv.tv.data.db.AppDatabase
import nl.boschtv.tv.data.db.TrendEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate

/**
 * What is hot right now, from TMDB. The provider only knows what exists and when it was added; this adds
 * "what everyone is watching this week" and "what is new and popular on each streaming service in NL".
 * Titles are matched by TMDB id, which the catalog already carries.
 */
class Trends(private val db: AppDatabase, private val http: OkHttpClient, private val prefs: Prefs, private val token: String) {

    val enabled get() = token.isNotBlank()

    /**
     * Each streaming service's official logo (a TMDB image path), from TMDB's watch-provider list
     * (data by JustWatch). Fetched, not bundled: the app and the repository contain no brand artwork.
     * Empty without a TMDB token; the app then shows the service's name on its brand colour.
     */
    private val _logos = MutableStateFlow<Map<Service, String>>(emptyMap())
    val logos: StateFlow<Map<Service, String>> = _logos

    /** Last fetched logos, so they show immediately on start. */
    suspend fun loadLogos() {
        _logos.value = prefs.serviceLogos().orEmpty().split(';').mapNotNull { e ->
            val (k, v) = e.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            Service.entries.firstOrNull { it.name == k }?.let { it to v }
        }.toMap()
        if (_logos.value.isEmpty()) runCatching { refreshLogos(prefs.content().home.region) }
    }

    private val notTheService = Regex("""amazon channel|apple tv channel|roku|with ads|\bkids\b""", RegexOption.IGNORE_CASE)

    /** TMDB's name where it differs from ours. */
    private val tmdbNames = mapOf(Service.PRIME to "Amazon Prime Video", Service.APPLE to "Apple TV", Service.BBC to "BBC iPlayer")

    /** "Disney+" and "Disney Plus" alike: lower case, letters and digits only, "+" as "plus". */
    private fun norm(name: String) = nl.boschtv.tv.core.Titles.fold(name.replace("+", " plus ")).replace(" ", "")

    private suspend fun refreshLogos(region: String) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        // Per service the closest name wins: exact ("Discovery+"), then starting with it ("Peacock
        // Premium"), then any mention ("Investigation Discovery"). Ties go to the viewer's country.
        data class Pick(val score: Int, val order: Int, val logo: String)
        val best = HashMap<Service, Pick>()
        var order = 0
        for (r in listOf(region, "NL", "BE", "GB", "DE", "US").distinct()) for (type in listOf("tv", "movie")) {
            results("watch/providers/$type?watch_region=$r")
                .sortedBy { it.obj("display_priorities")?.long(r) ?: it.long("display_priority") ?: 999L }
                .forEach { o ->
                    val name = o.str("provider_name") ?: return@forEach
                    val logo = o.str("logo_path") ?: return@forEach
                    if (notTheService.containsMatchIn(name)) return@forEach
                    val n = norm(name)
                    order++
                    for (svc in Service.of(Service.mask(name))) {
                        val target = norm(tmdbNames[svc] ?: svc.label)
                        val score = when { n == target -> 0; n.startsWith(target) -> 1; else -> 2 }
                        val pick = Pick(score, order, logo)
                        val old = best[svc]
                        if (old == null || score < old.score) best[svc] = pick
                    }
                }
            delay(120)
        }
        if (best.isEmpty()) return@withContext
        val found = best.mapValues { it.value.logo }
        _logos.value = found
        prefs.saveServiceLogos(found.entries.joinToString(";") { "${it.key.name}=${it.value}" })
    }

    /** TMDB's ids for the services (the same in every country; availability is filtered by region). */
    private val providers = mapOf(
        Service.NETFLIX to 8, Service.PRIME to 119, Service.DISNEY to 337, Service.VIDEOLAND to 72, Service.HBO to 1899,
        Service.APPLE to 350, Service.SKYSHOWTIME to 1773, Service.VIAPLAY to 76, Service.NPO to 360, Service.NLZIET to 472,
        Service.PATHE to 71, Service.CANAL to 381, Service.CRUNCHYROLL to 283,
    )

    suspend fun keys(listId: String): List<String> = withContext(Dispatchers.IO) { db.trends().keys(listId) }

    /** Refreshes at most twice a day. Returns true when new lists were stored. */
    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        if (!force && System.currentTimeMillis() - prefs.lastTrends() < 12 * 3600_000L) return@withContext false
        val home = prefs.content().home
        val lang = home.locale
        val region = home.region
        runCatching { refreshLogos(region) }
        val ok = runCatching {
            // This week, films and series in one ranking.
            val trend = ArrayList<String>()
            for (page in 1..8) {
                results("trending/all/week?language=$lang&page=$page").forEach { o -> key(o, o.str("media_type"))?.let(trend::add) }
                delay(120)
            }
            store("trend", trend)

            // Per service: released in the last year and a half, available on that service in the viewer's country, most popular first.
            val since = LocalDate.now().minusMonths(18)
            for ((service, id) in providers) {
                val tv = results("discover/tv?watch_region=$region&with_watch_providers=$id&sort_by=popularity.desc&first_air_date.gte=$since&language=$lang")
                    .mapNotNull { key(it, "tv") }
                delay(120)
                val movies = results("discover/movie?watch_region=$region&with_watch_providers=$id&sort_by=popularity.desc&primary_release_date.gte=$since&language=$lang")
                    .mapNotNull { key(it, "movie") }
                delay(120)
                // Interleave so the row mixes series and films.
                val mixed = ArrayList<String>()
                for (i in 0 until maxOf(tv.size, movies.size)) { tv.getOrNull(i)?.let(mixed::add); movies.getOrNull(i)?.let(mixed::add) }
                store("svc:${service.name}", mixed)
            }
            prefs.markTrends()
        }.isSuccess
        ok
    }

    private val tmdbKey = Regex("""^([ms]):t(\d+)""")

    /**
     * TMDB's "people who liked this also liked" list for one title, as catalog keys in TMDB's order.
     * Fetched the first time a title's page is opened and kept, so reopening is instant.
     */
    suspend fun recommendations(titleKey: String): List<String> = withContext(Dispatchers.IO) {
        val listId = "rec:$titleKey"
        val cached = db.trends().keys(listId)
        if (cached.isNotEmpty() || !enabled) return@withContext cached
        val m = tmdbKey.find(titleKey) ?: return@withContext emptyList()
        val type = if (m.groupValues[1] == "m") "movie" else "tv"
        val keys = runCatching {
            (1..2).flatMap { page -> results("$type/${m.groupValues[2]}/recommendations?language=${prefs.content().home.locale}&page=$page") }.mapNotNull { key(it, type) }
        }.getOrDefault(emptyList())
        store(listId, keys)
        keys
    }

    private fun key(o: JsonObj, type: String?): String? {
        val id = o.long("id") ?: return null
        return when (type) { "movie" -> "m:t$id"; "tv" -> "s:t$id"; else -> null }
    }

    private suspend fun store(listId: String, keys: List<String>) {
        val rows = keys.distinct().mapIndexed { i, k -> TrendEntity(listId, i, k) }
        if (rows.isEmpty()) return
        db.withTransaction { db.trends().clear(listId); db.trends().insert(rows) }
    }

    /**
     * Stores the title's description in the viewer's language from TMDB (when that is not English and
     * TMDB has a translation). Returns true when a description was stored. Remembers the language
     * asked, also when TMDB has none, so a title is looked up once per language.
     */
    suspend fun localizePlot(key: String): Boolean = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext false
        val home = prefs.content().home
        if (home == nl.boschtv.tv.core.Language.ENGLISH) return@withContext false
        val m = tmdbKey.find(key) ?: return@withContext false
        val type = if (m.groupValues[1] == "m") "movie" else "tv"
        val text = runCatching { item("$type/${m.groupValues[2]}?language=${home.locale}")?.str("overview") }.getOrNull()?.takeIf { it.isNotBlank() }
        db.catalog().setLocalPlot(key, text, home.locale)
        text != null
    }

    private fun item(path: String): JsonObj? {
        val req = Request.Builder().url("https://api.themoviedb.org/3/$path")
            .header("Authorization", "Bearer $token").header("accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("TMDB ${resp.code}")
            return JsonReader.of(resp.body!!.source()).readJsonValue().asObj()
        }
    }

    private fun results(path: String): List<JsonObj> {
        val req = Request.Builder().url("https://api.themoviedb.org/3/$path")
            .header("Authorization", "Bearer $token").header("accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("TMDB ${resp.code}")
            val root = JsonReader.of(resp.body!!.source()).readJsonValue().asObj() ?: return emptyList()
            return root.list("results").mapNotNull { it.asObj() }
        }
    }
}
