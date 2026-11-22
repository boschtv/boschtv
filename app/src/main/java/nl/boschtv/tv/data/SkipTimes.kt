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

/** Where an episode's intro and recap are, and where its credits start, in this file's own timeline. */
data class Segments(val intro: LongRange?, val recap: LongRange?, val creditsAt: Long?)

/**
 * Intro, recap and credits times from TheIntroDB, a community database keyed by TMDB id. The times
 * are crowd-sourced against one release of an episode, so they are only used when several people
 * agree and when they make sense for this file: intros and recaps near the start, credits near the
 * end. Credits are counted back from the end, so a file a little longer or shorter still lines up.
 */
class SkipTimes(private val http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, Segments>()
    private val tmdb = Regex("""^s:t(\d+)""")

    suspend fun forEpisode(titleKey: String, season: Int, episode: Int, durationMs: Long): Segments? = withContext(Dispatchers.IO) {
        val id = tmdb.find(titleKey)?.groupValues?.get(1) ?: return@withContext null
        val cacheKey = "$id/$season/$episode"
        cache[cacheKey]?.let { return@withContext it }
        val url = "https://api.theintrodb.org/v4/media?tmdb_id=$id&season=$season&episode=$episode&details=true" +
            if (durationMs > 0) "&duration_ms=$durationMs" else ""
        val o = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JsonReader.of(r.body!!.source()).readJsonValue().asObj()
            }
        }.getOrNull() ?: return@withContext null
        segments(o, durationMs).also { cache[cacheKey] = it }
    }

    internal fun segments(o: JsonObj, duration: Long): Segments {
        val known = duration > 0
        val measured = o.long("group_duration_ms")?.takeIf { it > 0 }

        fun best(kind: String): JsonObj? = o.list(kind).mapNotNull { it.asObj() }
            .filter { e ->
                val empty = (e.long("start_ms") ?: 0L) == 0L && (e.long("end_ms") ?: 0L) == 0L   // "this episode has none"
                val agreed = (e.long("submission_count") ?: 0L) >= 2 || (e.double("confidence") ?: 0.0) >= 0.7
                !empty && agreed
            }
            .maxByOrNull { it.long("submission_count") ?: 0L }

        fun opening(kind: String, maxLength: Long, maxShare: Double): LongRange? {
            val e = best(kind) ?: return null
            val start = e.long("start_ms") ?: 0L
            val end = e.long("end_ms") ?: return null
            if (end <= start || end - start > maxLength) return null
            if (known && end > duration * maxShare) return null
            return start..end
        }

        val credits = best("credits")?.long("start_ms")?.let { start ->
            // Counted back from the end of the measured release, applied to this file's length.
            val at = if (known && measured != null) duration - (measured - start) else start
            at.takeIf { !known || (it >= duration * 0.6 && duration - it <= 15 * 60_000L) }
        }
        return Segments(
            intro = opening("intro", maxLength = 3 * 60_000L, maxShare = 0.4),
            recap = opening("recap", maxLength = 5 * 60_000L, maxShare = 0.3),
            creditsAt = credits,
        )
    }
}
