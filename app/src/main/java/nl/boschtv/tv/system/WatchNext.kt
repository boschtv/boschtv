package nl.boschtv.tv.system

import nl.boschtv.tv.R
import nl.boschtv.tv.ui.Texts
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.boschtv.tv.data.Catalog
import nl.boschtv.tv.data.Library

/** Mirrors "Continue watching" into the Continue Watching row of the Google TV home screen. */
class WatchNext(private val context: Context, private val catalog: Catalog, private val library: Library) {

    /** What the last publish did, shown in Settings so it can be checked on the TV itself. */
    val status = kotlinx.coroutines.flow.MutableStateFlow(Texts.get(R.string.wn_not_tried))

    @SuppressLint("RestrictedApi")
    suspend fun publish() = withContext(Dispatchers.IO) {
        val result = runCatching {
            val rows = library.rawContinue(15)
            val cards = catalog.byKeys(rows.map { it.titleKey })
            val helper = PreviewChannelHelper(context)
            val existing = HashMap<String, Long>()
            context.contentResolver.query(
                WatchNextPrograms.CONTENT_URI,
                arrayOf(WatchNextPrograms._ID, WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null,
            )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { existing[it] = c.getLong(0) } }

            val wanted = rows.filter { cards.containsKey(it.titleKey) }
            existing.filterKeys { id -> wanted.none { it.titleKey == id } }.values.forEach {
                context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null)
            }
            for (p in wanted) {
                val card = cards.getValue(p.titleKey)
                val b = WatchNextProgram.Builder()
                    .setType(if (p.isEpisode) WatchNextPrograms.TYPE_TV_EPISODE else WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(if (p.positionMs > 0) WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE else WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                    .setTitle(card.title)
                    .setInternalProviderId(p.titleKey)
                    .setLastEngagementTimeUtcMillis(p.updatedAt)
                    .setIntentUri(Uri.parse("boschtv://play?key=${Uri.encode(p.titleKey)}"))
                // Google TV is stricter than older launchers: give it the complete picture.
                b.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                card.plot?.let { b.setDescription(it) }
                card.year?.let { b.setReleaseDate(it.toString()) }
                p.season?.let { b.setSeasonNumber(it) }
                p.number?.let { b.setEpisodeNumber(it) }
                p.label?.let { b.setEpisodeTitle(it) }
                (p.image ?: card.backdrop ?: card.poster)?.let { b.setPosterArtUri(Uri.parse(it)) }
                if (p.durationMs > 0) {
                    b.setDurationMillis(p.durationMs.toInt())
                    b.setLastPlaybackPositionMillis(p.positionMs.coerceIn(0, p.durationMs).toInt())
                }
                val id = existing[p.titleKey]
                if (id != null) helper.updateWatchNextProgram(b.build(), id) else helper.publishWatchNextProgram(b.build())
            }
            // Read our own entries back: this is what the home screen can draw from.
            var stored = 0
            context.contentResolver.query(WatchNextPrograms.CONTENT_URI, arrayOf(WatchNextPrograms._ID), null, null, null)?.use { stored = it.count }
            Texts.get(R.string.wn_published, stored)
        }
        status.value = result.getOrElse { Texts.get(R.string.wn_failed, "${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}") }
    }
}
