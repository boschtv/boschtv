package nl.boschtv.tv.data

import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.boschtv.tv.core.JsonObj
import nl.boschtv.tv.core.asObj
import nl.boschtv.tv.data.xtream.USER_AGENT
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Where an episode's intro and recap are, and where its credits start, in this file's own timeline. */
data class Segments(val intro: LongRange?, val recap: LongRange?, val creditsAt: Long?)

/**
 * Intro, recap and credits times from two community databases: TheIntroDB (by TMDB id) and IntroDB
 * (by IMDb id, looked up through TMDB). Community times are sometimes wrong, so they are combined with
 * care:
 *  - Intro and recap: two sources that agree are trusted; one source needs several submissions or the
 *    series' usual intro length; sources that clearly disagree give no button (better no button than
 *    one that skips story).
 *  - Credits: the latest start any reliable source gives. Later is always safe (the countdown starts a
 *    little after the credits); earlier could cut off the end of an episode.
 *  - The series as a whole: an episode whose credits stray much earlier than its neighbours' is moved to
 *    the series' usual length, and a lone intro with the series' usual length is accepted.
 * All times are counted in this file's own timeline: credits from the end, so a slightly longer or
 * shorter file still lines up.
 */
class SkipTimes(private val http: OkHttpClient, private val tmdbToken: String) {
    private val cache = ConcurrentHashMap<String, Segments>()
    private val theIntroDb = ConcurrentHashMap<String, JsonObj>()
    private val introDb = ConcurrentHashMap<String, JsonObj>()
    private val imdbIds = ConcurrentHashMap<String, String>()
    private val tmdb = Regex("""^s:t(\d+)""")

    suspend fun forEpisode(titleKey: String, season: Int, episode: Int, durationMs: Long): Segments? = withContext(Dispatchers.IO) {
        val id = tmdb.find(titleKey)?.groupValues?.get(1) ?: return@withContext null
        val cacheKey = "$id/$season/$episode"
        cache[cacheKey]?.let { return@withContext it }
        val a = theIntroDb(id, season, episode, durationMs)
        val b = imdbId(id)?.let { introDb(it, season, episode) }
        if (a == null && b == null) return@withContext null
        segments(a, b, durationMs, typicalCredits(id, season, episode), typicalIntro(id, season, episode)).also { cache[cacheKey] = it }
    }

    // ---- fetching ----

    private fun json(url: String, auth: Boolean = false): JsonObj? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { if (auth) header("Authorization", "Bearer $tmdbToken") }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            JsonReader.of(r.body!!.source()).readJsonValue().asObj()
        }
    }.getOrNull()

    private fun theIntroDb(id: String, season: Int, episode: Int, durationMs: Long = 0): JsonObj? {
        val key = "$id/$season/$episode"
        theIntroDb[key]?.let { return it }
        val url = "https://api.theintrodb.org/v4/media?tmdb_id=$id&season=$season&episode=$episode&details=true" +
            if (durationMs > 0) "&duration_ms=$durationMs" else ""
        return json(url)?.also { theIntroDb[key] = it }
    }

    private fun introDb(imdb: String, season: Int, episode: Int): JsonObj? {
        val key = "$imdb/$season/$episode"
        introDb[key]?.let { return it }
        return json("https://api.introdb.app/segments?imdb_id=$imdb&season=$season&episode=$episode")?.also { introDb[key] = it }
    }

    /** IntroDB only knows IMDb ids; TMDB translates (once per series). Needs a TMDB token. */
    private fun imdbId(tmdbId: String): String? {
        if (tmdbToken.isBlank()) return null
        imdbIds[tmdbId]?.let { return it.ifEmpty { null } }
        val imdb = json("https://api.themoviedb.org/3/tv/$tmdbId/external_ids", auth = true)?.str("imdb_id").orEmpty()
        imdbIds[tmdbId] = imdb
        return imdb.ifEmpty { null }
    }

    // ---- the series as a whole (TheIntroDB, neighbouring episodes) ----

    private fun neighbours(id: String, season: Int, episode: Int) =
        listOf(episode - 2, episode - 1, episode + 1, episode + 2).filter { it >= 1 }.mapNotNull { theIntroDb(id, season, it) }

    /** How long this series' credits usually run, from start to the end of the episode. */
    private fun typicalCredits(id: String, season: Int, episode: Int): Long? =
        typicalOf(neighbours(id, season, episode).mapNotNull { creditsTail(it) })

    /** How long this series' intro usually lasts. */
    private fun typicalIntro(id: String, season: Int, episode: Int): Long? =
        typicalOf(neighbours(id, season, episode).mapNotNull { o -> best(o, "intro")?.let { length(it) } })

    /** Only a series that is really consistent has a pattern: neighbours within 10 seconds of each other. */
    internal fun typicalOf(values: List<Long>): Long? {
        if (values.size < 2) return null
        val sorted = values.sorted()
        return if (sorted.last() - sorted.first() <= 10_000) sorted[sorted.size / 2] else null
    }

    // ---- reading one source ----

    /** Entries of one kind, without "0-0" (which means: this episode has none). */
    private fun candidates(o: JsonObj, kind: String): List<JsonObj> = o.list(kind).mapNotNull { it.asObj() }
        .filter { e -> (e.long("start_ms") ?: 0L) != 0L || (e.long("end_ms") ?: 0L) != 0L }

    private fun subs(e: JsonObj) = e.long("submission_count") ?: 0L
    private fun agreed(e: JsonObj) = subs(e) >= 2 || (e.double("confidence") ?: 0.0) >= 0.7
    private fun best(o: JsonObj, kind: String): JsonObj? = candidates(o, kind).filter(::agreed).maxByOrNull(::subs)
    private fun length(e: JsonObj): Long? = e.long("end_ms")?.let { it - (e.long("start_ms") ?: 0L) }

    /** IntroDB has one object per kind (outro for credits), not a list; 0-0 also means none. */
    private fun single(o: JsonObj?, kind: String): JsonObj? = o?.obj(kind)
        ?.takeIf { (it.long("start_ms") ?: 0L) != 0L || (it.long("end_ms") ?: 0L) != 0L }

    /** Time from the start of TheIntroDB's credits to the end of its measured release, if plausible. */
    private fun creditsTail(o: JsonObj): Long? {
        val measured = o.long("group_duration_ms")?.takeIf { it > 0 } ?: return null
        val start = best(o, "credits")?.long("start_ms") ?: return null
        return (measured - start).takeIf { it in 10_000L..15 * 60_000L }
    }

    // ---- combining ----

    internal fun segments(
        theIntroDb: JsonObj?, introDb: JsonObj?, duration: Long,
        typicalTail: Long? = null, typicalIntroLength: Long? = null,
    ): Segments {
        val known = duration > 0

        fun opening(kind: String, maxLength: Long, maxShare: Double, typicalLength: Long? = null): LongRange? {
            fun fits(e: JsonObj) = typicalLength != null && length(e)?.let { abs(it - typicalLength) <= 3_000 } == true
            val a = theIntroDb?.let { o -> best(o, kind) ?: candidates(o, kind).firstOrNull(::fits) }
            val b = single(introDb, kind)
            val pick = when {
                a != null && b != null -> {
                    val same = abs((a.long("start_ms") ?: 0L) - (b.long("start_ms") ?: 0L)) <= 10_000 &&
                        abs((a.long("end_ms") ?: 0L) - (b.long("end_ms") ?: 0L)) <= 10_000
                    when {
                        same -> a
                        subs(a) >= 2 && subs(a) >= 2 * subs(b) -> a   // clearly more people behind one of them
                        subs(b) >= 2 && subs(b) >= 2 * subs(a) -> b
                        else -> null                                  // they disagree: no button
                    }
                }
                a != null -> a
                b != null -> b.takeIf { subs(it) >= 2 || fits(it) }
                else -> null
            } ?: return null
            val start = pick.long("start_ms") ?: 0L
            val end = pick.long("end_ms") ?: return null
            if (end <= start || end - start > maxLength) return null
            if (known && end > duration * maxShare) return null
            return start..end
        }

        // Credits: every reliable source gives a "time before the end"; the shortest (latest start) wins.
        // Two sources that agree are taken as they are (Severance 1x1: both 1:35, its credits really are
        // longer); otherwise the series pattern may move a stray episode later.
        val own = theIntroDb?.let { creditsTail(it) }
        val other = single(introDb, "outro")?.let { o ->
            val s = o.long("start_ms"); val e = o.long("end_ms")
            if (s != null && e != null) (e - s).takeIf { it in 10_000L..15 * 60_000L } else null
        }
        val confirmed = own != null && other != null && abs(own - other) <= 5_000
        val corrected = if (!confirmed && typicalTail != null && (own == null || own - typicalTail > 20_000)) typicalTail else own
        val tail = listOfNotNull(corrected, other).minOrNull()
        val credits = if (known && tail != null) (duration - tail).takeIf { it >= duration * 0.6 } else null

        return Segments(
            intro = opening("intro", maxLength = 3 * 60_000L, maxShare = 0.4, typicalLength = typicalIntroLength),
            recap = opening("recap", maxLength = 5 * 60_000L, maxShare = 0.3),
            creditsAt = credits,
        )
    }
}
