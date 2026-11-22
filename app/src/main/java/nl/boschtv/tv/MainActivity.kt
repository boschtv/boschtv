package nl.boschtv.tv

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.data.Card
import nl.boschtv.tv.data.SyncState
import nl.boschtv.tv.data.TitleQuery
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.KIND_SERIES
import nl.boschtv.tv.data.xtream.Source
import nl.boschtv.tv.player.Playback
import nl.boschtv.tv.ui.BoschTheme
import nl.boschtv.tv.ui.BrowseArgs
import nl.boschtv.tv.ui.BrowseScreen
import nl.boschtv.tv.ui.DetailScreen
import nl.boschtv.tv.ui.HomeScreen
import nl.boschtv.tv.ui.Ink
import nl.boschtv.tv.ui.LoginScreen
import nl.boschtv.tv.ui.Nav
import nl.boschtv.tv.ui.NavItem
import nl.boschtv.tv.ui.Pill
import nl.boschtv.tv.ui.SearchScreen
import nl.boschtv.tv.ui.SettingsScreen
import nl.boschtv.tv.ui.rememberContainer

class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink = intent?.data
        (application as App).container.refreshIfStale()
        setContent { BoschTheme { Root(deepLink) { deepLink = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink = intent.data
    }
}

private enum class Tab(@androidx.annotation.StringRes val label: Int) { SEARCH(R.string.search), HOME(R.string.tab_home), MOVIES(R.string.tab_movies), SERIES(R.string.tab_series), GENRES(R.string.tab_genres), KIDS(R.string.tab_kids), MYLIST(R.string.tab_my_list), LIVE(R.string.tab_live), SETTINGS(R.string.settings) }

/** Screens stacked on top of the current tab. */
private sealed interface Layer {
    data class Detail(val key: String) : Layer
    data class Browse(val args: BrowseArgs) : Layer
}

@Composable
private fun Root(deepLink: android.net.Uri?, onDeepLinkHandled: () -> Unit) {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val source by c.prefs.source.collectAsState(initial = null)
    val src = source ?: return Box(Modifier.fillMaxSize().background(Ink.Bg))
    if (!src.isConfigured) { LoginScreen(); return }

    var tab by remember { mutableStateOf(Tab.HOME) }
    // boschtv://play?key=… (a tile on the Google TV home screen) resumes straight into the player;
    // boschtv://live and boschtv://search open those screens, e.g. from a launcher shortcut.
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        when (link.host) {
            "play" -> link.getQueryParameter("key")?.let { Playback.title(ctx, it) }
            "live" -> Playback.live(ctx)
            "search" -> tab = Tab.SEARCH
        }
        onDeepLinkHandled()
    }
    val layers = remember { mutableStateListOf<Layer>() }
    // The streaming service the whole app is limited to; null is everything. Chosen from the logo.
    var scope by remember { mutableStateOf<Service?>(null) }
    var picker by remember { mutableStateOf(false) }
    // A newer version offered by the updater: asked once per version per launch.
    val update by c.updater.state.collectAsState()
    // The update window only opens right after the app is opened, before anything has been started;
    // later it would interrupt. Otherwise a dot on the settings icon is enough. "Later" lasts a day.
    var skipped by remember { mutableStateOf<String?>(null) }
    val snoozed by c.prefs.snoozedUpdate.collectAsState(initial = null)
    var promptAllowed by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(45_000); promptAllowed = false }
    val offered = (update as? nl.boschtv.tv.system.Updater.State.Available)?.versionName
    LaunchedEffect(layers.size, tab) { if (layers.isNotEmpty() || tab != Tab.HOME) promptAllowed = false }
    val updating = update is nl.boschtv.tv.system.Updater.State.Downloading ||
        (offered != null && promptAllowed && offered != skipped && offered != snoozed)
    val later = { offered?.let { v -> skipped = v; c.scope.launch { c.prefs.snoozeUpdate(v) } }; Unit }
    val content = remember { FocusRequester() }
    val nav = remember {
        Nav(
            openTitle = { layers += Layer.Detail(it) },
            openBrowse = { layers += Layer.Browse(it) },
            openService = { scope = it; tab = Tab.HOME; layers.clear() },
            openLive = { Playback.live(ctx, it) },
        )
    }
    BackHandler(updating || picker || layers.isNotEmpty() || tab != Tab.HOME || scope != null) {
        when {
            updating -> later()
            picker -> picker = false
            layers.isNotEmpty() -> layers.removeAt(layers.lastIndex)
            tab != Tab.HOME -> tab = Tab.HOME
            else -> scope = null   // Back on a service's home page returns to everything
        }
    }
    LaunchedEffect(picker) { if (!picker && layers.isEmpty()) { delay(60); runCatching { content.requestFocus() } } }
    // Coming back from a detail page lands on the tile you left from.
    val depth = layers.size
    LaunchedEffect(depth) { if (depth == 0) { delay(60); runCatching { content.requestFocus() } } }
    // Returning from the player or live TV: put the remote back where it was.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (layers.isEmpty()) runCatching { content.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        // The tab stays composed underneath so its scroll position and focus survive.
        val front = layers.isEmpty() && !picker && !updating
        Box(Modifier.fillMaxSize().gate(front)) {
            when (tab) {
                Tab.HOME -> HomeScreen(null, scope, nav, content, active = front)
                Tab.MOVIES -> HomeScreen(KIND_MOVIE, scope, nav, content, active = front)
                Tab.SERIES -> HomeScreen(KIND_SERIES, scope, nav, content, active = front)
                Tab.GENRES -> BrowseScreen(BrowseArgs(stringResource(R.string.tab_genres), TitleQuery(services = scope?.bit ?: 0L), filters = true), nav, content)
                Tab.KIDS -> HomeScreen(null, scope, nav, content, kids = true, active = front)
                Tab.MYLIST -> MyList(nav, content)
                Tab.SEARCH -> SearchScreen(nav, content, scope)
                Tab.SETTINGS -> SettingsScreen(content)
                Tab.LIVE -> Unit
            }
            TopBar(tab, scope, enabled = front, updateWaiting = offered != null, onScope = { picker = true }) { t -> if (t == Tab.LIVE) Playback.live(ctx) else tab = t }
        }
        layers.forEachIndexed { i, layer ->
            val top = i == layers.lastIndex && !picker && !updating
            Box(Modifier.fillMaxSize().background(Ink.Bg).gate(top)) {
                val own = remember(layer) { FocusRequester() }
                when (layer) {
                    is Layer.Detail -> DetailScreen(layer.key, nav)
                    is Layer.Browse -> BrowseScreen(layer.args, nav, own)
                }
            }
        }
        if (picker) ScopePicker(scope) { picked -> scope = picked; tab = Tab.HOME; picker = false }
        if (updating) UpdatePrompt(update, onLater = later)
        SyncBadge(Modifier.align(Alignment.BottomEnd))
    }
}

/** A region the remote may only move into while it is the one in front. Never focusable itself. */
private fun Modifier.gate(open: Boolean): Modifier =
    focusProperties { enter = { if (open) FocusRequester.Default else FocusRequester.Cancel } }.focusGroup()

@Composable
private fun TopBar(selected: Tab, scope: Service?, enabled: Boolean, updateWaiting: Boolean, onScope: () -> Unit, onSelect: (Tab) -> Unit) {
    // A soft shade behind the bar keeps it readable over bright artwork.
    Box(Modifier.fillMaxWidth().height(104.dp).background(Brush.verticalGradient(listOf(Ink.Bg.copy(alpha = 0.92f), Ink.Bg.copy(alpha = 0.55f), Color.Transparent))))
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp).gate(enabled),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScopeButton(scope, onScope)
        Spacer(Modifier.width(8.dp))
        NavItem("", selected == Tab.SEARCH, Icons.Rounded.Search, stringResource(R.string.search)) { onSelect(Tab.SEARCH) }
        for (t in listOf(Tab.HOME, Tab.MOVIES, Tab.SERIES, Tab.GENRES, Tab.KIDS, Tab.MYLIST)) {
            NavItem(stringResource(t.label), selected == t) { onSelect(t) }
        }
        Spacer(Modifier.weight(1f))
        // Live TV is a different mode, so it sits apart on the right, but it is an ordinary item.
        NavItem(stringResource(Tab.LIVE.label), false, Icons.Rounded.LiveTv) { onSelect(Tab.LIVE) }
        // A new version waiting: a small dot on the settings icon, nothing that interrupts.
        Box {
            NavItem("", selected == Tab.SETTINGS, Icons.Rounded.Settings, stringResource(R.string.settings)) { onSelect(Tab.SETTINGS) }
            if (updateWaiting) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(9.dp).background(Ink.Accent, CircleShape))
        }
    }
}

/** "A new version is here": download and hand over to Android's installer, or later. */
@Composable
private fun UpdatePrompt(state: nl.boschtv.tv.system.Updater.State, onLater: () -> Unit) {
    val c = rememberContainer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    // Coming back from Android's install screen (or its permission setting): the button needs the remote again.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Ink.Bg.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(Ink.Surface, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(48.dp))
            when (state) {
                is nl.boschtv.tv.system.Updater.State.Downloading -> {
                    Text(stringResource(R.string.update_downloading, state.versionName), style = MaterialTheme.typography.headlineSmall, color = Ink.Text)
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Ink.Line, nl.boschtv.tv.ui.PillShape)) {
                        Box(Modifier.fillMaxWidth(state.progress).height(6.dp).background(Ink.Accent, nl.boschtv.tv.ui.PillShape))
                    }
                    Text(stringResource(R.string.update_then_android), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                }
                is nl.boschtv.tv.system.Updater.State.Available -> {
                    Text(stringResource(R.string.update_available, state.versionName), style = MaterialTheme.typography.headlineSmall, color = Ink.Text)
                    state.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Soft, maxLines = 6) }
                    Text(stringResource(R.string.update_keeps), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.update_now), { scope.launch { c.updater.install() } }, Modifier.focusRequester(first), primary = true)
                        Pill(stringResource(R.string.later), onLater, selected = true)
                    }
                }
                else -> Unit
            }
        }
    }
}

/** The logo doubles as the switch between everything and one streaming service. */
@Composable
private fun ScopeButton(scope: Service?, onClick: () -> Unit) {
    val logo = nl.boschtv.tv.ui.serviceLogo(scope, "w92")
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = Ink.Text, pressedContainerColor = Ink.Text,
            contentColor = Ink.Text, focusedContentColor = Ink.Bg,
        ),
    ) {
        Row(Modifier.height(48.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (logo != null) coil.compose.AsyncImage(logo, scope?.label,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)))
            else Image(painterResource(R.drawable.ic_logo), "BoschTV", Modifier.size(36.dp))
            androidx.tv.material3.Icon(Icons.Rounded.ExpandMore, stringResource(R.string.choose_service), Modifier.padding(start = 4.dp).size(18.dp))
        }
    }
}

/** Opened from the logo: everything, or one streaming service. Back closes it without changing anything. */
@Composable
private fun ScopePicker(current: Service?, onPick: (Service?) -> Unit) {
    val c = rememberContainer()
    val services by androidx.compose.runtime.produceState(emptyList<Service>()) { value = c.catalog.services() }
    val start = remember { FocusRequester() }
    LaunchedEffect(services.size) { delay(80); runCatching { start.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Ink.Bg.copy(alpha = 0.78f))) {
        Column(
            Modifier.padding(start = 40.dp, top = 76.dp).width(668.dp)
                .background(Ink.Surface, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(20.dp),
        ) {
            Text(stringResource(R.string.what_to_watch), style = MaterialTheme.typography.titleLarge, color = Ink.Text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        nl.boschtv.tv.ui.Tile({ onPick(null) }, (if (current == null) Modifier.focusRequester(start) else Modifier).size(88.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(23.dp), scale = 1.08f, color = Ink.Accent, focusedColor = Ink.Accent) {
                            Image(painterResource(R.drawable.ic_logo), stringResource(R.string.everything), Modifier.fillMaxSize())
                        }
                        Text(stringResource(R.string.everything), style = MaterialTheme.typography.labelMedium, color = if (current == null) Ink.Text else Ink.Muted, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                items(services.size) { i ->
                    val s = services[i]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        nl.boschtv.tv.ui.ServiceCard(s, { onPick(s) }, modifier = (if (current == s) Modifier.focusRequester(start) else Modifier).size(88.dp))
                        Text(s.label, style = MaterialTheme.typography.labelMedium, color = if (current == s) Ink.Text else Ink.Muted, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MyList(nav: Nav, focus: FocusRequester) {
    val c = rememberContainer()
    val version by c.library.version.collectAsState()
    var cards by remember { mutableStateOf<List<Card>?>(null) }
    LaunchedEffect(version) { cards = c.library.myList() }
    cards?.let { BrowseScreen(BrowseArgs(stringResource(R.string.tab_my_list), TitleQuery()), nav, focus, fixed = it) }
}

/** Small, unobtrusive note while the catalog refreshes in the background. */
@Composable
private fun SyncBadge(modifier: Modifier) {
    val c = rememberContainer()
    val state by c.sync.state.collectAsState()
    val s = state as? SyncState.Running ?: return
    Text(stringResource(R.string.catalog_updating, (s.progress * 100).toInt()), style = MaterialTheme.typography.labelLarge, color = Ink.Soft,
        modifier = modifier.padding(20.dp).background(Ink.Surface.copy(alpha = 0.9f), nl.boschtv.tv.ui.PillShape).padding(horizontal = 14.dp, vertical = 8.dp))
}
