package nl.boschtv.tv.ui

import nl.boschtv.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Search
import nl.boschtv.tv.data.db.KIND_SERIES
import nl.boschtv.tv.data.db.KIND_MOVIE
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.data.Card
import nl.boschtv.tv.data.HomeRow
import nl.boschtv.tv.data.TitleQuery
import nl.boschtv.tv.data.kindLabel

@Immutable
data class BrowseArgs(val title: String, val query: TitleQuery, val filters: Boolean = false)

class Nav(
    val openTitle: (String) -> Unit,
    val openBrowse: (BrowseArgs) -> Unit,
    val openService: (Service) -> Unit,
    val openLive: (Long?) -> Unit,
)

/** Full-bleed artwork plus the essentials of whatever tile has focus. Purely local data: instant. */
@Composable
fun Hero(card: Card?, modifier: Modifier = Modifier, preview: Boolean = false, showService: Boolean = true) {
    // Text follows focus immediately; the big image waits a beat so fast scrolling stays smooth.
    var shown by remember { mutableStateOf(card) }
    LaunchedEffect(card?.key) { if (shown != null) delay(280); shown = card }

    Box(modifier.fillMaxSize().background(Ink.Bg)) {
        Crossfade(shown?.backdrop ?: shown?.poster, animationSpec = tween(350), label = "backdrop",
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.66f).fillMaxHeight(0.62f)) { url ->
            if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        }
        if (preview) Preview(card, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.66f).fillMaxHeight(0.62f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.Bg, 0.34f to Ink.Bg, 0.62f to Ink.Bg.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Ink.Bg.copy(alpha = 0.55f), 0.16f to Color.Transparent, 0.4f to Color.Transparent, 0.62f to Ink.Bg)))

        if (card != null) Column(Modifier.padding(start = EDGE, top = 84.dp).fillMaxWidth(0.5f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val eyebrow = buildList {
                if (card.isNew) add(Texts.get(R.string.label_new))
                val inCinema = card.flags and nl.boschtv.tv.core.Flags.CINEMA != 0 && (card.year ?: 0) >= java.time.Year.now().value - 1
                if (inCinema) add(Texts.get(R.string.label_cinema)) else if (showService) Service.of(card.services).firstOrNull()?.let { add(it.label.uppercase()) }
                add(card.kindLabel.uppercase())
            }.joinToString(" · ")
            Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = Ink.AccentSoft)
            // A title that needs two lines leaves room for less description, so nothing runs into the rows below.
            var titleLines by remember(card.key) { mutableStateOf(1) }
            Text(card.title, style = MaterialTheme.typography.displayMedium, color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { titleLines = it.lineCount })
            MetaLine(listOfNotNull(
                card.year?.toString(),
                card.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                genreLine(card.genres).ifBlank { null },
                card.label,
            ))
            card.plot?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Ink.Soft, maxLines = if (titleLines > 1) 1 else 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun HomeScreen(kind: Int?, service: Service?, nav: Nav, focus: FocusRequester, kids: Boolean = false, active: Boolean = true) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val catalogVersion by c.sync.version.collectAsState()
    val libraryVersion by c.library.version.collectAsState()
    var rows by remember(kind, service) { mutableStateOf<List<HomeRow>?>(null) }
    var focused by remember(kind, service) { mutableStateOf<Card?>(null) }
    val first = remember { FocusRequester() }
    // Where to land when focus comes back into the rows (from the top bar, a detail page, the player):
    // the tile you were on if it is still there, otherwise the first one.
    val restore = remember { FocusRequester() }
    var lastId by remember(kind, service) { mutableStateOf<String?>(null) }

    LaunchedEffect(kind, service, catalogVersion, libraryVersion) {
        rows = c.recommender.home(kind, service?.bit ?: 0L, kids)
    }
    val loaded = rows != null
    LaunchedEffect(loaded, kind, service) {
        if (loaded) { delay(60); runCatching { first.requestFocus() } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val list = rows
        Hero(focused ?: list?.firstOrNull { it.cards.isNotEmpty() }?.cards?.first(), preview = active, showService = service == null)
        val railsHeight = maxHeight * 0.52f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.52f)) {
            when {
                list == null -> Unit   // the logo animation below covers the first load
                list.isEmpty() -> Text(stringResource(R.string.nothing_yet),
                    style = MaterialTheme.typography.titleLarge, color = Ink.Muted, modifier = Modifier.padding(EDGE))
                else -> WithPivot((ROW_TITLE_H + 8).dp) {
                    val target = lastId
                    val stillThere = target != null && list.any { r ->
                        target.startsWith(r.id + "/") && (r.cards.any { "${r.id}/${it.key}" == target } || r.services.any { "${r.id}/${it.name}" == target })
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().focusRequester(focus)
                            .focusProperties { enter = { if (stillThere) restore else first } }.focusGroup(),
                        contentPadding = PaddingValues(bottom = railsHeight - 60.dp),
                    ) {
                        itemsIndexed(list, key = { _, r -> r.id }, contentType = { _, r -> r.style }) { i, row ->
                            CardRow(
                                row = row,
                                onFocus = { focused = it ?: focused },
                                onClick = { nav.openTitle(it.key) },
                                firstFocus = if (i == 0) first else null,
                                onService = nav.openService,
                                onMore = row.more?.let { q -> { nav.openBrowse(BrowseArgs(row.title, q.copy(limit = 60))) } },
                                onLongClick = if (row.id == "continue") { card -> scope.launch { c.library.removeFromContinue(card.key) }; Unit } else null,
                                restoreId = if (stillThere) target else null, restore = restore, onFocusId = { lastId = it },
                            )
                        }
                    }
                }
            }
        }
        if (list == null) Splash(Modifier.align(Alignment.Center))
    }
}

/** Shown for the moment the first rows load: the mark eases in and the wordmark follows. */
@Composable
fun Splash(modifier: Modifier = Modifier) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = true }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0.6f,
        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 180f), label = "scale")
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, tween(500, delayMillis = 150), label = "alpha")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(nl.boschtv.tv.R.drawable.ic_logo), "boschtv",
            Modifier.width(84.dp).fillMaxHeight(0.16f).graphicsLayer { scaleX = scale; scaleY = scale },
        )
        Text("  boschtv", style = MaterialTheme.typography.displayLarge, color = Ink.Text, modifier = Modifier.graphicsLayer { this.alpha = alpha; translationX = (1f - alpha) * -24f })
    }
}

/**
 * Rest on a title for a moment and it starts playing, muted, where its artwork was. The rows stay
 * exactly where they are. Stops as soon as you move on, open something, or leave the screen.
 */
@Composable
private fun Preview(card: Card?, modifier: Modifier) {
    val c = rememberContainer()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(card?.key) {
        url = null
        val key = card?.key ?: return@LaunchedEffect
        delay(2500)
        url = runCatching { c.catalog.previewUrl(key) }.getOrNull()
        delay(30_000)   // a taste, not the whole film: then back to the artwork
        url = null
    }
    // One stream at a time on this kind of line: the preview's connection is closed synchronously the
    // moment the screen is left, before the real player opens its own.
    val holder = remember { arrayOfNulls<androidx.media3.exoplayer.ExoPlayer>(1) }
    fun kill() { holder[0]?.release(); holder[0] = null; url = null }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { kill() }
    val stream = url ?: return
    var showing by remember(stream) { mutableStateOf(false) }
    val player = remember(stream) {
        nl.boschtv.tv.player.Playback.player(ctx, live = false).apply {
            volume = 0.5f   // previews play with sound, at half volume
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
            setMediaItem(nl.boschtv.tv.player.Playback.item(stream), 240_000L)   // skip logos and opening titles
            prepare(); playWhenReady = true
        }.also { holder[0] = it }
    }
    androidx.compose.runtime.DisposableEffect(stream) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() { showing = true }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { url = null }
        }
        player.addListener(listener)
        onDispose { if (holder[0] === player) { player.release(); holder[0] = null } }
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (showing) 1f else 0f, tween(500), label = "preview")
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            androidx.media3.ui.PlayerView(context).apply {
                useController = false; isFocusable = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}
