package nl.boschtv.tv.data

import nl.boschtv.tv.R
import nl.boschtv.tv.ui.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.data.db.ChannelDao
import nl.boschtv.tv.data.db.ChannelEntity
import nl.boschtv.tv.data.db.ContentIds
import nl.boschtv.tv.data.db.EpisodeEntity
import nl.boschtv.tv.data.db.FavoriteEntity
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.MyListEntity
import nl.boschtv.tv.data.db.ProgressEntity
import nl.boschtv.tv.data.db.RatingEntity
import nl.boschtv.tv.data.db.TitleEntity
import nl.boschtv.tv.data.db.UserDatabase
import kotlin.math.exp
import kotlin.math.sqrt

/** What we have learned about one profile's taste from what was watched, liked and saved. */
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

/**
 * Watch progress, "Continue watching", next/new episodes, My List, thumbs and favourite channels.
 * Every change to what a profile made goes through here.
 */
class Library(private val user: UserDatabase, private val channels: ChannelDao, private val catalog: Catalog, private val profiles: Profiles) {
    private val dao get() = user.library()
    val version = MutableStateFlow(0)

    /** Everything read or written here is the active profile's. */
    private suspend fun pid() = profiles.id()

    /** Screens that show the library rebuild; also called when another profile starts watching. */
    fun changed() { version.value++ }

    /** [contentId] comes from [ContentIds]. */
    suspend fun progress(contentId: String): ProgressEntity? = withContext(Dispatchers.IO) { dao.progress(pid(), contentId) }
    suspend fun progressOf(titleKey: String): List<ProgressEntity> = withContext(Dispatchers.IO) { dao.progressOf(pid(), titleKey) }

    suspend fun saveMovie(t: TitleEntity, streamId: Long, container: String?, positionMs: Long, durationMs: Long) =
        save(ProgressEntity(
            profileId = pid(), contentId = ContentIds.movie(t.key), titleKey = t.key, season = null, number = null, label = null,
            image = t.dBackdrop ?: t.pBackdrop, streamId = streamId, container = container,
            positionMs = positionMs, durationMs = durationMs, finished = false, updatedAt = 0,
        ))

    suspend fun saveEpisode(e: EpisodeEntity, positionMs: Long, durationMs: Long) = save(episodeRow(pid(), e, positionMs, durationMs), e)

    private fun episodeRow(profile: Long, e: EpisodeEntity, positionMs: Long, durationMs: Long) = ProgressEntity(
        profileId = profile, contentId = ContentIds.of(e), titleKey = e.titleKey, season = e.season, number = e.number,
        label = Texts.get(R.string.episode_line, e.season, e.number, e.name), image = e.image, streamId = null, container = null,
        positionMs = positionMs, durationMs = durationMs, finished = false, updatedAt = 0,
    )

    private suspend fun save(p: ProgressEntity, episode: EpisodeEntity? = null) = withContext(Dispatchers.IO) {
        val dur = p.durationMs.coerceAtLeast(0)
        // Under a minute in: not worth a "Continue watching" tile yet.
        if (p.positionMs < 60_000 && dao.progress(p.profileId, p.contentId) == null) return@withContext
        val finished = dur > 0 && (p.positionMs >= dur * 0.93 || dur - p.positionMs < 120_000 && dur > 600_000)
        dao.saveProgress(p.copy(durationMs = dur, finished = finished, updatedAt = System.currentTimeMillis()))
        if (finished && episode != null) queueNext(p.profileId, episode)
        changed()
    }

    /** Finishing an episode puts the next one in "Continue watching". */
    private suspend fun queueNext(profile: Long, finished: EpisodeEntity) {
        val next = nextEpisode(finished.titleKey, finished.id) ?: return
        if (dao.progress(profile, ContentIds.of(next)) == null) {
            dao.saveProgress(episodeRow(profile, next, 0, next.durationSecs * 1000).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun nextEpisode(titleKey: String, afterId: String): EpisodeEntity? = withContext(Dispatchers.IO) {
        val eps = catalog.episodes(titleKey)
        eps.getOrNull(eps.indexOfFirst { it.id == afterId } + 1)
    }

    /**
     * After a catalog refresh: every series we are fully caught up on gets its episode list re-checked,
     * so a newly released episode shows up in "Continue watching" by itself. Done for every profile.
     */
    suspend fun refreshNextUp() = withContext(Dispatchers.IO) {
        var added = false
        for (profile in profiles.ids()) added = refreshNextUp(profile) || added
        if (added) changed()
    }

    private suspend fun refreshNextUp(profile: Long): Boolean {
        val bySeries = dao.recentProgress(profile, 400).filter { it.isEpisode }.groupBy { it.titleKey }
        var added = false
        for ((key, rows) in bySeries.entries.take(40)) {
            if (rows.any { !it.finished }) continue
            val last = rows.maxByOrNull { (it.season ?: 0) * 10_000 + (it.number ?: 0) } ?: continue
            catalog.detail(key, maxAgeMs = 3600_000L) ?: continue
            val eps = catalog.episodes(key)
            val next = eps.firstOrNull { it.season * 10_000 + it.number > (last.season ?: 0) * 10_000 + (last.number ?: 0) } ?: continue
            if (dao.progress(profile, ContentIds.of(next)) != null) continue
            dao.saveProgress(episodeRow(profile, next, 0, next.durationSecs * 1000).copy(
                label = nl.boschtv.tv.ui.Texts.get(nl.boschtv.tv.R.string.new_episode, next.season, next.number), updatedAt = System.currentTimeMillis(),
            ))
            added = true
        }
        return added
    }

    /** One tile per title: the most recent unfinished film or episode. */
    suspend fun continueWatching(): List<Card> = withContext(Dispatchers.IO) {
        val rows = dao.recentProgress(pid(), 300).filter { !it.finished }.distinctBy { it.titleKey }.take(30)
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
        return if (p.durationMs > 0 && left > 0) Texts.get(R.string.min_left_short, left.toInt()) else null
    }

    suspend fun rawContinue(limit: Int): List<ProgressEntity> = withContext(Dispatchers.IO) {
        dao.recentProgress(pid(), 300).filter { !it.finished }.distinctBy { it.titleKey }.take(limit)
    }

    /** Where "Play" on a series should start: the unfinished episode, else the one after the last watched. */
    suspend fun resumePoint(titleKey: String): Pair<EpisodeEntity, Long>? = withContext(Dispatchers.IO) {
        val eps = catalog.episodes(titleKey)
        if (eps.isEmpty()) return@withContext null
        val rows = dao.progressOf(pid(), titleKey).sortedByDescending { it.updatedAt }
        // The history knows an episode by season and number; which video that is comes from the current catalog.
        val current = rows.firstOrNull { !it.finished }
        val lastDone = rows.firstOrNull { it.finished }
        resumeEpisode(eps, current?.season, current?.number, lastDone?.season, lastDone?.number)
            .let { (e, isCurrent) -> e to if (isCurrent) current!!.positionMs else 0L }
    }

    suspend fun removeFromContinue(titleKey: String) = withContext(Dispatchers.IO) { dao.clearProgress(pid(), titleKey); changed() }
    suspend fun clearHistory() = withContext(Dispatchers.IO) { dao.clearAllProgress(pid()); changed() }

    suspend fun inList(key: String) = withContext(Dispatchers.IO) { dao.inList(pid(), key) }
    suspend fun toggleList(key: String): Boolean = withContext(Dispatchers.IO) {
        val profile = pid()
        val now = !dao.inList(profile, key)
        if (now) dao.addToList(MyListEntity(profile, key, System.currentTimeMillis())) else dao.removeFromList(profile, key)
        changed(); now
    }
    suspend fun myList(): List<Card> = withContext(Dispatchers.IO) {
        val keys = dao.myList(pid())
        val cards = catalog.byKeys(keys)
        keys.mapNotNull { cards[it] }
    }

    suspend fun rating(key: String): Boolean? = withContext(Dispatchers.IO) { dao.rating(pid(), key)?.liked }
    suspend fun rate(key: String, liked: Boolean?) = withContext(Dispatchers.IO) {
        val profile = pid()
        if (liked == null) dao.unrate(profile, key) else dao.rate(RatingEntity(profile, key, liked, System.currentTimeMillis()))
        changed()
    }

    /* ---------- favourite channels ---------- */

    private suspend fun found(favorites: List<FavoriteEntity>): Map<FavoriteEntity, List<ChannelEntity>> =
        matchFavorites(favorites, channels.byNames(favorites.map { it.channel }), channels.byEpgIds(favorites.mapNotNull { it.epgId }))

    /** The active profile's favourites as they are in the current channel list, in the order they were starred. */
    suspend fun favoriteChannels(): List<ChannelEntity> = withContext(Dispatchers.IO) {
        found(dao.favorites(pid())).values.mapNotNull { it.firstOrNull() }.distinctBy { it.id }
    }

    /** Ids of every channel that shows a star for the active profile. */
    fun favoriteChannelIds(): Flow<Set<Long>> = flow {
        emitAll(dao.observeFavorites(pid()).map { favs -> found(favs).values.flatMapTo(HashSet()) { list -> list.map { it.id } } })
    }.flowOn(Dispatchers.IO)

    suspend fun toggleFavorite(ch: ChannelEntity) = withContext(Dispatchers.IO) {
        val profile = pid()
        val starred = found(dao.favorites(profile)).entries.firstOrNull { (_, list) -> list.any { it.id == ch.id } }?.key
        if (starred != null) dao.removeFavorite(profile, starred.channel)
        else if (ch.search.isNotBlank()) dao.addFavorite(FavoriteEntity(profile, ch.search, ch.epgId?.takeIf { it.isNotBlank() }, System.currentTimeMillis()))
    }

    suspend fun taste(): Taste = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val profile = pid()
        val weight = HashMap<String, Float>()
        val order = ArrayList<String>()
        for (p in dao.recentProgress(profile, 400)) {
            val seen = if (p.finished) 1f else if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0.15f, 1f) else 0.15f
            val recency = exp(-((now - p.updatedAt) / 86_400_000.0) / 90.0).toFloat()
            if (p.titleKey !in weight) order += p.titleKey
            weight[p.titleKey] = maxOf(weight[p.titleKey] ?: 0f, seen * recency)
        }
        val ratings = dao.ratings(profile)
        ratings.forEach { weight[it.titleKey] = if (it.liked) 2f else -1.2f }
        dao.myList(profile).forEach { if (it !in weight) weight[it] = 0.6f }

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

val Card.kindLabel get() = nl.boschtv.tv.ui.Texts.get(if (kind == KIND_MOVIE) nl.boschtv.tv.R.string.kind_movie else nl.boschtv.tv.R.string.kind_series)

/**
 * Which channels of the current list each favourite stands for. A favourite is a channel name: every
 * channel carrying that name ([byName], the list sorted as on screen) is it. Only when no channel has the
 * name any more (another provider writes it differently) does the guide id decide, and then the first
 * channel with that id ([byEpgId]) is taken, not all its HD and 4K variants. Favourites without a channel are left out.
 */
internal fun matchFavorites(
    favorites: List<FavoriteEntity>, byName: List<ChannelEntity>, byEpgId: List<ChannelEntity>,
): Map<FavoriteEntity, List<ChannelEntity>> {
    val names = byName.groupBy { it.search }
    val out = LinkedHashMap<FavoriteEntity, List<ChannelEntity>>()
    for (f in favorites) {
        val list = names[f.channel] ?: f.epgId?.let { id -> byEpgId.firstOrNull { it.epgId == id } }?.let(::listOf) ?: continue
        out[f] = list
    }
    return out
}

/**
 * The episode to play and whether it is the one in progress. An episode this provider does not list
 * (it numbers a season differently, or dropped one) never sends the viewer back to the start of the
 * series: the first episode after that point is taken instead.
 */
internal fun resumeEpisode(eps: List<EpisodeEntity>, season: Int?, number: Int?, doneSeason: Int?, doneNumber: Int?): Pair<EpisodeEntity, Boolean> {
    fun EpisodeEntity.at() = this.season.toLong() * 100_000 + this.number
    if (season != null && number != null) {
        eps.firstOrNull { it.season == season && it.number == number }?.let { return it to true }
        eps.firstOrNull { it.at() > season.toLong() * 100_000 + number }?.let { return it to false }
    }
    if (doneSeason != null && doneNumber != null) {
        eps.firstOrNull { it.at() > doneSeason.toLong() * 100_000 + doneNumber }?.let { return it to false }
    }
    return eps.first() to false
}
