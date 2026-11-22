package nl.boschtv.tv.data

import nl.boschtv.tv.R
import nl.boschtv.tv.ui.Texts
import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import nl.boschtv.tv.App
import nl.boschtv.tv.core.Categories
import nl.boschtv.tv.core.ContentPrefs
import nl.boschtv.tv.core.Tags
import nl.boschtv.tv.core.ProviderProfile
import nl.boschtv.tv.core.CategoryInfo
import nl.boschtv.tv.core.Flags
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.JsonObj
import nl.boschtv.tv.core.Lang
import nl.boschtv.tv.core.Titles
import nl.boschtv.tv.data.db.AppDatabase
import nl.boschtv.tv.data.db.ChannelEntity
import nl.boschtv.tv.data.db.ChannelGroupEntity
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.KIND_SERIES
import nl.boschtv.tv.data.db.TitleCore
import nl.boschtv.tv.data.xtream.AuthException
import nl.boschtv.tv.data.xtream.Source
import nl.boschtv.tv.data.xtream.XtreamClient
import java.util.concurrent.TimeUnit

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val progress: Float, val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

/** A film/series while it is being merged across provider categories. */
private class Candidate(
    val key: String, val kind: Int, var sourceId: Long, var name: String, val year: Int?,
    var poster: String?, var container: String?, var addedTs: Long, var rating: Double, var lang: Int,
    var genres: Long, var services: Long, var flags: Int, var topRank: Int,
    var dubbed: Boolean, var quality: String?, var plot: String?, var backdrop: String?,
    /** Its category or its own name says which language it is for (a "MULTI" listing says nothing). */
    var labelled: Boolean = true,
) {
    val alts = LinkedHashMap<String, String>()   // label -> "id;ext;label"
    // Lowest wins. Among otherwise equal listings, the one made for the viewer's language goes before one
    // that names no language: a "MULTI" copy carries every dub at once, which makes it several times
    // heavier for the same picture, and its subtitles are a guess.
    val score get() = lang * 100 + (if (dubbed) 10 else 0) + (if (quality != null) 4 else 0) + (if (labelled) 0 else 2)
    val altLabel get() = when { dubbed -> "dub"; else -> quality }
}

class SyncManager(
    private val db: AppDatabase,
    private val client: XtreamClient,
    private val prefs: Prefs,
    private val onChanged: () -> Unit = {},
) {
    val state = MutableStateFlow<SyncState>(SyncState.Idle)
    /** Bumped whenever the catalog changed, so screens know to reload. */
    val version = MutableStateFlow(0)
    private val mutex = Mutex()


    /**
     * The catalog update parses lists of tens of megabytes. On its own low-priority thread it only
     * uses processor time the screen does not need, so the app never stutters or freezes while it runs.
     */
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "catalog-sync")
    }.asCoroutineDispatcher()

    /** Full refresh. The old catalog stays usable until the new one is completely in. */
    suspend fun sync(src: Source): Result<Unit> = withContext(background) {
        if (mutex.isLocked) return@withContext Result.success(Unit)
        mutex.withLock {
            val result = runCatching { run(src) }
            result.onSuccess { state.value = SyncState.Idle; onChanged(); version.value++ }
            result.onFailure { e ->
                if (e is AuthException) prefs.setProblem(e.message)
                state.value = SyncState.Failed(e.message ?: Texts.get(R.string.sync_failed))
            }
            result
        }
    }

    /**
     * A provider sometimes answers with a stripped list (no posters, no TMDB ids), seen when the line was
     * busy elsewhere. Storing that would blank the artwork and duplicate titles, so the update is dropped
     * and the catalog we have stays. Judged against the catalog already stored, not a fixed limit: a
     * provider that always lacks many posters keeps working; only a sudden jump is refused.
     */
    private suspend fun complete(items: Collection<Candidate>, kind: Int) {
        if (items.size < 500) return
        val before = db.catalog().missingPosterPercent(kind) ?: return   // first sync: nothing to compare with
        val now = items.count { it.poster.isNullOrBlank() } * 100 / items.size
        if (now > before + 25) throw IllegalStateException(Texts.get(R.string.incomplete_list))
    }

    private fun step(p: Float, msg: String) { state.value = SyncState.Running(p, msg) }

    private suspend fun run(src: Source) {
        step(0.02f, Texts.get(R.string.step_connect))
        val account = client.account(src)
        prefs.saveAccount(account)
        if (!account.active) throw AuthException(account.message)
        val content = prefs.content()
        val gen = System.currentTimeMillis()

        step(0.06f, Texts.get(R.string.step_categories))
        val vodCats = client.categories(src, "get_vod_categories")
        val seriesCats = client.categories(src, "get_series_categories")
        val liveCats = client.categories(src, "get_live_categories")
        // How does this provider write its catalog? Decided once, before anything is classified.
        val profile = ProviderProfile.of((vodCats + seriesCats + liveCats).map { it.name })
        val liveInfo = liveCats.associate { it.id to Categories.classify(it.name, true, content, profile) }
        val vodInfo = vodCats.associate { it.id to Categories.classify(it.name, false, content, profile) }
        val seriesInfo = seriesCats.associate { it.id to Categories.classify(it.name, false, content, profile) }

        step(0.12f, Texts.get(R.string.step_movies))
        val movies = collect(src, "get_vod_streams", KIND_MOVIE, vodInfo, content)
        complete(movies, KIND_MOVIE)
        step(0.5f, Texts.get(R.string.step_save_movies))
        store(movies, gen)

        step(0.55f, Texts.get(R.string.step_series))
        val series = collect(src, "get_series", KIND_SERIES, seriesInfo, content)
        complete(series, KIND_SERIES)
        step(0.82f, Texts.get(R.string.step_save_series))
        store(series, gen)

        step(0.86f, Texts.get(R.string.step_channels))
        val channelCount = syncChannels(src, liveCats.map { it.id }, liveInfo, gen, content)

        step(0.97f, Texts.get(R.string.step_finish))
        db.withTransaction {
            db.catalog().pruneTitles(gen)
            db.catalog().refreshGenres()
            db.channels().pruneChannels(gen)
            db.channels().pruneProgrammes(System.currentTimeMillis() - 6 * 3600_000L)
        }
        prefs.markSynced()
        prefs.saveSyncReport(report(profile, vodInfo.values + seriesInfo.values, liveInfo.values, movies + series, channelCount))
    }

    /** "1,204 of 1,402 categories used · 92% with a genre · …" for Settings. */
    private fun report(profile: ProviderProfile, vod: Collection<CategoryInfo>, live: Collection<CategoryInfo>, titles: Collection<Candidate>, channels: Int): String {
        val all = vod + live
        val used = all.count { it.lang != null }
        val withGenre = if (titles.isEmpty()) 0 else titles.count { it.genres != 0L } * 100 / titles.size
        val services = vod.count { it.lang != null && it.services != 0L }
        val nf = java.text.NumberFormat.getIntegerInstance()
        return buildList {
            add(Texts.get(R.string.report_categories, nf.format(used), nf.format(all.size)))
            add(Texts.get(if (profile.labelsLanguages) R.string.report_langs else R.string.report_no_langs))
            add(Texts.get(R.string.report_titles, nf.format(titles.size), withGenre))
            add(Texts.get(R.string.report_channels, nf.format(channels)))
            if (services == 0) add(Texts.get(R.string.report_no_services))
        }.joinToString(" · ")
    }

    private fun collect(src: Source, action: String, kind: Int, cats: Map<String, CategoryInfo>, content: ContentPrefs): Collection<Candidate> {
        val out = LinkedHashMap<String, Candidate>(8192)
        val topCounters = HashMap<String, Int>()
        val prefix = if (kind == KIND_MOVIE) "m" else "s"
        client.forEach(src, action) { o ->
            val catId = o.str("category_id") ?: return@forEach
            val cat = cats[catId] ?: return@forEach
            var catLang = cat.lang ?: return@forEach
            var labelled = cat.labelled
            // The category says nothing about language: the title's own label decides ("NL: Dune", "DE: Dune").
            if (!cat.labelled) Tags.language(Tags.of(o.str("name").orEmpty()))?.let { l ->
                val rel = content.relate(l)
                if (!content.wants(rel, live = false)) return@forEach
                catLang = rel
                labelled = true
            }
            if (o.bool("is_adult")) return@forEach
            val id = (if (kind == KIND_MOVIE) o.long("stream_id") else o.long("series_id")) ?: return@forEach
            val clean = Titles.clean(o.str("name") ?: return@forEach)
            val released = (o.str("releaseDate") ?: o.str("release_date") ?: o.str("year"))?.take(4)?.toIntOrNull()
            val year = clean.year ?: released
            // A long-running series is listed per season ("Goede Tijden, Slechte Tijden (2026)"): the year it
            // started is the earlier one, and that is what decides whether it counts as new.
            val shownYear = if (kind == KIND_SERIES && year != null && released != null && released in 1900..year) released else year
            val tmdb = Titles.tmdbId(o.str("tmdb")) ?: Titles.tmdbId(o.str("tmdb_id"))
            val seriesDub = kind == KIND_SERIES && clean.dubbed
            val key = buildString {
                append(prefix).append(':')
                if (tmdb != null) append('t').append(tmdb) else append("n:").append(Titles.fold(clean.name)).append(':').append(year ?: 0)
                if (seriesDub) append(":nl")
            }
            var topRank = 0
            if (cat.flags and Flags.TOP != 0) {
                val n = (topCounters[catId] ?: 0) + 1
                topCounters[catId] = n
                topRank = n + if (catLang == Lang.EN) 1000 else 0
            }
            val cand = Candidate(
                key = key, kind = kind, sourceId = id,
                name = if (seriesDub) "${clean.name} (NL gesproken)" else clean.name,
                year = shownYear,
                poster = o.str("stream_icon") ?: o.str("cover"),
                container = o.str("container_extension"),
                addedTs = o.long("added") ?: o.long("last_modified") ?: 0L,
                rating = (o.double("rating") ?: 0.0).takeIf { it in 0.1..9.4 } ?: 0.0,   // 9.5+ on this kind of line is a placeholder, not a score
                lang = if (catLang == Lang.EN) 1 else 0,
                genres = cat.genres or Genre.mask(o.str("genre")),
                services = cat.services, flags = cat.flags and Flags.TOP.inv(), topRank = topRank,
                dubbed = clean.dubbed && kind == KIND_MOVIE, quality = clean.quality,
                plot = o.str("plot")?.take(700), backdrop = o.firstString("backdrop_path"),
                labelled = labelled,
            )
            val existing = out[key]
            if (existing == null) out[key] = cand else merge(existing, cand)
        }
        return out.values
    }

    private val ENGLISH = "en"
    private val MAX_COPIES = 4

    /** Same title seen again (other category, other language, other quality): keep the best, merge signals. */
    private fun merge(a: Candidate, b: Candidate) {
        val primaryLang = minOf(a.lang, b.lang)
        fun alt(c: Candidate) {
            if (c.kind != KIND_MOVIE) {
                // Another listing of the same series has its own files, often on another server: the player
                // falls back on those when an episode of the preferred listing arrives too slowly.
                if (a.alts.size < MAX_COPIES) a.alts.getOrPut("$COPY${c.sourceId}") { "${c.sourceId};;$COPY" }
                return
            }
            if (c.lang > primaryLang) {
                // The provider's Dutch copy can be dubbed with no other audio track (children's films),
                // so the English copy stays available as a version. Its quality variants are dropped.
                if (!c.dubbed) a.alts.getOrPut(ENGLISH) { "${c.sourceId};${c.container.orEmpty()};$ENGLISH" }
                return
            }
            val label = c.altLabel ?: return
            a.alts.getOrPut(label) { "${c.sourceId};${c.container.orEmpty()};$label" }
        }
        if (b.score < a.score) {
            alt(a)
            a.sourceId = b.sourceId; a.container = b.container; a.name = b.name
            a.lang = b.lang; a.dubbed = b.dubbed; a.quality = b.quality; a.labelled = b.labelled
            a.poster = b.poster ?: a.poster
        } else {
            alt(b)
            if (a.poster == null) a.poster = b.poster
        }
        a.genres = a.genres or b.genres
        a.services = a.services or b.services
        a.flags = a.flags or b.flags
        a.addedTs = maxOf(a.addedTs, b.addedTs)
        a.rating = maxOf(a.rating, b.rating)
        if (b.topRank != 0 && (a.topRank == 0 || b.topRank < a.topRank)) a.topRank = b.topRank
        if (a.plot == null) a.plot = b.plot
        if (a.backdrop == null) a.backdrop = b.backdrop
    }

    private suspend fun store(items: Collection<Candidate>, gen: Long) {
        items.asSequence().map { c ->
            TitleCore(
                key = c.key, kind = c.kind, sourceId = c.sourceId, name = c.name, search = Titles.fold(c.name),
                year = c.year, poster = c.poster, container = c.container, addedTs = c.addedTs, rating = c.rating,
                lang = c.lang, pGenres = c.genres, services = c.services,
                flags = c.flags or (if (c.topRank > 0) Flags.TOP else 0), topRank = c.topRank,
                alts = c.alts.values.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                pPlot = c.plot, pBackdrop = c.backdrop, gen = gen,
            )
        }.chunked(400).forEach { chunk ->
            db.withTransaction {
                // A title that now points at another entry of the provider (another language version, a
                // re-upload): its stored episode list belongs to the old entry and must not be used again.
                val moved = db.catalog().movedTitles(chunk.map { it.key }, chunk.map { "${it.key}|${it.sourceId}" })
                db.catalog().upsertTitles(chunk)
                if (moved.isNotEmpty()) { db.catalog().deleteEpisodesOf(moved); db.catalog().expireDetail(moved) }
            }
        }
    }

    /** @return the number of channels kept. */
    private suspend fun syncChannels(src: Source, order: List<String>, cats: Map<String, CategoryInfo>, gen: Long, content: ContentPrefs): Int {
        val byGroup = LinkedHashMap<String, MutableList<JsonObj>>()
        client.forEach(src, "get_live_streams") { o ->
            val catId = o.str("category_id") ?: return@forEach
            val info = cats[catId] ?: return@forEach
            if (info.lang == null) return@forEach
            if (Titles.isDivider(o.str("name").orEmpty())) return@forEach
            // Unlabelled group: the channel's own label decides ("DE: ZDF" goes, "NL: NPO 1" stays).
            if (!info.labelled) Tags.language(Tags.of(o.str("name").orEmpty()))?.let { l ->
                if (!content.wants(content.relate(l), live = true)) return@forEach
            }
            byGroup.getOrPut(catId) { ArrayList() } += o
        }
        // Home-language groups first, then the regional ones (Flemish), then English; provider order inside each.
        val groupIds = order.filter { byGroup.containsKey(it) }
            .sortedBy { when (cats[it]?.lang) { Lang.HOME -> 0; Lang.REGIONAL -> 1; else -> 2 } }
        val channels = ArrayList<ChannelEntity>()
        val groups = ArrayList<ChannelGroupEntity>()
        var sort = 0
        groupIds.forEachIndexed { gi, gid ->
            val info = cats.getValue(gid)
            val prefix = when (info.lang) { Lang.REGIONAL -> Texts.get(R.string.group_regional); Lang.EN -> Texts.get(R.string.group_english); else -> "" }
            groups += ChannelGroupEntity(gid, prefix + info.label, gi)
            byGroup.getValue(gid).forEachIndexed { i, o ->
                val id = o.long("stream_id") ?: return@forEachIndexed
                val name = Titles.channel(o.str("name").orEmpty())
                channels += ChannelEntity(
                    id = id, name = name, search = Titles.fold(name), logo = o.str("stream_icon"), groupId = gid,
                    epgId = o.str("epg_channel_id"), num = i + 1, sort = sort++, archive = o.bool("tv_archive"), gen = gen,
                )
            }
        }
        db.withTransaction {
            db.channels().clearGroups()
            db.channels().insertGroups(groups)
            channels.chunked(500).forEach { db.channels().insertChannels(it) }
        }
        return channels.size
    }
}

/** Keeps the catalog fresh in the background, also when the app is closed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as App).container
        val src = c.prefs.source()
        if (!src.isConfigured) return Result.success()
        val ok = c.sync.sync(src).isSuccess
        if (ok) {
            runCatching { c.library.refreshNextUp() }
            runCatching { c.trends.refresh() }
            runCatching { c.refreshGuide() }
            runCatching { c.enricher.run(maxItems = 150) }
            runCatching { c.watchNext.publish() }
        }
        return if (ok || c.sync.state.value.let { it is SyncState.Failed && runAttemptCount > 2 }) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(8, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("catalog-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
