package nl.boschtv.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import nl.boschtv.tv.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import nl.boschtv.tv.data.db.EpisodeEntity
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.ProgressEntity
import nl.boschtv.tv.data.db.TitleEntity
import nl.boschtv.tv.data.toCard
import nl.boschtv.tv.player.Playback

@Composable
fun DetailScreen(key: String, nav: Nav, atEpisode: Boolean = false) {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryVersion by c.library.version.collectAsState()

    var title by remember(key) { mutableStateOf<TitleEntity?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var episodes by remember(key) { mutableStateOf<List<EpisodeEntity>>(emptyList()) }
    var progress by remember(key) { mutableStateOf<Map<String, ProgressEntity>>(emptyMap()) }
    var resume by remember(key) { mutableStateOf<Pair<EpisodeEntity, Long>?>(null) }
    var inList by remember(key) { mutableStateOf(false) }
    var liked by remember(key) { mutableStateOf<Boolean?>(null) }
    var similar by remember(key) { mutableStateOf<List<Card>>(emptyList()) }
    var trailer by remember(key) { mutableStateOf<String?>(null) }
    var season by remember(key) { mutableIntStateOf(-1) }
    val playFocus = remember { FocusRequester() }
    /** The episode being watched, for landing on it when coming back from the player. */
    val episodeFocus = remember { FocusRequester() }
    var inRows by remember(key) { mutableStateOf(false) }
    /** Set once focus has been in the rows: only then does entering the buttons mean "coming up from below". */
    var inRowsBefore by remember(key) { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var titleLines by remember(key) { mutableIntStateOf(1) }

    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        loading = true
        title = c.catalog.title(key)                    // instant, from the local catalog
        inList = c.library.inList(key); liked = c.library.rating(key)
        episodes = c.catalog.episodes(key)
        if (attempt == 0 && !atEpisode) { delay(80); runCatching { playFocus.requestFocus() } }
        title = c.catalog.detail(key) ?: title          // then plot, cast and a fresh episode list
        // The provider's description is often English only: TMDB may have it in the viewer's language.
        if (title?.lPlotLocale != c.prefs.content().home.locale && c.trends.localizePlot(key)) title = c.catalog.title(key) ?: title
        episodes = c.catalog.episodes(key)
        loading = false
        title?.let { similar = c.recommender.similar(it.toCard()) }
        trailer = runCatching { c.trends.trailer(key) }.getOrNull()
    }
    LaunchedEffect(key, libraryVersion, episodes.size) {
        progress = c.library.progressOf(key).associateBy { it.contentId }
        resume = c.library.resumePoint(key)
        if (season < 0 || episodes.none { it.season == season }) season = resume?.first?.season ?: episodes.firstOrNull()?.season ?: -1
    }

    // Back from the player: on the episode being watched when this page was opened from "Continue watching"
    // (and it is a series), otherwise on the play button.
    val landOnEpisode = atEpisode && episodes.isNotEmpty()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (!landOnEpisode || !runCatching { episodeFocus.requestFocus() }.getOrDefault(false)) runCatching { playFocus.requestFocus() }
    }
    val t = title
    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        if (t == null) return@Box
        val backdrop = t.dBackdrop ?: t.pBackdrop ?: t.poster
        if (backdrop != null) AsyncImage(backdrop, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.68f).fillMaxHeight(0.66f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.Bg, 0.32f to Ink.Bg, 0.62f to Ink.Bg.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 0.66f to Ink.Bg)))

        // The header never scrolls; only the episodes / "more like this" area underneath does.
        Column(Modifier.fillMaxSize()) {
            // Full header while you are on its buttons; once you move down into seasons and episodes it
            // collapses to the title line so the list gets the room. Going back up expands it again.
            // The full header leaves room for the line that names the focused button.
            val headerHeight by androidx.compose.animation.core.animateDpAsState(
                when { inRows && titleLines > 1 -> 174.dp; inRows -> 134.dp; titleLines > 1 -> 344.dp; else -> 304.dp }, label = "header")
            Box(Modifier.fillMaxWidth().height(headerHeight).clipToBounds()) {
                Column(Modifier.padding(start = EDGE, top = 40.dp).fillMaxWidth(0.52f).wrapContentHeight(Alignment.Top, unbounded = true), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val eyebrow = (listOf(stringResource(if (t.kind == KIND_MOVIE) R.string.kind_movie else R.string.kind_series).uppercase()) + Service.of(t.services).take(2).map { it.label.uppercase() }).joinToString(" · ")
                    Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = Ink.AccentSoft)
                    Text(t.name, style = MaterialTheme.typography.displayMedium, color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { titleLines = it.lineCount })
                    val seasons = episodes.map { it.season }.distinct().size
                    MetaLine(listOfNotNull(
                        t.year?.toString(),
                        duration(t.durationSecs).ifBlank { null },
                        if (seasons > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.n_seasons, seasons, seasons) else null,
                        t.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                        genreLine(t.genres).ifBlank { null },
                    ))
                    Text(t.lPlot ?: t.dPlot ?: t.pPlot ?: if (loading) "" else stringResource(R.string.no_description), style = MaterialTheme.typography.bodyLarge,
                        color = Ink.Soft, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(66.dp))
                    t.actors?.let { Text(stringResource(R.string.with_cast, it), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }

                    // The buttons may run wider than the text column above them.
                    // Coming up from the rows below: always the first button (Play / Continue), not the one that
                    // happens to sit above the tile. Moving left and right inside the row is left alone.
                    var buttonsHadFocus by remember { mutableStateOf(false) }
                    Row(Modifier.padding(top = 10.dp).wrapContentWidth(Alignment.Start, unbounded = true)
                        .onFocusChanged { f -> if (f.hasFocus && !buttonsHadFocus && inRowsBefore) runCatching { playFocus.requestFocus() }; buttonsHadFocus = f.hasFocus }
                        .focusGroup(),   // so the row knows when focus is anywhere inside it, not only on its first button
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (t.kind == KIND_MOVIE) {
                            val p = progress["m:${t.key}"]?.takeIf { !it.finished && it.positionMs > 0 }
                            val left = p?.let { (it.durationMs - it.positionMs) / 60_000 }
                            MainButton(if (p != null) stringResource(R.string.continue_left, left!!.toInt()) else stringResource(R.string.play), { Playback.title(ctx, t.key) },
                                Modifier.focusRequester(playFocus), icon = Icons.Rounded.PlayArrow)
                            if (p != null) IconAction(Icons.Rounded.Replay, stringResource(R.string.from_start), { Playback.title(ctx, t.key, restart = true) })
                            c.catalog.versions(t).drop(1).forEach { v ->
                                MainButton(versionLabel(v.label), { Playback.title(ctx, t.key, streamId = v.streamId, container = v.container) }, primary = false)
                            }
                        } else {
                            val r = resume
                            val label = when {
                                // Nothing came back: the provider did not answer. Offer another try rather than claim there is nothing.
                                r == null -> if (loading || episodes.isNotEmpty()) stringResource(R.string.loading) else stringResource(R.string.play)
                                r.second > 0 -> stringResource(R.string.continue_ep, r.first.season, r.first.number)
                                progress.isNotEmpty() -> stringResource(R.string.next_ep, r.first.season, r.first.number)
                                else -> stringResource(R.string.play_ep, r.first.season, r.first.number)
                            }
                            MainButton(label, { if (r != null) Playback.title(ctx, t.key, episodeId = r.first.id) else if (!loading) attempt++ }, Modifier.focusRequester(playFocus),
                                icon = Icons.Rounded.PlayArrow)
                        }
                        // Only when there is one: from the provider, or TMDB's official trailer.
                        trailer?.let { id ->
                            IconAction(Icons.Rounded.Movie, stringResource(R.string.trailer), { Playback.trailer(ctx, id, t.name, t.dBackdrop ?: t.pBackdrop ?: t.poster) })
                        }
                        IconAction(if (inList) Icons.Rounded.Check else Icons.Rounded.Add, if (inList) stringResource(R.string.in_my_list) else stringResource(R.string.tab_my_list), { scope.launch { inList = c.library.toggleList(t.key) } })
                        IconAction(Icons.Rounded.ThumbUp, stringResource(R.string.like), { scope.launch { liked = if (liked == true) null else true; c.library.rate(t.key, liked) } }, active = liked == true)
                        IconAction(Icons.Rounded.ThumbDown, stringResource(R.string.dislike), { scope.launch { liked = if (liked == false) null else false; c.library.rate(t.key, liked) } }, active = liked == false)
                    }
                }
            }
            // The pivot leaves room for the season buttons above the episode that has focus.
            WithPivot(64.dp) { LazyColumn(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) inRowsBefore = true; inRows = it.hasFocus }
                // Up from the top row goes back to the play button (which also expands the header again).
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                        if (!focusManager.moveFocus(FocusDirection.Up)) runCatching { playFocus.requestFocus() }
                        true
                    } else false
                }, contentPadding = PaddingValues(top = 4.dp, bottom = 260.dp)) {
            if (episodes.isNotEmpty()) {
                val seasons = episodes.map { it.season }.distinct().sorted()
                item("seasons") {
                    LazyRow(Modifier.padding(top = 4.dp), contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(seasons, key = { it }) { s -> Pill(stringResource(R.string.season_n, s), { season = s }, selected = s == season) }
                    }
                }
                item("episodes") {
                    val shown = episodes.filter { it.season == season }
                    val current = resume?.first?.id
                    // Opened from "Continue watching": the row starts at the episode being watched.
                    val rowState = androidx.compose.foundation.lazy.rememberLazyListState(
                        if (atEpisode) shown.indexOfFirst { it.id == current }.coerceAtLeast(0) else 0)
                    LaunchedEffect(current, season) {
                        val i = shown.indexOfFirst { it.id == current }
                        if (atEpisode && i >= 0) rowState.scrollToItem(i)
                    }
                    WithPivot(EDGE) {
                        LazyRow(state = rowState, contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 12.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(shown.size, key = { shown[it].id }) { index ->
                                val e = shown[index]
                                val p = progress[nl.boschtv.tv.data.db.ContentIds.of(e)]
                                val frac = p?.let { if (it.finished) 1f else if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f }
                                val card = Card(e.id, 1, "${e.number}. ${e.name}", null, null, e.image ?: backdrop, 0.0, 0, 0, 0, 0, 0, e.plot,
                                    progress = frac, label = listOfNotNull(duration(e.durationSecs).ifBlank { null }, if (p?.finished == true) stringResource(R.string.watched) else null).joinToString(" · ").ifBlank { null },
                                    wideImage = e.image ?: backdrop)
                                WideCard(card, { Playback.title(ctx, t.key, episodeId = e.id) },
                                    Modifier.railEdge(index, shown.size).then(if (e.id == current) Modifier.focusRequester(episodeFocus) else Modifier),
                                    description = e.plot)
                            }
                        }
                    }
                }
            }
            // The episodes did not come (the provider did not answer): say so in words; Play tries again.
            if (t.kind != KIND_MOVIE && episodes.isEmpty() && !loading) item("failed") {
                Text(stringResource(R.string.episodes_failed), style = MaterialTheme.typography.titleMedium, color = Ink.Soft, modifier = Modifier.padding(start = EDGE, top = 4.dp, bottom = 12.dp))
            }
            if (similar.size >= 5) item("similar") {
                Box(Modifier.padding(top = 6.dp)) {
                    CardRow(HomeRow("similar", stringResource(R.string.more_like_this), similar), onFocus = {}, onClick = { nav.openTitle(it.key) })
                }
            }
            } }
        }
    }
}
