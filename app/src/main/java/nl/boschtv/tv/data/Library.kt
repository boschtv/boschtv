package nl.boschtv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.data.db.AppDatabase
import nl.boschtv.tv.data.db.EpisodeEntity
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.MyListEntity
import nl.boschtv.tv.data.db.ProgressEntity
import nl.boschtv.tv.data.db.RatingEntity
import nl.boschtv.tv.data.db.TitleEntity
import kotlin.math.exp
import kotlin.math.sqrt

/** What we have learned about the household's taste from what was watched, liked and saved. */
class Taste(
    val genre: FloatArray,
    val service: FloatArray,
    val watched: Set<String>,
    val disliked: Set<String>,
    val recent: List<Card>,
) {
    val hasHistory get() = watched.isNotEmpty()
    fun genreScore(mask: Long): Float {
        var s = 0f; var n = 0
        for (g in Genre.entries) if (mask and g.bit != 0L) { s += genre[g.ordinal]; n++ }
        return if (n == 0) 0f else s / sqrt(n.toFloat())
    }
    fun serviceScore(mask: Long): Float {
        var s = 0f
        for (v in Service.entries) if (mask and v.bit != 0L) s = maxOf(s, service[v.ordinal])
        return s
    }
}

/** Watch progress, "Verder kijken", next/new episodes, Mijn lijst and thumbs. */
class Library(private val db: AppDatabase, private val catalog: Catalog) {
    private val dao get() = db.library()
    val version = MutableStateFlow(0)

    private fun changed() { version.value++ }

    suspend fun progress(contentId: String): ProgressEntity? = withContext(Dispatchers.IO) { dao.progress(contentId) }
    suspend fun progressOf(titleKey: String): List<ProgressEntity> = withContext(Dispatchers.IO) { dao.progressOf(titleKey) }

    suspend fun saveMovie(t: TitleEntity, streamId: Long, container: String?, positionMs: Long, durationMs: Long) =
        save(ProgressEntity(
            contentId = "m:${t.key}", titleKey = t.key, episodeId = null, season = null, number = null, label = null,
            image = t.dBackdrop ?: t.pBackdrop, streamId = streamId, container = container,
            positionMs = positionMs, durationMs = durationMs, finished = false, updatedAt = 0,
        ))

    suspend fun saveEpisode(e: EpisodeEntity, positionMs: Long, durationMs: Long) = save(episodeRow(e, positionMs, durationMs))

    private fun episodeRow(e: EpisodeEntity, positionMs: Long, durationMs: Long) = ProgressEntity(
        contentId = "e:${e.id}", titleKey = e.titleKey, episodeId = e.id, season = e.season, number = e.number,
        label = "S${e.season} · A${e.number} · ${e.name}", image = e.image, streamId = null, container = e.container,
        positionMs = positionMs, durationMs = durationMs, finished = false, updatedAt = 0,
    )

    private suspend fun save(p: ProgressEntity) = withContext(Dispatchers.IO) {
        val dur = p.durationMs.coerceAtLeast(0)
        // Under a minute in: not worth a "Verder kijken" tile yet.
        if (p.positionMs < 60_000 && dao.progress(p.contentId) == null) return@withContext
        val finished = dur > 0 && (p.positionMs >= dur * 0.93 || dur - p.positionMs < 120_000 && dur > 600_000)
        dao.saveProgress(p.copy(durationMs = dur, finished = finished, updatedAt = System.currentTimeMillis()))
        if (finished && p.episodeId != null) queueNext(p.titleKey, p.episodeId)
        changed()
    }

    /** Finishing an episode puts the next one in "Verder kijken". */
    private suspend fun queueNext(titleKey: String, finishedId: String) {
        val next = nextEpisode(titleKey, finishedId) ?: return
        if (dao.progress("e:${next.id}") == null) {
            dao.saveProgress(episodeRow(next, 0, next.durationSecs * 1000).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun nextEpisode(titleKey: String, afterId: String): EpisodeEntity? = withContext(Dispatchers.IO) {
        val eps = catalog.episodes(titleKey)
        eps.getOrNull(eps.indexOfFirst { it.id == afterId } + 1)
    }

    /**
     * After a catalog refresh: every series we are fully caught up on gets its episode list re-checked,
     * so a newly released episode shows up in "Verder kijken" by itself.
     */
    suspend fun refreshNextUp() = withContext(Dispatchers.IO) {
        val bySeries = dao.recentProgress(400).filter { it.episodeId != null }.groupBy { it.titleKey }
        var added = false
        for ((key, rows) in bySeries.entries.take(40)) {
            if (rows.any { !it.finished }) continue
            val last = rows.maxByOrNull { (it.season ?: 0) * 10_000 + (it.number ?: 0) } ?: continue
            catalog.detail(key, maxAgeMs = 3600_000L) ?: continue
            val eps = catalog.episodes(key)
            val next = eps.firstOrNull { it.season * 10_000 + it.number > (last.season ?: 0) * 10_000 + (last.number ?: 0) } ?: continue
            if (dao.progress("e:${next.id}") != null) continue
            dao.saveProgress(episodeRow(next, 0, next.durationSecs * 1000).copy(
                label = "Nieuwe aflevering · S${next.season} A${next.number}", updatedAt = System.currentTimeMillis(),
            ))
            added = true
        }
        if (added) changed()
    }

    /** One tile per title: the most recent unfinished film or episode. */
    suspend fun continueWatching(): List<Card> = withContext(Dispatchers.IO) {
        val rows = dao.recentProgress(300).filter { !it.finished }.distinctBy { it.titleKey }.take(30)
        val cards = catalog.byKeys(rows.map { it.titleKey })
        rows.mapNotNull { p ->
            val c = cards[p.titleKey] ?: return@mapNotNull null
            c.copy(
                progress = if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f,
                label = p.label ?: remaining(p), wideImage = p.image ?: c.backdrop,
            )
        }
    }

    private fun remaining(p: ProgressEntity): String? {
        val left = (p.durationMs - p.positionMs) / 60_000
        return if (p.durationMs > 0 && left > 0) "Nog $left min" else null
    }

    suspend fun rawContinue(limit: Int): List<ProgressEntity> = withContext(Dispatchers.IO) {
        dao.recentProgress(300).filter { !it.finished }.distinctBy { it.titleKey }.take(limit)
    }

    /** Where "Afspelen" on a series should start: the unfinished episode, else the one after the last watched. */
    suspend fun resumePoint(titleKey: String): Pair<EpisodeEntity, Long>? = withContext(Dispatchers.IO) {
        val eps = catalog.episodes(titleKey)
        if (eps.isEmpty()) return@withContext null
        val rows = dao.progressOf(titleKey).sortedByDescending { it.updatedAt }
        val current = rows.firstOrNull { !it.finished }
        if (current != null) eps.firstOrNull { it.id == current.episodeId }?.let { return@withContext it to current.positionMs }
        val lastDone = rows.firstOrNull { it.finished }
        val next = lastDone?.episodeId?.let { id -> eps.getOrNull(eps.indexOfFirst { it.id == id } + 1) }
        (next ?: eps.first()) to 0L
    }

    suspend fun removeFromContinue(titleKey: String) = withContext(Dispatchers.IO) { dao.clearProgress(titleKey); changed() }
    suspend fun clearHistory() = withContext(Dispatchers.IO) { dao.clearAllProgress(); changed() }

    suspend fun inList(key: String) = withContext(Dispatchers.IO) { dao.inList(key) }
    suspend fun toggleList(key: String): Boolean = withContext(Dispatchers.IO) {
        val now = !dao.inList(key)
        if (now) dao.addToList(MyListEntity(key, System.currentTimeMillis())) else dao.removeFromList(key)
        changed(); now
    }
    suspend fun myList(): List<Card> = withContext(Dispatchers.IO) {
        val keys = dao.myList()
        val cards = catalog.byKeys(keys)
        keys.mapNotNull { cards[it] }
    }

    suspend fun rating(key: String): Boolean? = withContext(Dispatchers.IO) { dao.rating(key)?.liked }
    suspend fun rate(key: String, liked: Boolean?) = withContext(Dispatchers.IO) {
        if (liked == null) dao.unrate(key) else dao.rate(RatingEntity(key, liked, System.currentTimeMillis()))
        changed()
    }

    suspend fun taste(): Taste = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val weight = HashMap<String, Float>()
        val order = ArrayList<String>()
        for (p in dao.recentProgress(400)) {
            val seen = if (p.finished) 1f else if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0.15f, 1f) else 0.15f
            val recency = exp(-((now - p.updatedAt) / 86_400_000.0) / 90.0).toFloat()
            if (p.titleKey !in weight) order += p.titleKey
            weight[p.titleKey] = maxOf(weight[p.titleKey] ?: 0f, seen * recency)
        }
        val ratings = dao.ratings()
        ratings.forEach { weight[it.titleKey] = if (it.liked) 2f else -1.2f }
        dao.myList().forEach { if (it !in weight) weight[it] = 0.6f }

        val cards = catalog.byKeys(weight.keys.toList())
        val g = FloatArray(Genre.entries.size)
        val s = FloatArray(Service.entries.size)
        for ((key, w) in weight) {
            val c = cards[key] ?: continue
            val gs = Genre.of(c.genres)
            gs.forEach { g[it.ordinal] += w / sqrt(gs.size.toFloat()) }
            if (w > 0) Service.of(c.services).forEach { s[it.ordinal] += w }
        }
        fun normalise(a: FloatArray) { val m = a.maxOrNull() ?: 0f; if (m > 0f) for (i in a.indices) a[i] = (a[i] / m).coerceAtLeast(-1f) }
        normalise(g); normalise(s)
        Taste(
            genre = g, service = s,
            watched = order.toSet(),
            disliked = ratings.filter { !it.liked }.map { it.titleKey }.toSet(),
            recent = order.mapNotNull { cards[it] }.filter { it.genres != 0L && (weight[it.key] ?: 0f) > 0.3f }.take(6),
        )
    }
}

val Card.kindLabel get() = if (kind == KIND_MOVIE) "Film" else "Serie"
