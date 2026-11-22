package nl.boschtv.tv.ui

import nl.boschtv.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import nl.boschtv.tv.App
import nl.boschtv.tv.AppContainer
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.data.Card
import nl.boschtv.tv.data.HomeRow
import nl.boschtv.tv.data.RowStyle
import kotlin.math.abs

val Shape = RoundedCornerShape(10.dp)
val PillShape = RoundedCornerShape(50)
val EDGE = 48.dp

@Composable
fun rememberContainer(): AppContainer {
    val ctx = LocalContext.current
    return remember { (ctx.applicationContext as App).container }
}

/** Keeps the focused item at a fixed spot, so lists glide under a stationary selection like on a real TV app. */
class PivotSpec(private val pivotPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val d = offset - pivotPx
        return if (abs(d) < 1f) 0f else d
    }
}

@Composable
fun WithPivot(pivot: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { pivot.toPx() }
    val spec = remember(px) { PivotSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Any focusable tile: grows slightly and gets a white ring when the remote lands on it. */
@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    shape: RoundedCornerShape = Shape,
    scale: Float = 1.07f,
    color: Color = Ink.Surface,
    focusedColor: Color = Ink.SurfaceHi,
    ring: Boolean = true,
    focusedContent: Color = Ink.Text,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = if (onFocus != null) modifier.onFocusChanged { if (it.isFocused) onFocus() } else modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color, focusedContainerColor = focusedColor, pressedContainerColor = focusedColor,
            contentColor = Ink.Soft, focusedContentColor = focusedContent,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (ring) Border(BorderStroke(2.5.dp, Ink.Text), shape = shape) else Border.None,
        ),
        glow = ClickableSurfaceDefaults.glow(),
    ) { content() }
}

/** Pill button: white when focused. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primary: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 40.dp,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when { primary -> Ink.Accent; selected -> Ink.SurfaceHi; else -> Color.Transparent },
            focusedContainerColor = Ink.Text, pressedContainerColor = Ink.Text,
            contentColor = if (selected || primary) Ink.Text else Ink.Soft, focusedContentColor = Ink.Bg,
        ),
    ) {
        Row(Modifier.padding(horizontal = if (text.isEmpty()) 10.dp else 18.dp).height(height), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = text.ifEmpty { null }, modifier = Modifier.size(20.dp))
            if (icon != null && text.isNotEmpty()) Box(Modifier.width(8.dp))
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private val tmdbSize = Regex("""(image\.tmdb\.org/t/p/)[^/]+/""")

/** Most artwork comes from TMDB, which serves any width: ask for one that fits the tile, not a full-size poster. */
fun sized(url: String?, size: String): String? = url?.let { tmdbSize.replace(it, "$1$size/") }

@Composable
fun Art(url: String?, title: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, size: String = "w342") {
    if (url.isNullOrBlank()) {
        Box(modifier.background(Ink.SurfaceHi), contentAlignment = Alignment.Center) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Ink.Soft, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    } else {
        AsyncImage(model = sized(url, size), contentDescription = title, contentScale = scale, modifier = modifier.background(Ink.Surface))
    }
}

val POSTER_W = 118.dp
val POSTER_H = 177.dp

@Composable
fun PosterCard(card: Card, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null, rank: Int = 0, badge: Boolean = true) {
    Tile(onClick, modifier.size(POSTER_W, POSTER_H), onFocus = onFocus, scale = 1.12f) {
        Box(Modifier.fillMaxSize()) {
            Art(card.poster, card.title, Modifier.fillMaxSize())
            if (rank > 0) {
                Box(Modifier.fillMaxWidth().height(64.dp).align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60A0A24)))))
                Text("$rank", style = MaterialTheme.typography.displayMedium, color = Ink.Text,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp))
            } else if (badge && card.isNew) {
                Text(stringResource(R.string.label_new), style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Ink.Accent).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

val WIDE_W = 236.dp

@Composable
fun WideCard(card: Card, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    Column(Modifier.width(WIDE_W)) {
        Tile(onClick, modifier.size(WIDE_W, 133.dp), onFocus = onFocus, onLongClick = onLongClick, scale = 1.05f) {
            Box(Modifier.fillMaxSize()) {
                Art(card.wideImage ?: card.backdrop ?: card.poster, card.title, Modifier.fillMaxSize(), size = "w500")
                card.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(4.dp).background(Ink.Accent))
                    }
                }
            }
        }
        Text(card.title, style = MaterialTheme.typography.titleMedium, color = Ink.Text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        card.label?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Your own logo files in app/src/main/assets/icons (not in git), by lower-case name without extension. */
@Composable
private fun ownLogos(): Map<String, String> {
    val ctx = LocalContext.current
    return remember {
        runCatching { ctx.assets.list("icons")?.toList() }.getOrNull().orEmpty()
            .filter { it.substringAfterLast('.').lowercase() in setOf("png", "webp", "jpg", "svg") }
            .associate { it.substringBeforeLast('.').lowercase() to "file:///android_asset/icons/$it" }
    }
}

/**
 * The logo for a streaming service: your own file in app/src/main/assets/icons/<name>.png|svg when
 * there is one, otherwise the official logo from TMDB ([width] in TMDB's steps: w92, w154, w300).
 */
@Composable
fun serviceLogo(service: Service?, width: String = "w300"): String? {
    service ?: return null
    ownLogos()[service.name.lowercase()]?.let { return it }
    val logos by rememberContainer().trends.logos.collectAsState()
    return logos[service]?.let { "https://image.tmdb.org/t/p/$width$it" }
}

/**
 * A streaming service as its own app icon, so the row looks like a shelf of apps. Without a logo (no
 * TMDB token, or TMDB does not list the service): the name on the service's brand colour.
 */
@Composable
fun ServiceCard(service: Service, onClick: () -> Unit, onFocus: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val logo = serviceLogo(service)
    val shape = RoundedCornerShape(27.dp)   // squircle: a quarter of the side, like app icons on a phone
    Tile(onClick, modifier.size(104.dp), onFocus = onFocus, scale = 1.08f, shape = shape, color = Color(service.color), focusedColor = Color(service.color)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(service.label, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(8.dp))
            if (logo != null) AsyncImage(logo, service.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

const val ROW_TITLE_H = 36

/** One horizontal rail. The focused tile always sits at the left edge; everything else slides under it. */
@Composable
fun CardRow(
    row: HomeRow,
    onFocus: (Card?) -> Unit,
    onClick: (Card) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    onService: (Service) -> Unit = {},
    onMore: (() -> Unit)? = null,
    onLongClick: ((Card) -> Unit)? = null,
    /** "rowId/key" of the tile to come back to, and the requester that tile should answer to. */
    restoreId: String? = null,
    restore: FocusRequester? = null,
    onFocusId: ((String) -> Unit)? = null,
) {
    // A row that is already called "new" does not need a NIEUW label on every tile.
    val showNew = !(row.id.startsWith("new") || row.id.startsWith("svc"))
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.height(ROW_TITLE_H.dp).padding(start = EDGE), contentAlignment = Alignment.CenterStart) {
            Text(row.title, style = MaterialTheme.typography.titleLarge, color = Ink.Text, maxLines = 1)
        }
        WithPivot(EDGE) {
            LazyRow(
                contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (row.style == RowStyle.SERVICES) {
                    itemsIndexed(row.services, key = { _, s -> s.name }) { i, s ->
                        val id = "${row.id}/${s.name}"
                        ServiceCard(s, { onService(s) }, onFocus = { onFocus(null); onFocusId?.invoke(id) },
                            modifier = (if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                                .then(if (restore != null && id == restoreId) Modifier.focusRequester(restore) else Modifier)
                                .railEdge(i, row.services.size))
                    }
                } else {
                    itemsIndexed(row.cards, key = { _, c -> c.key }, contentType = { _, _ -> row.style }) { i, c ->
                        val more = onMore != null && row.cards.size >= 12
                        val id = "${row.id}/${c.key}"
                        val m = (if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(if (restore != null && id == restoreId) Modifier.focusRequester(restore) else Modifier)
                            .railEdge(i, row.cards.size + if (more) 1 else 0)
                        val focused = { onFocus(c); onFocusId?.invoke(id); Unit }
                        when (row.style) {
                            RowStyle.WIDE -> WideCard(c, { onClick(c) }, m, onFocus = focused, onLongClick = onLongClick?.let { { it(c) } })
                            RowStyle.TOP -> PosterCard(c, { onClick(c) }, m, onFocus = focused, rank = i + 1)
                            else -> PosterCard(c, { onClick(c) }, m, onFocus = focused, badge = showNew)
                        }
                    }
                    if (onMore != null && row.cards.size >= 12) item(key = "more", contentType = "more") {
                        Tile(onMore, Modifier.size(POSTER_W, POSTER_H).focusProperties { right = FocusRequester.Cancel }, onFocus = { onFocus(null) }) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.see_all), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** First and last tile of a rail: left/right stays put instead of jumping to whatever else is on screen. */
fun Modifier.railEdge(index: Int, count: Int): Modifier = if (index != 0 && index != count - 1) this else focusProperties {
    if (index == 0) left = FocusRequester.Cancel
    if (index == count - 1) right = FocusRequester.Cancel
}

@Composable
fun Dot(modifier: Modifier = Modifier) = Box(modifier.size(4.dp).clip(CircleShape).background(Ink.Muted))

/** "2026 • 1 u 49 min • Komedie, Romantiek" */
@Composable
fun MetaLine(parts: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.filter { it.isNotBlank() }.forEachIndexed { i, p ->
            if (i > 0) Dot()
            Text(p, style = MaterialTheme.typography.titleMedium, color = Ink.Soft, maxLines = 1)
        }
    }
}

fun duration(secs: Long): String = when {
    secs <= 0 -> ""
    secs >= 3600 -> Texts.get(R.string.duration_hm, secs / 3600, (secs % 3600) / 60)
    else -> Texts.get(R.string.duration_m, secs / 60)
}

/**
 * One entry in a menu bar (the top bar, and the bar inside a streaming service). The page you are on is marked with a blue underline; the white pill is
 * reserved for where the remote is right now, so the two never get confused.
 */
@Composable
fun NavItem(
    label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    description: String = label, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.then(Modifier.height(40.dp).then(androidx.compose.ui.Modifier).onFocusChanged { focused = it.isFocused }),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(PillShape),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = Ink.Text, pressedContainerColor = Ink.Text,
            contentColor = if (selected) Color.White else Ink.Muted, focusedContentColor = Ink.Bg,
        ),
    ) {
        Box(Modifier.height(40.dp).padding(horizontal = if (label.isEmpty()) 11.dp else 16.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (icon != null) androidx.tv.material3.Icon(icon, description, Modifier.size(if (label.isEmpty()) 20.dp else 17.dp))
                if (label.isNotEmpty()) Text(label, style = if (selected) MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            // A short underline under the word marks the page you are on.
            if (selected && !focused) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).width(if (label.isEmpty()) 14.dp else 22.dp).height(3.dp).background(Ink.Accent, PillShape))
        }
    }
}

