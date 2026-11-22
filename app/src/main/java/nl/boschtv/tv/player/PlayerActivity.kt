package nl.boschtv.tv.player

import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import nl.boschtv.tv.ui.Texts
import nl.boschtv.tv.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.automirrored.rounded.List
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
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.boschtv.tv.App
import nl.boschtv.tv.data.db.ContentIds
import nl.boschtv.tv.data.db.EpisodeEntity
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.TitleEntity
import nl.boschtv.tv.ui.BoschTheme
import nl.boschtv.tv.ui.Ink
import nl.boschtv.tv.ui.ChoiceRow
import nl.boschtv.tv.ui.IconAction
import nl.boschtv.tv.ui.MainButton
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
    /** After skipping, left/right keep skipping until Up or Down goes to the buttons (as in the big streaming apps). */
    var seeking by mutableStateOf(false)
    /** Intro, recap and credits of the current episode, from TheIntroDB; null when unknown. */
    var segments by mutableStateOf<nl.boschtv.tv.data.Segments?>(null)
    /** Any key during the credits countdown keeps you in the credits. */
    var stayInCredits by mutableStateOf(false)
    /** What the film or episode is about; shown while paused. */
    var plot by mutableStateOf<String?>(null)
    /** Playing an episode of a series (not a film): the player offers its episode list. */
    var episodes by mutableStateOf(false)
    /** Several episodes went by without anyone touching the remote: ask before starting another. */
    var stillWatching by mutableStateOf(false)

    /** The "Skip intro" / "Skip recap" button while one is playing: its text and where it jumps to. */
    val skip: Pair<Int, Long>?
        get() {
            val s = segments ?: return null
            s.recap?.let { if (position in it.first until it.last - 1500) return R.string.skip_recap to it.last }
            s.intro?.let { if (position in it.first until it.last - 1500) return R.string.skip_intro to it.last }
            return null
        }
    /**
     * The single button that floats over the picture (no control bar): skip the intro or recap, or go to
     * the next episode during the credits. It has the focus, so OK presses it.
     */
    val floating: Floating?
        get() {
            skip?.let { return Floating.Skip(it.first, it.second) }
            if (nearEnd && !stayInCredits) return Floating.Next(nextIn)
            return null
            return null
        }
    val creditsAt get() = segments?.creditsAt
    val inCredits get() = next != null && creditsAt?.let { position >= it } == true
    val nearEnd get() = next != null && duration > 0 && (inCredits || duration - position < AUTO_NEXT_MS)
    /** Seconds until the next episode starts by itself. */
    val nextIn: Int get() = if (inCredits && !stayInCredits) ((AUTO_NEXT_MS - (position - creditsAt!!)) / 1000).toInt().coerceAtLeast(0)
        else ((duration - position) / 1000).toInt().coerceAtLeast(0)
}

sealed interface Floating {
    data class Skip(val label: Int, val to: Long) : Floating
    /** [inSeconds] null: no countdown yet (credits started), the button just offers the next episode. */
    data class Next(val inSeconds: Int?) : Floating
}

/** How long the credits countdown runs before the next episode starts. */
const val AUTO_NEXT_MS = 10_000L

class PlayerActivity : ComponentActivity() {
    private val c by lazy { (application as App).container }
    private lateinit var player: ExoPlayer
    private val ui = VodUi()
    private var title: TitleEntity? = null
    private var episode: EpisodeEntity? = null
    private var segmentsFor: String? = null
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
    private lateinit var frameRate: FrameRateMatch
    private var sleepCheck = true
    /** Episodes that started by themselves since the remote was last touched. */
    private var unattended = 0
    private var stillJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player = Playback.player(this, live = false)
        frameRate = FrameRateMatch(this, player)
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
                } else ui.error = Texts.get(R.string.play_failed)
            }
        })
        setContent { BoschTheme { VodScreen(player, ui, ::toggle, ::restart, ::playNext, { scrub(it > 0, 0) }, { frameRate.view = it },
            VodActions(::keepWatching)) { finish() } } }
        lifecycleScope.launch { load() }
        lifecycleScope.launch { sleepCheck = c.prefs.sleepCheck.first() }
        lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                if (released || !onScreen) continue
                // The sleep timer ran out: stop and leave the app, so the TV can go to sleep by itself.
                if (c.sleep.due()) { save(); finishAffinity(); continue }
                if (ui.stillWatching) continue
                ui.position = player.currentPosition.coerceAtLeast(0)
                ui.duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                // Credits reached and nobody pressed Back: on to the next episode after the countdown.
                val at = ui.creditsAt
                if (ui.inCredits && !ui.stayInCredits && at != null && ui.position - at >= AUTO_NEXT_MS) autoNext()
                // Skip times need the file's length to pick the right release: ask once it is known.
                val ep = episode
                if (ep != null && ui.duration > 0 && segmentsFor != ep.id) {
                    segmentsFor = ep.id
                    launch { ui.segments = c.skips.forEpisode(ep.titleKey, ep.season, ep.number, ui.duration) }
                }
                if (ui.visible && !ui.tracksOpen && ui.playing && !ui.scrubbing && System.currentTimeMillis() - ui.touched > 4500) { ui.visible = false; ui.seeking = false }
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
            val saved = c.library.progress(ContentIds.movie(t.key))
            // The version watched last time, as long as the provider still has it (another provider numbers them differently).
            val savedStream = saved?.streamId?.takeIf { id -> c.catalog.versions(t).any { it.streamId == id } }
            streamId = intent.getLongExtra(Playback.STREAM, 0).takeIf { it != 0L } ?: savedStream ?: t.sourceId
            container = intent.getStringExtra(Playback.CONTAINER) ?: saved?.container?.takeIf { savedStream == streamId } ?: t.container
            ui.title = t.name; ui.subtitle = t.year?.toString(); ui.plot = t.lPlot ?: t.dPlot ?: t.pPlot
            start(c.prefs.source().movie(streamId, container), if (restart || saved?.finished == true) 0 else saved?.positionMs ?: 0)
        } else {
            if (c.catalog.episodes(key).isEmpty()) c.catalog.detail(key, maxAgeMs = 0)
            val wanted = intent.getStringExtra(Playback.EPISODE)?.let { c.catalog.episode(it) }
            val (ep, pos) = if (wanted != null) {
                val p = c.library.progress(ContentIds.of(wanted))
                wanted to if (restart || p?.finished == true) 0L else p?.positionMs ?: 0L
            } else c.library.resumePoint(key) ?: return finish()
            startEpisode(ep, if (restart) 0 else pos)
        }
    }

    private suspend fun startEpisode(ep: EpisodeEntity, positionMs: Long) {
        episode = ep
        ui.episodes = true
        ui.plot = ep.plot
        ui.title = title?.name.orEmpty()
        ui.subtitle = Texts.get(R.string.episode_line, ep.season, ep.number, ep.name)
        ui.next = c.library.nextEpisode(ep.titleKey, ep.id)
        ui.segments = null; ui.stayInCredits = false; segmentsFor = null
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
        if (ui.next != null) autoNext() else finish()
    }

    /** The next episode starting by itself. After three in a row that nobody touched the remote for, ask first. */
    private fun autoNext() {
        if (ui.stillWatching) return
        if (sleepCheck && ++unattended >= 3) {
            save(ended = true)
            player.pause()
            ui.stillWatching = true; ui.visible = false; ui.tracksOpen = false
            // Nobody answers: they are asleep or gone. Leave the app so the stream is freed and the TV can sleep.
            stillJob?.cancel()
            stillJob = lifecycleScope.launch { delay(5 * 60_000L); if (ui.stillWatching) finishAffinity() }
            return
        }
        playNext()
    }

    private fun keepWatching() {
        stillJob?.cancel()
        ui.stillWatching = false; unattended = 0
        playNext()
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
        ui.seeking = true
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
                val lang = f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }?.replaceFirstChar(Char::uppercase)
                val label = listOfNotNull(lang ?: f.label ?: Texts.get(R.string.track_n, i + 1), f.label?.takeIf { lang != null && it != lang }).joinToString(" · ")
                TrackOption(label, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        ui.audio = options(C.TRACK_TYPE_AUDIO)
        val text = options(C.TRACK_TYPE_TEXT)
        ui.text = if (text.isEmpty()) emptyList() else listOf(TrackOption(Texts.get(R.string.off), text.none { it.selected }) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }) + text
    }

    // Lint wrongly flags the documented super.dispatchKeyEvent call as restricted (androidx.core marker).
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        unattended = 0
        if (ui.stillWatching) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
            return super.dispatchKeyEvent(event)
        }
        val wasVisible = ui.visible
        val floating = ui.floating   // as it was before this key
        ui.touched = System.currentTimeMillis()
        // The floating button: OK presses it; Back during the credits keeps you in the credits.
        if (floating != null && !wasVisible && !ui.scrubbing) {
            val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
            when {
                ok && floating is Floating.Skip -> { player.seekTo(floating.to); ui.position = floating.to; return true }
                ok && floating is Floating.Next -> { playNext(); return true }
                event.keyCode == KeyEvent.KEYCODE_BACK && floating is Floating.Next -> { ui.stayInCredits = true; return true }
            }
        }
        if (ui.inCredits) ui.stayInCredits = true
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
        if (!wasVisible || ui.scrubbing || ui.seeking) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(false, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(true, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                ui.scrubbing -> { commitScrub(); return true }
                !wasVisible || ui.seeking -> { toggle(); return true }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ui.scrubbing) commitScrub()
                ui.seeking = false   // to the buttons
                if (!wasVisible) { show(); return true }
            }
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

class VodActions(val keepWatching: () -> Unit)

@Composable
private fun VodScreen(
    player: ExoPlayer, ui: VodUi,
    onToggle: () -> Unit, onRestart: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onView: (PlayerView) -> Unit, actions: VodActions, onClose: () -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player; keepScreenOn = true; isFocusable = false }.also(onView) },
            modifier = Modifier.fillMaxSize(),
        )
        if (ui.buffering && ui.error == null) Spinner(Modifier.align(Alignment.Center))

        AnimatedVisibility(ui.visible || ui.error != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0xE6000000)))) {
                Column(Modifier.padding(start = 48.dp, top = 36.dp).fillMaxWidth(0.7f)) {
                    Text(ui.title, style = MaterialTheme.typography.headlineLarge, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    // Paused: what this film or episode is about.
                    if (!ui.playing && !ui.buffering && ui.error == null) ui.plot?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = Ink.Text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                ui.error?.let {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(it, style = MaterialTheme.typography.titleLarge, color = Ink.Text)
                        MainButton(stringResource(R.string.back), onClose)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Play sits in front of the time bar; the few other things are plain icons underneath, which
                    // name themselves when the remote is on them.
                    SeekBar(ui, onSeek, seekFocus, moreFocus) {
                        IconAction(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (ui.playing) R.string.pause else R.string.play), onToggle,
                            Modifier.focusRequester(playFocus).focusProperties { down = moreFocus }, size = 40.dp, caption = false)
                    }
                    if (!ui.scrubbing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconAction(Icons.Rounded.Replay, stringResource(R.string.from_start), onRestart, Modifier.focusRequester(moreFocus), plain = true)
                        if (ui.next != null) {
                            val counting = ui.nearEnd && !(ui.inCredits && ui.stayInCredits)
                            IconAction(Icons.Rounded.SkipNext, if (counting) stringResource(R.string.next_ep_in, ui.nextIn) else stringResource(R.string.next_episode), onNext,
                                Modifier.focusRequester(nextFocus), pinned = counting, plain = true)
                        }
                        // A series: straight to its episode list (the page under the player).
                        if (ui.episodes) IconAction(Icons.AutoMirrored.Rounded.List, stringResource(R.string.episodes), onClose, plain = true)
                        if (ui.text.isNotEmpty() || ui.audio.size > 1) IconAction(Icons.Rounded.Subtitles, stringResource(R.string.subs_audio), { ui.tracksOpen = true }, plain = true)
                    }
                }
            }
        }
        // After skipping the time bar keeps the remote (left/right go on skipping); otherwise Play/Pause has it.
        LaunchedEffect(ui.visible, ui.scrubbing, ui.tracksOpen, ui.seeking) {
            if (ui.visible && !ui.scrubbing && !ui.tracksOpen && !ui.stillWatching) { delay(80); runCatching { (if (ui.seeking) seekFocus else playFocus).requestFocus() } }
        }
        // Skip intro / Skip recap / Next episode: one button over the picture, without the control bar.
        // While the bar is hidden it has the focus (white), so a single OK presses it.
        val floating = ui.floating
        val floatFocus = remember { FocusRequester() }
        // What the button last said: it keeps saying that while it fades out, instead of emptying and shrinking.
        var lastFloating by remember { mutableStateOf(floating) }
        LaunchedEffect(floating) { if (floating != null) lastFloating = floating }
        AnimatedVisibility(floating != null && !ui.scrubbing && !ui.tracksOpen && !ui.stillWatching, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (ui.visible) 150.dp else 48.dp)) {
            when (val shown = floating ?: lastFloating) {
                is Floating.Skip -> OverPicture(stringResource(shown.label), null, { player.seekTo(shown.to); ui.position = shown.to }, Modifier.focusRequester(floatFocus))
                is Floating.Next -> OverPicture(stringResource(R.string.next_episode), shown.inSeconds, onNext, Modifier.focusRequester(floatFocus))
                null -> Unit
            }
        }
        LaunchedEffect(floating?.javaClass, ui.visible) {
            if (floating != null && !ui.visible) { delay(120); runCatching { floatFocus.requestFocus() } }
        }

        if (ui.tracksOpen) TrackPanel(ui, Modifier.align(Alignment.CenterEnd))
        if (ui.stillWatching) StillWatching(ui, actions.keepWatching, onClose)
    }
}

@Composable
private fun SeekBar(ui: VodUi, onSeek: (Long) -> Unit, focus: FocusRequester, below: FocusRequester, leading: @Composable () -> Unit) {
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
        leading()
        Text(clock(shown), style = MaterialTheme.typography.titleMedium, color = Ink.Text)
        Box(
            Modifier.weight(1f).height(18.dp)
                .focusRequester(focus)
                // Down goes to the first button, not to whichever button happens to sit under the middle of the bar.
                .focusProperties { down = below }
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
            // Where you are, while the bar has the remote: left/right move it, Down goes to the buttons.
            if (big) BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(Modifier.offset(x = maxWidth * f - 9.dp).size(18.dp).clip(CircleShape).background(Ink.Text))
            }
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
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (ui.text.isNotEmpty()) {
            item { Text(stringResource(R.string.subtitles), style = MaterialTheme.typography.titleLarge, color = Ink.Text, modifier = Modifier.padding(bottom = 6.dp)) }
            items(ui.text.size) { i ->
                val o = ui.text[i]
                ChoiceRow(o.label, o.selected, { o.select(); ui.tracksOpen = false }, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
        if (ui.audio.isNotEmpty()) {
            item { Text(stringResource(R.string.audio), style = MaterialTheme.typography.titleLarge, color = Ink.Text, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
            items(ui.audio.size) { i ->
                val o = ui.audio[i]
                ChoiceRow(o.label, o.selected, { o.select(); ui.tracksOpen = false }, if (i == 0 && ui.text.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

/**
 * The one button that lies over the picture: "Skip intro", "Skip recap", "Next episode". Always the same
 * size, whatever it says. Dark glass with a thin outline while the control bar has the remote, white once
 * it has it itself. A countdown ([seconds] until the next episode starts) fills it from the left and
 * shows its number where the arrow otherwise is.
 */
@Composable
private fun OverPicture(text: String, seconds: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val filled by androidx.compose.animation.core.animateFloatAsState(
        if (seconds == null) 0f else (1f - seconds * 1000f / AUTO_NEXT_MS).coerceIn(0f, 1f),
        androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing), label = "countdown")
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.size(220.dp, 44.dp).onFocusChanged { focused = it.isFocused },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xD90A0A24), focusedContainerColor = Ink.Text, pressedContainerColor = Ink.Text,
            contentColor = Ink.Text, focusedContentColor = Ink.Bg,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.5.dp, Color(0x73FFFFFF)), shape = shape),
            focusedBorder = androidx.tv.material3.Border.None,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (seconds != null) Box(Modifier.fillMaxHeight().fillMaxWidth(filled).background(if (focused) Ink.Accent.copy(alpha = 0.28f) else Color(0x33FFFFFF)))
            Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (seconds != null) Text("$seconds", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                else androidx.tv.material3.Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** "Are you still watching?": the picture dimmed, the next episode waiting for a yes. */
@Composable
private fun StillWatching(ui: VodUi, onContinue: () -> Unit, onStop: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xD9000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.still_watching), style = MaterialTheme.typography.displayMedium, color = Ink.Text)
            Text(listOfNotNull(ui.title, ui.next?.let { stringResource(R.string.episode_line, it.season, it.number, it.name) }).joinToString("  ·  "),
                style = MaterialTheme.typography.titleLarge, color = Ink.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MainButton(stringResource(R.string.keep_watching), onContinue, Modifier.focusRequester(focus), icon = Icons.Rounded.PlayArrow)
                MainButton(stringResource(R.string.stop_watching), onStop, primary = false)
            }
        }
    }
}

/** Loading indicator: a comet. A bright head with a tail that fades out behind it, going round. */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)), label = "angle")
    // The tail grows and shrinks as it goes, which reads as effort rather than as a clock hand.
    val tail by t.animateFloat(110f, 280f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "tail")
    androidx.compose.foundation.Canvas(modifier.size(46.dp)) {
        val width = 3.5.dp.toPx()
        val inset = width / 2
        val box = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
        val fade = Brush.sweepGradient(0f to Color.Transparent, tail / 360f * 0.6f to Ink.Accent, tail / 360f to Ink.Text, 1f to Color.Transparent)
        rotate(angle) {
            drawArc(fade, 0f, tail, useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = box,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
