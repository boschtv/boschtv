package nl.boschtv.tv.data

import nl.boschtv.tv.R
import nl.boschtv.tv.ui.Texts
import androidx.compose.runtime.Immutable
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.JsonObj
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.core.Titles
import nl.boschtv.tv.core.asObj
import nl.boschtv.tv.data.db.AppDatabase
import nl.boschtv.tv.data.db.EpisodeEntity
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.ROW_COLUMNS
import nl.boschtv.tv.data.db.TitleEntity
import nl.boschtv.tv.data.db.TitleRow
import nl.boschtv.tv.data.xtream.XtreamClient

/** One tile. Everything a row, grid or the hero needs is in here, so focusing a tile never hits the network. */
@Immutable
data class Card(
    val key: String, val kind: Int, val title: String, val year: Int?, val poster: String?, val backdrop: String?,
    val rating: Double, val genres: Long, val services: Long, val flags: Int, val lang: Int, val addedTs: Long,
    val plot: String?, val progress: Float? = null, val label: String? = null, val wideImage: String? = null,
) {
    val isNew get() = addedTs > 0 && System.currentTimeMillis() / 1000 - addedTs < 10 * 86400 &&
        (year == null || year >= java.time.Year.now().value - 1)
}

fun TitleRow.toCard() = Card(key, kind, name, year, poster, backdrop, rating, genres, services, flags, lang, addedTs, plot)
fun TitleEntity.toCard() = Card(
    key, kind, name, year, poster, dBackdrop ?: pBackdrop, rating, genres, services, flags, lang, addedTs, lPlot ?: dPlot ?: pPlot,
)

enum class Sort { NEW, RATING, YEAR, AZ, TOP }

/** Declarative catalog filter; the single place SQL for title lists is built. */
@Immutable
data class TitleQuery(
    val kind: Int? = null,
    val genresAll: Long = 0,
    val genresAny: Long = 0,
    val genresNone: Long = Genre.SPORT.bit or Genre.KIDS.bit,
    val services: Long = 0,
    val flags: Int = 0,
    val dutchOnly: Boolean = false,
    val dutchFirst: Boolean = true,
    val year: Int? = null,
    val minYear: Int? = null,
    val maxYear: Int? = null,
    val minRating: Double = 0.0,
    val text: String? = null,
    val sort: Sort = Sort.NEW,
    val limit: Int = 30,
    val offset: Int = 0,
) {
    private fun where(args: MutableList<Any>): String {
        val w = ArrayList<String>()
        kind?.let { w += "kind = ?"; args += it }
        if (genresAll != 0L) { w += "(genres & ?) = ?"; args += genresAll; args += genresAll }
        if (genresAny != 0L) { w += "(genres & ?) != 0"; args += genresAny }
        // "Not sport" must not hide a title that is sport plus something else we asked for.
        val none = genresNone and (genresAll or genresAny).inv()
        if (none != 0L) { w += "(genres & ?) = 0"; args += none }
        if (services != 0L) { w += "(services & ?) != 0"; args += services }
        if (flags != 0) { w += "(flags & ?) != 0"; args += flags }
        if (dutchOnly) w += "lang = 0"
        year?.let { w += "year = ?"; args += it }
        minYear?.let { w += "year >= ?"; args += it }
        maxYear?.let { w += "year <= ?"; args += it }
        if (minRating > 0) { w += "rating >= ?"; args += minRating }
        if (sort == Sort.TOP) w += "topRank > 0"
        text?.let { w += "search LIKE ?"; args += "%$it%" }
        return if (w.isEmpty()) "" else " WHERE " + w.joinToString(" AND ")
    }

    fun select(): SimpleSQLiteQuery {
        val args = ArrayList<Any>()
        val lead = if (dutchFirst) "lang, " else ""
        val order = when {
            text != null -> "(search LIKE ?) DESC, lang, rating DESC".also { }
            sort == Sort.NEW -> "${lead}addedTs DESC"
            sort == Sort.RATING -> "${lead}rating DESC, addedTs DESC"
            sort == Sort.YEAR -> "year DESC, addedTs DESC"
            sort == Sort.AZ -> "search"
            else -> "topRank"
        }
        val sql = StringBuilder("SELECT $ROW_COLUMNS FROM titles").append(where(args)).append(" ORDER BY ").append(order)
        if (text != null) args += "$text%"
        sql.append(" LIMIT ? OFFSET ?"); args += limit; args += offset
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    fun count(): SimpleSQLiteQuery {
        val args = ArrayList<Any>()
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM titles" + where(args), args.toTypedArray())
    }
}

data class Version(val streamId: Long, val container: String?, val label: String)

class Catalog(
    private val db: AppDatabase,
    private val client: XtreamClient,
    private val prefs: Prefs,
) {
    private val dao get() = db.catalog()

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { dao.titleCount() == 0 && db.channels().count() == 0 }

    suspend fun cards(q: TitleQuery): List<Card> = withContext(Dispatchers.IO) { dao.rows(q.select()).map { it.toCard() } }
    suspend fun count(q: TitleQuery): Int = withContext(Dispatchers.IO) { dao.count(q.count()) }
    suspend fun byKeys(keys: List<String>): Map<String, Card> = withContext(Dispatchers.IO) {
        keys.chunked(500).flatMap { dao.rowsByKeys(it) }.associate { it.key to it.toCard() }
    }
    suspend fun title(key: String): TitleEntity? = withContext(Dispatchers.IO) { dao.title(key) }

    suspend fun search(text: String, limit: Int = 60, services: Long = 0): List<Card> {
        val q = Titles.fold(text)
        if (q.length < 2) return emptyList()
        return cards(TitleQuery(text = q, genresNone = 0, limit = limit, services = services))
    }

    /** Services that actually have content on this line, most titles first. */
    @Volatile private var serviceCache: List<Service>? = null
    fun invalidate() { serviceCache = null }

    suspend fun services(): List<Service> = serviceCache ?: withContext(Dispatchers.IO) {
        val counts = IntArray(Service.entries.size)
        for (m in dao.serviceMasks()) for (s in Service.entries) if (m and s.bit != 0L) counts[s.ordinal]++
        Service.entries.filter { counts[it.ordinal] >= 12 }.also { serviceCache = it }
    }

    fun versions(t: TitleEntity): List<Version> = buildList {
        add(Version(t.sourceId, t.container, if (t.lang == 0) "original" else "en"))
        t.alts?.lines()?.forEach { line ->
            val p = line.split(';')
            val id = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
            add(Version(id, p.getOrNull(1)?.ifBlank { null }, p.getOrNull(2) ?: "other"))
        }
    }

    /* ---------- detail ---------- */

    /** Title with plot, backdrop, cast and (for series) a fresh episode list. Cached in the database. */
    suspend fun detail(key: String, maxAgeMs: Long = 6 * 3600_000L): TitleEntity? = withContext(Dispatchers.IO) {
        val t = dao.title(key) ?: return@withContext null
        val fresh = System.currentTimeMillis() - t.detailAt < maxAgeMs
        if (t.kind == KIND_MOVIE) {
            if (t.enriched == 0) runCatching { fetchMovie(t) }
        } else if (!fresh) {
            runCatching { fetchSeries(t) }
        }
        dao.title(key)
    }

    suspend fun fetchMovie(t: TitleEntity) {
        val src = prefs.source()
        val info = client.obj(src, "get_vod_info", "vod_id" to t.sourceId.toString())?.obj("info")
        if (info == null) { dao.markEnrichFailed(t.key); return }
        saveInfo(t.key, info, info.long("duration_secs") ?: 0L)
    }

    private suspend fun saveInfo(key: String, info: JsonObj, durationSecs: Long) {
        dao.setDetail(
            key = key,
            plot = (info.str("plot") ?: info.str("description"))?.take(1200),
            backdrop = info.firstString("backdrop_path"),
            genres = Genre.mask(info.str("genre")),
            durationSecs = durationSecs,
            actors = (info.str("cast") ?: info.str("actors"))?.take(300),
            director = info.str("director")?.take(120),
            trailer = info.str("youtube_trailer"),
            at = System.currentTimeMillis(),
        )
    }

    private suspend fun fetchSeries(t: TitleEntity) {
        val src = prefs.source()
        val root = client.obj(src, "get_series_info", "series_id" to t.sourceId.toString()) ?: return
        val out = ArrayList<EpisodeEntity>()
        fun add(seasonHint: Int, list: Any?) {
            (list as? List<*>)?.forEach { raw ->
                val e = raw.asObj() ?: return@forEach
                val id = e.str("id") ?: return@forEach
                val number = e.int("episode_num") ?: (out.size + 1)
                val info = e.obj("info")
                out += EpisodeEntity(
                    id = id, titleKey = t.key, season = e.int("season") ?: seasonHint, number = number,
                    name = Titles.episode(e.str("title"), number) { Texts.get(R.string.episode_n, it) }, container = e.str("container_extension"),
                    durationSecs = info?.long("duration_secs") ?: 0L,
                    image = info?.str("movie_image") ?: info?.str("cover_big"),
                    plot = info?.str("plot")?.take(500),
                )
            }
        }
        when (val eps = root.map["episodes"]) {
            is Map<*, *> -> eps.forEach { (k, v) -> add((k as? String)?.toIntOrNull() ?: 1, v) }
            is List<*> -> eps.forEachIndexed { i, v -> add(i + 1, v) }
        }
        db.withTransaction {
            if (out.isNotEmpty()) { dao.deleteEpisodes(t.key); dao.insertEpisodes(out) }
            val info = root.obj("info")
            if (info != null) saveInfo(t.key, info, 0L) else dao.touchDetail(t.key, System.currentTimeMillis())
        }
    }

    /** Stream to preview a title with: the film itself, or the first episode of a series. */
    suspend fun previewUrl(key: String): String? = withContext(Dispatchers.IO) {
        val t = dao.title(key) ?: return@withContext null
        val src = prefs.source()
        if (t.kind == KIND_MOVIE) return@withContext src.movie(t.sourceId, t.container)
        var eps = dao.episodes(key)
        if (eps.isEmpty()) { runCatching { fetchSeries(t) }; eps = dao.episodes(key) }
        eps.firstOrNull()?.let { src.episode(it.id, it.container) }
    }

    suspend fun episodes(key: String): List<EpisodeEntity> = withContext(Dispatchers.IO) { dao.episodes(key) }
    suspend fun episode(id: String): EpisodeEntity? = withContext(Dispatchers.IO) { dao.episode(id) }
}

/** Fills in genre/plot/backdrop for films in the background (the film list itself carries none). Newest Dutch titles first. */
class Enricher(private val db: AppDatabase, private val catalog: Catalog) {
    @Volatile private var running = false

    /**
     * One request at a time with a pause in between, a small batch per app start, and it backs off the
     * moment the provider stops answering. A failed request is simply tried again another time.
     */
    suspend fun run(maxItems: Int) = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        try {
            db.catalog().retryFailedEnrichment()
            var done = 0
            var failures = 0
            while (done < maxItems && failures < 3) {
                val batch = db.catalog().toEnrich(10)
                if (batch.isEmpty()) break
                for (target in batch) {
                    val t = db.catalog().title(target.key) ?: continue
                    if (runCatching { catalog.fetchMovie(t) }.isSuccess) failures = 0 else failures++
                    done++
                    if (failures >= 3) break
                    kotlinx.coroutines.delay(1500)
                }
            }
        } finally {
            running = false
        }
    }
}
