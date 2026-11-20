package nl.boschtv.tv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.boschtv.tv.App
import nl.boschtv.tv.data.db.EpisodeEntity
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.TitleEntity
import nl.boschtv.tv.ui.BoschTheme
import nl.boschtv.tv.ui.Ink
import nl.boschtv.tv.ui.Pill
import java.util.Locale

data class TrackOption(val label: String, val selected: Boolean, val select: () -> Unit)

/** Observable state of the film/series player. */
class VodUi {
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var visible by mutableStateOf(true)
    /** While skipping: where we will land. The video itself only jumps once, when you stop pressing. */
    var scrubTo by mutableStateOf<Long?>(null)
    val scrubbing get() = scrubTo != null
    var touched by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)
    var next by mutableStateOf<EpisodeEntity?>(null)
    var tracksOpen by mutableStateOf(false)
    var audio by mutableStateOf<List<TrackOption>>(emptyList())
    var text by mutableStateOf<List<TrackOption>>(emptyList())
    val nearEnd get() = next != null && duration > 0 && duration - position < 30_000
}

class PlayerActivity : ComponentActivity() {
    private val c by lazy { (application as App).container }
    private lateinit var player: ExoPlayer
    private val ui = VodUi()
    private var title: TitleEntity? = null
    private var episode: EpisodeEntity? = null
    private var streamId: Long = 0
    private var container: String? = null
    private var retries = 0
    // The line allows one stream. A player that is not on screen must hold no connection, and must never
    // reconnect by itself: otherwise it steals the stream from whatever is being watched now.
    private var released = false
    private var onScreen = false
    private var scrubJob: kotlinx.coroutines.Job? = null
    private var scrubStreak = 0
    private var frameWatch: kotlinx.coroutines.Job? = null
    private var awaitingFrame = false
    private var lastScrubAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player = Playback.player(this, live = false)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { ui.playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                ui.buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { retries = 0; ui.error = null }
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onTracksChanged(tracks: Tracks) = readTracks(tracks)
            override fun onRenderedFirstFrame() { awaitingFrame = false }
            override fun onPlayerError(error: PlaybackException) {
                // Network hiccups are common on IPTV lines: retry from where we were a few times before giving up.
                if (!onScreen || released) return
                if (retries++ < 3) {
                    val at = player.currentPosition
                    lifecycleScope.launch {
                        delay(1200L * retries)
                        if (onScreen && !released) { player.prepare(); player.seekTo(at); player.play() }
                    }
                } else ui.error = "Afspelen lukt niet. Controleer je verbinding of probeer een andere versie."
            }
        })
        setContent { BoschTheme { VodScreen(player, ui, ::toggle, ::restart, ::playNext, { scrub(it > 0, 0) }) { finish() } } }
        lifecycleScope.launch { load() }
        lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                if (released || !onScreen) continue
                ui.position = player.currentPosition.coerceAtLeast(0)
                ui.duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (ui.visible && !ui.tracksOpen && ui.playing && !ui.scrubbing && System.currentTimeMillis() - ui.touched > 4500) ui.visible = false
                if (++tick % 20 == 0 && ui.playing) save()
            }
        }
    }

    private suspend fun load() {
        val key = intent.getStringExtra(Playback.KEY) ?: return finish()
        val t = c.catalog.title(key) ?: return finish()
        title = t
        val restart = intent.getBooleanExtra(Playback.RESTART, false)
        if (t.kind == KIND_MOVIE) {
            val saved = c.library.progress("m:${t.key}")
            streamId = intent.getLongExtra(Playback.STREAM, 0).takeIf { it != 0L } ?: saved?.streamId ?: t.sourceId
            container = intent.getStringExtra(Playback.CONTAINER) ?: saved?.container?.takeIf { saved.streamId == streamId } ?: t.container
            ui.title = t.name; ui.subtitle = t.year?.toString()
            start(c.prefs.source().movie(streamId, container), if (restart || saved?.finished == true) 0 else saved?.positionMs ?: 0)
        } else {
            if (c.catalog.episodes(key).isEmpty()) c.catalog.detail(key, maxAgeMs = 0)
            val wanted = intent.getStringExtra(Playback.EPISODE)?.let { c.catalog.episode(it) }
            val (ep, pos) = if (wanted != null) {
                val p = c.library.progress("e:${wanted.id}")
                wanted to if (restart || p?.finished == true) 0L else p?.positionMs ?: 0L
            } else c.library.resumePoint(key) ?: return finish()
            startEpisode(ep, if (restart) 0 else pos)
        }
    }

    private suspend fun startEpisode(ep: EpisodeEntity, positionMs: Long) {
        episode = ep
        ui.title = title?.name.orEmpty()
        ui.subtitle = "S${ep.season} · A${ep.number} · ${ep.name}"
        ui.next = c.library.nextEpisode(ep.titleKey, ep.id)
        start(c.prefs.source().episode(ep.id, ep.container), positionMs)
    }

    private fun start(url: String, positionMs: Long) {
        retries = 0; ui.error = null
        player.setMediaItem(Playback.item(url), positionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        show()
    }

    private fun onEnded() {
        save(ended = true)
        if (ui.next != null) playNext() else finish()
    }

    private fun playNext() {
        val n = ui.next ?: return
        save(ended = true)
        lifecycleScope.launch { startEpisode(n, 0) }
    }

    private fun restart() { player.seekTo(0); player.play(); show() }
    private fun toggle() { if (player.isPlaying) player.pause() else player.play(); show() }
    private fun show() { ui.visible = true; ui.touched = System.currentTimeMillis() }

    /**
     * Skipping, the way streaming apps do it: the bar and the clock move instantly and speed up the longer
     * you keep going; the stream is only asked to jump once, shortly after the last press (or on OK).
     */
    private fun scrub(forward: Boolean, repeat: Int) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val now = System.currentTimeMillis()
        scrubStreak = if (now - lastScrubAt < 450) scrubStreak + 1 else 0
        lastScrubAt = now
        val pace = maxOf(repeat, scrubStreak)
        val step = when { pace < 4 -> 10_000L; pace < 10 -> 30_000L; pace < 20 -> 60_000L; else -> 180_000L }
        val from = ui.scrubTo ?: player.currentPosition
        ui.scrubTo = (from + if (forward) step else -step).coerceIn(0, (dur - 3000).coerceAtLeast(0))
        show()
        scrubJob?.cancel()
        scrubJob = lifecycleScope.launch { delay(900); commitScrub() }
    }

    private fun commitScrub() {
        scrubJob?.cancel()
        val to = ui.scrubTo ?: return
        player.seekTo(to)
        ui.position = to
        ui.scrubTo = null
        player.play()
        show()
        watchForPicture()
    }

    /**
     * After a jump the picture sometimes does not come back: the TV's decoder fails to restart (sound and
     * clock continue, screen black), or the stream stays stuck loading. If no frame shows up within a few
     * seconds, reopen the stream at that position, once.
     */
    private fun watchForPicture() {
        awaitingFrame = true
        frameWatch?.cancel()
        frameWatch = lifecycleScope.launch {
            fun stuck() = awaitingFrame && onScreen && !released && !ui.scrubbing
            delay(5000)
            if (!stuck()) return@launch
            // Decoder came back without a picture: rebuild it. Still loading: give the stream a little longer.
            if (player.playbackState != Player.STATE_READY) {
                delay(8000)
                if (!stuck()) return@launch
            }
            val at = player.currentPosition
            player.stop(); player.prepare(); player.seekTo(at); player.play()
        }
    }

    private fun cancelScrub() { scrubJob?.cancel(); ui.scrubTo = null }

    private fun save(ended: Boolean = false) {
        if (released) return
        val t = title ?: return
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val pos = if (ended && dur > 0) dur else player.currentPosition
        val ep = episode
        c.scope.launch {
            if (ep != null) c.library.saveEpisode(ep, pos, dur) else c.library.saveMovie(t, streamId, container, pos, dur)
        }
    }

    private fun readTracks(tracks: Tracks) {
        fun options(type: Int): List<TrackOption> = tracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                val f = g.getTrackFormat(i)
                val lang = f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("nl")) }?.replaceFirstChar(Char::uppercase)
                val label = listOfNotNull(lang ?: f.label ?: "Spoor ${i + 1}", f.label?.takeIf { lang != null && it != lang }).joinToString(" · ")
                TrackOption(label, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        ui.audio = options(C.TRACK_TYPE_AUDIO)
        val text = options(C.TRACK_TYPE_TEXT)
        ui.text = if (text.isEmpty()) emptyList() else listOf(TrackOption("Uit", text.none { it.selected }) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }) + text
    }

    // Lint wrongly flags the documented super.dispatchKeyEvent call as restricted (androidx.core marker).
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val wasVisible = ui.visible
        ui.touched = System.currentTimeMillis()
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { scrub(true, event.repeatCount); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { scrub(false, event.repeatCount); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); return true }
            KeyEvent.KEYCODE_BACK -> when {
                ui.tracksOpen -> { ui.tracksOpen = false; return true }
                ui.scrubbing -> { cancelScrub(); return true }
                wasVisible && player.isPlaying -> { ui.visible = false; return true }
            }
        }
        if (ui.tracksOpen) return super.dispatchKeyEvent(event)
        if (!wasVisible || ui.scrubbing) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(false, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(true, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                ui.scrubbing -> { commitScrub(); return true }
                !wasVisible -> { toggle(); return true }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (ui.scrubbing) commitScrub(); if (!wasVisible) { show(); return true } }
        }
        ui.visible = true
        return super.dispatchKeyEvent(event)
    }

    private fun releasePlayer() {
        if (released) return
        save()
        released = true
        scrubJob?.cancel()
        player.release()
    }

    override fun onStart() {
        super.onStart()
        onScreen = true
        // Coming back to a player that was stopped in the background: pick the stream up again.
        if (!released && title != null && player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            player.prepare(); player.play()
        }
    }

    override fun onPause() {
        super.onPause()
        // Closing the player frees the stream right away, before the next screen asks for one.
        if (isFinishing) releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        onScreen = false
        if (!released) { save(); player.stop() }   // stop, not pause: pause keeps the connection open
        c.scope.launch { delay(600); c.watchNext.publish() }
    }

    override fun onDestroy() { super.onDestroy(); releasePlayer() }
}

@Composable
private fun VodScreen(
    player: ExoPlayer, ui: VodUi,
    onToggle: () -> Unit, onRestart: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player; keepScreenOn = true; isFocusable = false } },
            modifier = Modifier.fillMaxSize(),
        )
        if (ui.buffering && ui.error == null) Spinner(Modifier.align(Alignment.Center))

        AnimatedVisibility(ui.visible || ui.error != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xE6000000)))) {
                Column(Modifier.padding(start = 48.dp, top = 36.dp).fillMaxWidth(0.7f)) {
                    Text(ui.title, style = MaterialTheme.typography.headlineLarge, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                ui.error?.let {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(it, style = MaterialTheme.typography.titleLarge, color = Ink.Text)
                        Pill("Terug", onClose, primary = true)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SeekBar(ui, onSeek)
                    if (!ui.scrubbing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (ui.playing) "Pauze" else "Afspelen", onToggle, Modifier.focusRequester(playFocus), primary = true,
                            icon = if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow)
                        Pill("Vanaf begin", onRestart, icon = Icons.Rounded.Replay, selected = true)
                        if (ui.text.isNotEmpty() || ui.audio.size > 1) Pill("Ondertiteling en audio", { ui.tracksOpen = true }, icon = Icons.Rounded.Subtitles, selected = true)
                        if (ui.next != null) Pill(if (ui.nearEnd) "Volgende aflevering over ${((ui.duration - ui.position) / 1000).coerceAtLeast(0)} s" else "Volgende aflevering", onNext, Modifier.focusRequester(nextFocus), icon = Icons.Rounded.SkipNext, selected = true)
                    }
                }
            }
        }
        LaunchedEffect(ui.visible, ui.scrubbing, ui.tracksOpen) {
            if (ui.visible && !ui.scrubbing && !ui.tracksOpen) { delay(80); runCatching { playFocus.requestFocus() } }
        }
        // Nudge towards the next episode during the credits.
        LaunchedEffect(ui.nearEnd) { if (ui.nearEnd) { ui.visible = true; ui.touched = System.currentTimeMillis(); delay(150); runCatching { nextFocus.requestFocus() } } }

        if (ui.tracksOpen) TrackPanel(ui, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SeekBar(ui: VodUi, onSeek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shown = ui.scrubTo ?: ui.position
    ui.scrubTo?.let { to ->
        val delta = to - ui.position
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(clock(to), style = MaterialTheme.typography.displayMedium, color = Ink.Text)
            Text((if (delta >= 0) "+" else "−") + clock(kotlin.math.abs(delta)), style = MaterialTheme.typography.titleLarge, color = Ink.AccentSoft, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(clock(shown), style = MaterialTheme.typography.titleMedium, color = Ink.Text)
        Box(
            Modifier.weight(1f).height(18.dp)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) { Key.DirectionLeft -> { onSeek(-1); true }; Key.DirectionRight -> { onSeek(1); true }; else -> false }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val big = focused || ui.scrubbing
            val f = if (ui.duration > 0) (shown.toFloat() / ui.duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(if (big) 8.dp else 4.dp).clip(CircleShape).background(Color(0x55FFFFFF)))
            Box(Modifier.fillMaxWidth(f).height(if (big) 8.dp else 4.dp).clip(CircleShape).background(if (big) Ink.Text else Ink.Accent))
        }
        Text(if (ui.duration > 0) "-" + clock(ui.duration - shown) else "", style = MaterialTheme.typography.titleMedium, color = Ink.Soft)
    }
}

@Composable
private fun TrackPanel(ui: VodUi, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LazyColumn(
        modifier.fillMaxHeight().width(360.dp).background(Ink.Bg.copy(alpha = 0.96f)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (ui.text.isNotEmpty()) {
            item { Text("Ondertiteling", style = MaterialTheme.typography.titleLarge, color = Ink.Text, modifier = Modifier.padding(bottom = 6.dp)) }
            items(ui.text.size) { i ->
                val o = ui.text[i]
                Pill(o.label, { o.select(); ui.tracksOpen = false }, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier), selected = o.selected)
            }
        }
        if (ui.audio.isNotEmpty()) {
            item { Text("Audio", style = MaterialTheme.typography.titleLarge, color = Ink.Text, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
            items(ui.audio.size) { i ->
                val o = ui.audio[i]
                Pill(o.label, { o.select(); ui.tracksOpen = false }, Modifier.fillMaxWidth().then(if (i == 0 && ui.text.isEmpty()) Modifier.focusRequester(first) else Modifier), selected = o.selected)
            }
        }
    }
}

/** Loading indicator: a royal-blue arc chasing round a faint ring. */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "angle")
    androidx.compose.foundation.Canvas(modifier.size(52.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(Color(0x40FFFFFF), 0f, 360f, useCenter = false, style = stroke)
        drawArc(Ink.Accent, angle, 100f, useCenter = false, style = stroke)
    }
}
