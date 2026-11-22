package nl.boschtv.tv.player

import nl.boschtv.tv.ui.Texts
import nl.boschtv.tv.R
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nl.boschtv.tv.App
import nl.boschtv.tv.data.db.ChannelEntity
import nl.boschtv.tv.data.db.ProgrammeEntity
import nl.boschtv.tv.ui.BoschTheme
import nl.boschtv.tv.ui.Ink
import nl.boschtv.tv.ui.Pill
import nl.boschtv.tv.ui.Tile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val GROUP_FAVORITES = "__fav"
const val GROUP_ALL = "__all"

data class LiveGroup(val id: String, val name: String)

class LiveUi {
    var groups by mutableStateOf<List<LiveGroup>>(emptyList())
    /** The list you zap through with up/down. */
    var zapGroup by mutableStateOf(GROUP_ALL)
    var zapList by mutableStateOf<List<ChannelEntity>>(emptyList())
    var current by mutableStateOf<ChannelEntity?>(null)
    var programmes by mutableStateOf<List<ProgrammeEntity>>(emptyList())
    var banner by mutableStateOf(false)
    var guide by mutableStateOf(false)
    /** The full timeline guide (all channels of the list, time along the top). */
    var timeline by mutableStateOf(false)
    val grid = TimelineUi()
    var digits by mutableStateOf("")
    var buffering by mutableStateOf(true)
    var problem by mutableStateOf<String?>(null)
    var favorites by mutableStateOf<Set<Long>>(emptySet())
    /** Set while watching a programme from its beginning instead of live. */
    var replay by mutableStateOf<ProgrammeEntity?>(null)
}

private val HHMM = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun hhmm(ms: Long) = HHMM.format(Date(ms))

/** Live TV that behaves like a TV: opens on the last channel, zaps with up/down, numbers jump, OK opens the guide. */
class LiveActivity : ComponentActivity() {
    private val c by lazy { (application as App).container }
    private lateinit var player: ExoPlayer
    private val ui = LiveUi()
    private var tuneJob: Job? = null
    private var bannerJob: Job? = null
    private var digitJob: Job? = null
    private var attempt = 0
    private var released = false
    private var onScreen = false
    private var ext = "m3u8"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player = Playback.player(this, live = true)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                ui.buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { attempt = 0; ui.problem = null }
                if (state == Player.STATE_ENDED) retry()
            }
            override fun onPlayerError(error: PlaybackException) = retry()
        })
        setContent { BoschTheme { LiveScreen(player, ui, ::channelsOf, ::pick, ::toggleFavorite, ::schedule, ::replay, ::openTimeline, ::gridSchedule) } }
        lifecycleScope.launch { c.library.favoriteChannelIds().collect { ui.favorites = it } }
        lifecycleScope.launch { init() }
    }

    private suspend fun init() {
        ext = c.prefs.liveExt()
        val dao = c.db.channels()
        ui.groups = listOf(LiveGroup(GROUP_FAVORITES, Texts.get(R.string.favorites))) + dao.groups().map { LiveGroup(it.id, it.name) } + LiveGroup(GROUP_ALL, Texts.get(R.string.all_channels))
        val (lastId, lastGroup) = c.prefs.lastChannel()
        val wantedId = intent.getLongExtra(Playback.CHANNEL, 0).takeIf { it != 0L }
        val wanted = (wantedId ?: lastId)?.let { dao.channel(it) }
        val first = wanted ?: dao.all().firstOrNull()
        if (first == null) { ui.problem = Texts.get(R.string.no_channels); return }
        val group = if (wantedId == null && lastGroup != null && channelsOf(lastGroup).any { it.id == first.id }) lastGroup else first.groupId
        ui.zapGroup = group
        ui.zapList = channelsOf(group)
        tune(first, immediate = true)
    }

    private suspend fun channelsOf(group: String): List<ChannelEntity> = when (group) {
        GROUP_FAVORITES -> c.library.favoriteChannels()
        GROUP_ALL -> c.db.channels().all()
        else -> c.db.channels().byGroup(group)
    }

    private suspend fun schedule(ch: ChannelEntity): List<ProgrammeEntity> {
        val out = ArrayList<ProgrammeEntity>()
        for (p in c.epg.upcoming(ch, 18)) {
            val last = out.lastOrNull()
            if (last != null && last.title == p.title && p.start - last.stop < 60_000) out[out.lastIndex] = last.copy(stop = p.stop) else out += p
        }
        return out
    }

    /** A longer stretch of a channel's guide for the timeline, starting an hour back. */
    private suspend fun gridSchedule(ch: ChannelEntity): List<ProgrammeEntity> {
        val out = ArrayList<ProgrammeEntity>()
        for (p in c.epg.upcoming(ch, 48, from = ui.grid.earliest)) {
            val last = out.lastOrNull()
            if (last != null && last.title == p.title && p.start - last.stop < 60_000) out[out.lastIndex] = last.copy(stop = p.stop) else out += p
        }
        // The provider's guide sometimes overlaps (a programme listed until 10:00 while the next starts
        // at 09:10). A timeline can only show one thing at a time: each programme ends where the next begins.
        out.sortBy { it.start }
        for (i in 0 until out.lastIndex) if (out[i].stop > out[i + 1].start) out[i] = out[i].copy(stop = out[i + 1].start)
        return out.filter { it.stop > it.start }
    }

    private fun openTimeline() {
        ui.grid.open(ui.zapList.indexOfFirst { it.id == ui.current?.id }.coerceAtLeast(0))
        ui.guide = false; ui.banner = false; ui.timeline = true
    }

    /** OK in the timeline: watch what is on now, or start a past programme from its beginning. */
    private fun timelineOk() {
        val g = ui.grid
        val ch = ui.zapList.getOrNull(g.row) ?: return
        val p = g.selected(ui.zapList)
        val now = System.currentTimeMillis()
        when {
            p == null || p.start <= now && p.stop > now -> {
                ui.timeline = false
                if (ch.id != ui.current?.id || ui.replay != null) pick(ui.zapGroup, ui.zapList, ch)
            }
            p.stop <= now && ch.archive -> { ui.timeline = false; replay(ch, p) }
            p.stop <= now -> g.note = Texts.get(R.string.no_catchup)
            else -> g.note = Texts.get(R.string.starts_at, hhmm(p.start))
        }
    }

    /** Number a channel answers to in the current list. */
    private fun numberOf(ch: ChannelEntity) = ui.zapList.indexOfFirst { it.id == ch.id } + 1

    private fun pick(group: String, list: List<ChannelEntity>, ch: ChannelEntity) {
        ui.zapGroup = group; ui.zapList = list; ui.guide = false
        tune(ch, immediate = true)
    }

    /** Watch a programme from its start through the provider's catch-up. Left/right skip, Back returns to live. */
    private fun replay(ch: ChannelEntity, p: ProgrammeEntity) {
        ui.guide = false; ui.current = ch; ui.replay = p; ui.problem = null; ui.buffering = true
        tuneJob?.cancel()
        tuneJob = lifecycleScope.launch {
            val minutes = ((p.stop - p.start) / 60_000).toInt().coerceIn(5, 360) + 5
            player.setMediaItem(Playback.item(c.prefs.source().timeshift(ch.id, p.start, minutes, c.prefs.serverZone())))
            player.prepare(); player.playWhenReady = true
        }
        showBanner()
    }

    private fun skip(ms: Long) { player.seekTo((player.currentPosition + ms).coerceAtLeast(0)); showBanner() }

    private fun tune(ch: ChannelEntity, immediate: Boolean = false) {
        ui.replay = null
        ui.current = ch; ui.programmes = emptyList(); ui.problem = null; ui.buffering = true
        showBanner()
        tuneJob?.cancel()
        tuneJob = lifecycleScope.launch {
            if (!immediate) delay(280)      // holding up/down flips through the list without opening every stream
            attempt = 0
            open(ch)
            c.prefs.saveLastChannel(ch.id, ui.zapGroup)
            ui.programmes = c.epg.upcoming(ch, 2)
        }
    }

    private suspend fun open(ch: ChannelEntity) {
        if (released || !onScreen) return
        player.setMediaItem(Playback.item(c.prefs.source().live(ch.id, ext)))
        player.prepare()
        player.playWhenReady = true
    }

    /** Streams drop; a TV just keeps trying. Second attempt switches container format, later ones back off. */
    private fun retry() {
        val ch = ui.current ?: return
        if (!onScreen || released) return      // never reconnect from the background: it would steal the single stream
        if (ui.replay != null) { ui.problem = Texts.get(R.string.replay_failed); return }
        attempt++
        if (attempt == 2) ext = if (ext == "m3u8") "ts" else "m3u8"
        if (attempt > 6) ui.problem = Texts.get(R.string.channel_down)
        lifecycleScope.launch {
            delay(minOf(8000L, 900L * attempt))
            if (onScreen && !released && ui.current?.id == ch.id) open(ch)
        }
    }

    private fun zap(delta: Int) {
        val list = ui.zapList
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == ui.current?.id }
        tune(list[((if (i < 0) 0 else i + delta) % list.size + list.size) % list.size])
    }

    private fun showBanner() {
        ui.banner = true
        bannerJob?.cancel()
        bannerJob = lifecycleScope.launch { delay(5000); ui.banner = false }
    }

    private fun digit(d: Int) {
        ui.digits = (ui.digits + d).takeLast(4)
        digitJob?.cancel()
        digitJob = lifecycleScope.launch {
            delay(1400)
            val n = ui.digits.toIntOrNull(); ui.digits = ""
            ui.zapList.getOrNull((n ?: 0) - 1)?.let { tune(it, immediate = true) }
        }
    }

    private fun toggleFavorite(ch: ChannelEntity) {
        lifecycleScope.launch { c.library.toggleFavorite(ch) }
    }

    // Lint wrongly flags the documented super.dispatchKeyEvent call as restricted (androidx.core marker).
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (ui.timeline) {
            val g = ui.grid
            val list = ui.zapList
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> g.move(list, 0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> g.move(list, 0, 1)
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> g.move(list, 0, 6)
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> g.move(list, 0, -6)
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> g.move(list, -1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> g.move(list, 1, 0)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> timelineOk()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_GUIDE -> ui.timeline = false
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        if (ui.guide) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_GUIDE) { ui.guide = false; return true }
            return super.dispatchKeyEvent(event)
        }
        if (ui.replay != null) when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { skip(-30_000); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { skip(30_000); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); showBanner(); return true }
            KeyEvent.KEYCODE_BACK -> { ui.current?.let { tune(it, immediate = true) }; return true }   // back to live
        }
        when (code) {
            // Arrows follow the channel list: down is the next channel (1 -> 2), up the previous one.
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> zap(1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> { ui.banner = false; ui.guide = true }
            // Right (or the remote's guide key) opens the timeline guide.
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> openTimeline()
            KeyEvent.KEYCODE_INFO -> if (ui.banner) ui.banner = false else showBanner()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> digit(code - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_BACK -> if (ui.banner) ui.banner = false else return super.dispatchKeyEvent(event)
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun releasePlayer() {
        if (released) return
        released = true
        tuneJob?.cancel()
        player.release()
    }

    override fun onStart() {
        super.onStart()
        val wasAway = !onScreen
        onScreen = true
        val ch = ui.current
        if (wasAway && ch != null && !released) lifecycleScope.launch { attempt = 0; open(ch) }
    }

    override fun onPause() { super.onPause(); if (isFinishing) releasePlayer() }

    override fun onStop() {
        super.onStop()
        onScreen = false
        if (!released) player.stop()   // closes the connection; pausing would keep the stream slot occupied
    }

    override fun onDestroy() { super.onDestroy(); releasePlayer() }

    @Composable
    private fun LiveScreen(
        player: ExoPlayer, ui: LiveUi,
        channelsOf: suspend (String) -> List<ChannelEntity>,
        onPick: (String, List<ChannelEntity>, ChannelEntity) -> Unit,
        onFavorite: (ChannelEntity) -> Unit,
        schedule: suspend (ChannelEntity) -> List<ProgrammeEntity>,
        onReplay: (ChannelEntity, ProgrammeEntity) -> Unit,
        onTimeline: () -> Unit,
        gridSchedule: suspend (ChannelEntity) -> List<ProgrammeEntity>,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player; keepScreenOn = true; isFocusable = false } },
                modifier = Modifier.fillMaxSize(),
            )
            if (ui.buffering && ui.problem == null && !ui.guide && !ui.timeline) Spinner(Modifier.align(Alignment.Center))
            ui.problem?.let {
                Text(it, style = MaterialTheme.typography.headlineMedium, color = Ink.Text,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Ink.Bg.copy(alpha = 0.85f)).padding(24.dp))
            }
            if (ui.digits.isNotEmpty()) Text(ui.digits, style = MaterialTheme.typography.displayLarge, color = Ink.Text,
                modifier = Modifier.align(Alignment.BottomEnd).padding(40.dp).clip(RoundedCornerShape(16.dp)).background(Ink.Bg.copy(alpha = 0.85f)).padding(horizontal = 24.dp, vertical = 8.dp))

            // At the top: channels put their own subtitles at the bottom, and the banner must not cover them.
            AnimatedVisibility(ui.banner && !ui.guide && !ui.timeline, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                ui.current?.let { Banner(it, numberOf(it), ui.programmes, ui.replay) }
            }
            AnimatedVisibility(ui.guide, enter = slideInHorizontally { -it / 3 } + fadeIn(), exit = slideOutHorizontally { -it / 3 } + fadeOut()) {
                Guide(ui, channelsOf, onPick, onFavorite, schedule, onReplay, onTimeline)
            }
            AnimatedVisibility(ui.timeline, enter = fadeIn(), exit = fadeOut()) {
                Timeline(ui.grid, ui.zapList, ui.groups.firstOrNull { it.id == ui.zapGroup }?.name ?: "", ui.current?.id, gridSchedule)
            }
        }
    }
}

@Composable
private fun Banner(ch: ChannelEntity, number: Int, programmes: List<ProgrammeEntity>, replay: ProgrammeEntity? = null) {
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF00A0A24), Color.Transparent))).padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 64.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(if (number > 0) "$number" else "", style = MaterialTheme.typography.displayLarge, color = Ink.AccentSoft)
            if (!ch.logo.isNullOrBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(72.dp, 48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ch.name, style = MaterialTheme.typography.headlineMedium, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val now = programmes.getOrNull(0)
                if (replay != null) {
                    Text(stringResource(R.string.from_start_banner, hhmm(replay.start), replay.title), style = MaterialTheme.typography.titleLarge, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.replay_keys), style = MaterialTheme.typography.titleMedium, color = Ink.Muted)
                } else if (now != null) {
                    Text("${hhmm(now.start)}  ${now.title}", style = MaterialTheme.typography.titleLarge, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressLine(now, Modifier.fillMaxWidth(0.5f))
                    programmes.getOrNull(1)?.let { Text(stringResource(R.string.up_next, hhmm(it.start), it.title), style = MaterialTheme.typography.titleMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text(stringResource(R.string.live_keys), style = MaterialTheme.typography.labelLarge, color = Ink.Muted)
        }
    }
}

@Composable
private fun ProgressLine(p: ProgrammeEntity, modifier: Modifier = Modifier) {
    val f = ((System.currentTimeMillis() - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.height(4.dp).clip(CircleShape).background(Ink.Line)) { Box(Modifier.fillMaxWidth(f).height(4.dp).background(Ink.Accent)) }
}

/** Three panes: lists, channels (with what is on now), and the selected channel's schedule. */
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")   // both produceState blocks do assign; lint misreads them
@Composable
private fun Guide(
    ui: LiveUi,
    channelsOf: suspend (String) -> List<ChannelEntity>,
    onPick: (String, List<ChannelEntity>, ChannelEntity) -> Unit,
    onFavorite: (ChannelEntity) -> Unit,
    schedule: suspend (ChannelEntity) -> List<ProgrammeEntity>,
    onReplay: (ChannelEntity, ProgrammeEntity) -> Unit,
    onTimeline: () -> Unit,
) {
    var group by remember { mutableStateOf(ui.zapGroup) }
    var channels by remember { mutableStateOf(ui.zapList) }
    var focused by remember { mutableStateOf(ui.current) }
    val currentFocus = remember { FocusRequester() }
    val startIndex = remember { ui.zapList.indexOfFirst { it.id == ui.current?.id }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 2).coerceAtLeast(0))

    LaunchedEffect(group) { if (group != ui.zapGroup || channels.isEmpty()) channels = channelsOf(group) }
    LaunchedEffect(Unit) { delay(120); runCatching { currentFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.Bg, 0.7f to Ink.Bg.copy(alpha = 0.94f), 1f to Ink.Bg.copy(alpha = 0.55f)))) {
        LazyColumn(Modifier.width(206.dp).fillMaxHeight(), contentPadding = PaddingValues(start = 28.dp, top = 36.dp, bottom = 36.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "timeline") {
                Pill(stringResource(R.string.tv_guide), onTimeline, Modifier.fillMaxWidth().padding(bottom = 8.dp), primary = true, icon = Icons.Rounded.DateRange)
            }
            items(ui.groups, key = { it.id }) { g ->
                Pill(g.name, { group = g.id; if (g.id == ui.zapGroup) channels = ui.zapList }, Modifier.fillMaxWidth(), selected = g.id == group)
            }
        }
        LazyColumn(Modifier.width(370.dp).fillMaxHeight(), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (channels.isEmpty()) item {
                Text(stringResource(if (group == GROUP_FAVORITES) R.string.no_favorites else R.string.no_channels_list),
                    style = MaterialTheme.typography.titleMedium, color = Ink.Muted, modifier = Modifier.padding(16.dp))
            }
            itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                val isCurrent = ch.id == ui.current?.id
                Tile(
                    onClick = { onPick(group, channels, ch) },
                    onLongClick = { onFavorite(ch) },
                    onFocus = { focused = ch },
                    modifier = Modifier.fillMaxWidth().height(60.dp).then(if (isCurrent || (startIndex == 0 && i == 0 && channels.none { it.id == ui.current?.id })) Modifier.focusRequester(currentFocus) else Modifier),
                    scale = 1.03f,
                    color = if (isCurrent) Ink.SurfaceHi else Color.Transparent,
                    focusedColor = Ink.Text, ring = false, focusedContent = Ink.Bg,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(38.dp), maxLines = 1)
                        if (!ch.logo.isNullOrBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(44.dp, 30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val now by produceState<ProgrammeEntity?>(null, ch.id) { delay(350); value = schedule(ch).firstOrNull() }
                            now?.let { Text(it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        if (ch.id in ui.favorites) Icon(Icons.Rounded.Star, stringResource(R.string.favorite), tint = Ink.Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 40.dp, top = 40.dp)) {
            val ch = focused
            if (ch != null) {
                Text(ch.name, style = MaterialTheme.typography.headlineLarge, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val list by produceState<List<ProgrammeEntity>?>(null, ch.id) { value = null; delay(300); value = schedule(ch) }
                when {
                    list == null -> Unit
                    list!!.isEmpty() -> Text(stringResource(R.string.no_guide_channel), style = MaterialTheme.typography.titleMedium, color = Ink.Muted, modifier = Modifier.padding(top = 14.dp))
                    else -> list!!.take(8).forEachIndexed { i, p ->
                        Row(Modifier.padding(top = if (i == 0) 16.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(hhmm(p.start), style = MaterialTheme.typography.titleMedium, color = if (i == 0) Ink.AccentSoft else Ink.Muted, modifier = Modifier.width(48.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = if (i == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                    color = if (i == 0) Ink.Text else Ink.Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (i == 0) {
                                    ProgressLine(p, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                    p.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                                    // Channels with catch-up: start the current programme from its beginning.
                                    if (ch.archive && p.start < System.currentTimeMillis()) {
                                        Pill(stringResource(R.string.watch_from_start), { onReplay(ch, p) }, Modifier.padding(top = 8.dp), selected = true)
                                    }
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.guide_keys), style = MaterialTheme.typography.labelLarge, color = Ink.Muted, modifier = Modifier.padding(top = 22.dp))
            }
        }
    }
}
