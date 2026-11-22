package nl.boschtv.tv.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import nl.boschtv.tv.data.xtream.USER_AGENT

object Playback {
    const val KEY = "key"
    const val EPISODE = "episode"
    const val STREAM = "stream"
    const val CONTAINER = "container"
    const val RESTART = "restart"
    const val CHANNEL = "channel"

    /** Plays a YouTube trailer full screen inside the app (see [TrailerActivity]). */
    fun trailer(ctx: Context, youtubeId: String, name: String? = null, image: String? = null) {
        ctx.startActivity(Intent(ctx, TrailerActivity::class.java).putExtra(TrailerActivity.VIDEO, youtubeId)
            .putExtra(TrailerActivity.NAME, name).putExtra(TrailerActivity.IMAGE, image))
    }

    fun title(ctx: Context, key: String, episodeId: String? = null, streamId: Long? = null, container: String? = null, restart: Boolean = false) {
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(KEY, key); putExtra(EPISODE, episodeId); putExtra(RESTART, restart)
            if (streamId != null) { putExtra(STREAM, streamId); putExtra(CONTAINER, container) }
        })
    }

    fun live(ctx: Context, channelId: Long? = null) {
        ctx.startActivity(Intent(ctx, LiveActivity::class.java).apply { if (channelId != null) putExtra(CHANNEL, channelId) })
    }

    fun player(ctx: Context, live: Boolean): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)
        // A skip closes one connection and opens another. On a line that allows a single stream the provider
        // can refuse the new one for a moment because it still counts the old one. Retry quickly and for
        // longer than the default, instead of backing off for many seconds and then giving up.
        val patient = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(12) {
            override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val base = super.getRetryDelayMsFor(info)
                return if (base == C.TIME_UNSET) base else minOf(base, 900L)
            }
        }
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), extractors).setLoadErrorHandlingPolicy(patient)
        val tracks = DefaultTrackSelector(ctx).apply {
            parameters = buildUponParameters()
                // Original sound with Dutch subtitles: English when the file has it, otherwise the file's
                // own default track (normally the original language). A Dutch dub is never preferred.
                .setPreferredAudioLanguages("en", "eng")
                .setPreferredTextLanguages("nl", "nld", "dut")
                .setSelectUndeterminedTextLanguage(false)
                .build()
        }
        val load = if (live) {
            // Start showing a zapped channel fast. Live TV cannot be loaded ahead, so the long upper limit only
            // counts while it is paused: the player keeps loading for a few minutes (within a fixed amount of
            // memory), which is what a pause on a channel without an archive plays on from.
            DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 300_000, 1_000, 2_500).setTargetBufferBytes(72 * 1024 * 1024).build()
        } else {
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_500, 4_000).build()
        }
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(sources)
            .setTrackSelector(tracks)
            .setLoadControl(load)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            }
    }

    /** [title] and [subtitle] are what the TV and a voice assistant show for what is playing. */
    fun item(url: String, title: String? = null, subtitle: String? = null): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (!title.isNullOrBlank()) b.setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).build())
        if (url.substringBefore('?').endsWith(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
