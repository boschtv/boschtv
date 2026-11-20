package nl.boschtv.tv.data

import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nl.boschtv.tv.data.db.AppDatabase
import nl.boschtv.tv.data.db.ChannelEntity
import nl.boschtv.tv.data.db.ProgrammeEntity
import nl.boschtv.tv.data.xtream.XtreamClient
import java.util.concurrent.ConcurrentHashMap

/** Programme guide, fetched per channel on demand and cached for a few hours. */
class Epg(private val db: AppDatabase, private val client: XtreamClient, private val prefs: Prefs) {
    private val fetchedAt = ConcurrentHashMap<Long, Long>()

    /** At most two guide downloads at once: the provider throttles when a whole screen of rows asks together. */
    private val gate = Semaphore(2)

    /** Programmes still running at [from] and after it (current one first when [from] is now). */
    suspend fun upcoming(channel: ChannelEntity, limit: Int = 12, from: Long = System.currentTimeMillis()): List<ProgrammeEntity> = withContext(Dispatchers.IO) {
        // The 4K / cable / other-quality copies of a channel come without a guide id: borrow the guide
        // from the copy of the same channel that has one (NPO 2 4K -> NPO 2 HD). Without such a twin, ask
        // for the channel's own listings anyway: some providers have them under the stream itself.
        if (channel.epgId.isNullOrBlank()) twinWithGuide(channel)?.let { return@withContext upcoming(it, limit, from) }
        val now = System.currentTimeMillis()
        var list = db.channels().programmes(channel.id, from, limit)
        val last = fetchedAt[channel.id] ?: 0L
        val stale = list.isEmpty() || list.last().stop < now + 3 * 3600_000L
        if (stale && now - last > 30 * 60_000L || now - last > 6 * 3600_000L && last != 0L) {
            fetchedAt[channel.id] = now
            runCatching { gate.withPermit { fetch(channel.id) } }
            list = db.channels().programmes(channel.id, from, limit)
        }
        list
    }

    private val quality = Regex("""\b(8k|4k|uhd|fhd|hd|sd|hevc|50fps|raw|dolby)\b""")
    private val spaces = Regex("""\s+""")
    private fun baseName(search: String) = spaces.replace(quality.replace(search, " "), " ").trim()

    private suspend fun twinWithGuide(channel: ChannelEntity): ChannelEntity? {
        val base = baseName(channel.search)
        if (base.length < 3) return null
        return db.channels().search(base, 40).firstOrNull { !it.epgId.isNullOrBlank() && baseName(it.search) == base }
    }

    private suspend fun fetch(channelId: Long) {
        val src = prefs.source()
        val root = client.obj(src, "get_simple_data_table", "stream_id" to channelId.toString()) ?: return
        val cutoff = System.currentTimeMillis() - 3600_000L
        val items = root.list("epg_listings").mapNotNull { raw ->
            val o = (raw as? Map<*, *>)?.let { @Suppress("UNCHECKED_CAST") nl.boschtv.tv.core.JsonObj(it as Map<String, Any?>) } ?: return@mapNotNull null
            val start = (o.long("start_timestamp") ?: return@mapNotNull null) * 1000
            val stop = (o.long("stop_timestamp") ?: return@mapNotNull null) * 1000
            if (stop < cutoff) return@mapNotNull null
            ProgrammeEntity(channelId, start, stop, decode(o.str("title")) ?: return@mapNotNull null, decode(o.str("description"))?.take(400))
        }
        if (items.isEmpty()) return
        db.withTransaction {
            db.channels().clearProgrammes(channelId)
            items.chunked(300).forEach { db.channels().insertProgrammes(it) }
        }
    }

    /** Xtream sends EPG text base64-encoded; some panels send it plain. */
    private fun decode(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val decoded = runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        val ok = decoded != null && decoded.isNotBlank() && decoded.none { it == '�' }
        return (if (ok) decoded else s)?.trim()?.ifBlank { null }
    }
}
