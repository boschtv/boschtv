package nl.boschtv.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import nl.boschtv.tv.data.db.ChannelEntity
import nl.boschtv.tv.data.db.ProgrammeEntity
import nl.boschtv.tv.ui.Ink
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

const val HALF_HOUR = 30 * 60_000L
/** How much time fits across the screen. */
const val SPAN = 150 * 60_000L

fun floorHalfHour(ms: Long) = ms - Math.floorMod(ms, HALF_HOUR)

/**
 * State of the timeline guide. The remote never moves between the programme blocks themselves:
 * the selection is a channel row plus a moment in time, and the blocks are drawn from that. Moving
 * up and down keeps the moment, so you land on whatever is on at the same time on the next channel.
 */
class TimelineUi {
    var row by mutableIntStateOf(0)
    var cursor by mutableLongStateOf(0L)
    var windowStart by mutableLongStateOf(0L)
    var earliest = 0L
    var note by mutableStateOf<String?>(null)
    val programmes = mutableStateMapOf<Long, List<ProgrammeEntity>>()

    fun open(row: Int, now: Long = System.currentTimeMillis()) {
        this.row = row
        cursor = now
        windowStart = floorHalfHour(now - 15 * 60_000L)   // a little of what just ended stays in view
        earliest = floorHalfHour(now - 60 * 60_000L)   // the guide keeps the last hour
        note = null
        programmes.clear()
    }

    fun selected(channels: List<ChannelEntity>): ProgrammeEntity? {
        val ch = channels.getOrNull(row) ?: return null
        return programmes[ch.id]?.firstOrNull { it.start <= cursor && it.stop > cursor }
    }

    fun move(channels: List<ChannelEntity>, dx: Int, dy: Int) {
        note = null
        if (dy != 0) row = (row + dy).coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        if (dx != 0) {
            val list = channels.getOrNull(row)?.let { programmes[it.id] }.orEmpty()
            val p = selected(channels)
            cursor = if (dx > 0) {
                val next = if (p != null) list.firstOrNull { it.start >= p.stop } else list.firstOrNull { it.start > cursor }
                next?.start ?: (cursor + HALF_HOUR)
            } else {
                val prev = if (p != null) list.lastOrNull { it.stop <= p.start } else list.lastOrNull { it.stop <= cursor }
                if (prev != null && prev.stop > earliest) maxOf(prev.start, earliest) else maxOf(cursor - HALF_HOUR, earliest)
            }
            cursor = cursor.coerceAtMost(System.currentTimeMillis() + 24 * 3600_000L)
        }
        while (cursor >= windowStart + SPAN - HALF_HOUR) windowStart += HALF_HOUR
        while (cursor < windowStart && windowStart > earliest) windowStart -= HALF_HOUR
    }
}

private val zone = ZoneId.systemDefault()
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private fun hm(ms: Long) = HM.format(Instant.ofEpochMilli(ms).atZone(zone))

private fun dayLabel(ms: Long, now: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> ""
        today.plusDays(1) -> "Morgen "
        today.minusDays(1) -> "Gisteren "
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("nl")).replaceFirstChar(Char::uppercase) + " "
    }
}

private const val ROW_H = 54

/** One drawn block: a single programme, or a run of short ones (children's TV, short news) shown together. */
private class Segment(val items: List<ProgrammeEntity>) {
    val start get() = items.first().start
    val stop get() = items.last().stop
    val titles get() = items.map { it.title }.distinct()
}

private const val SHORT = 15 * 60_000L

/** Programmes under a quarter of an hour that follow each other become one block, so their titles fit. */
private fun segments(list: List<ProgrammeEntity>): List<Segment> {
    val out = ArrayList<Segment>()
    var run = ArrayList<ProgrammeEntity>()
    fun flush() { if (run.isNotEmpty()) { out += Segment(run); run = ArrayList() } }
    for (p in list) {
        val short = p.stop - p.start < SHORT
        val joins = short && run.isNotEmpty() && p.start - run.last().stop < 60_000
        if (!joins) flush()
        run += p
        if (!short) flush()
    }
    flush()
    return out
}

/** Channels down the side, time along the top, a line at "now". The live picture keeps playing top right. */
@Composable
fun Timeline(
    t: TimelineUi,
    channels: List<ChannelEntity>,
    listName: String,
    playing: Long?,
    load: suspend (ChannelEntity) -> List<ProgrammeEntity>,
) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (t.row - 2).coerceAtLeast(0))
    LaunchedEffect(t.row) {
        val target = (t.row - 2).coerceAtLeast(0)
        if (abs(listState.firstVisibleItemIndex - target) > 6) listState.scrollToItem(target) else listState.animateScrollToItem(target)
    }
    val ch = channels.getOrNull(t.row)
    val sel = t.selected(channels)

    Column(Modifier.fillMaxSize()) {
        // ---- the selected programme, over the live picture
        Box(
            Modifier.fillMaxWidth().weight(0.4f)
                .background(Brush.horizontalGradient(0f to Ink.Bg, 0.42f to Ink.Bg.copy(alpha = 0.92f), 0.72f to Color.Transparent)),
        ) {
            Column(Modifier.fillMaxWidth(0.56f).padding(start = 48.dp, top = 30.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ch != null) Text("${t.row + 1}   ${ch.name}".uppercase(), style = MaterialTheme.typography.labelLarge, color = Ink.AccentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sel != null) {
                    val live = sel.start <= now && sel.stop > now
                    Text(sel.title, style = MaterialTheme.typography.headlineMedium, color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val mins = (sel.stop - sel.start) / 60_000
                    Text("${dayLabel(sel.start, now)}${hm(sel.start)} – ${hm(sel.stop)}  ·  $mins min" + if (live) "  ·  nog ${(sel.stop - now) / 60_000} min" else "",
                        style = MaterialTheme.typography.titleSmall, color = Ink.Soft)
                    if (live) {
                        val f = ((now - sel.start).toFloat() / (sel.stop - sel.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth(0.7f).height(4.dp).clip(CircleShape).background(Ink.Line)) { Box(Modifier.fillMaxWidth(f).height(4.dp).background(Ink.Accent)) }
                    }
                    sel.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                } else if (ch != null) {
                    Text(if (ch.id !in t.programmes) "Gids laden…" else "Geen gids voor dit moment", style = MaterialTheme.typography.headlineSmall, color = Ink.Soft)
                }
                val action = when {
                    t.note != null -> t.note!!
                    sel == null || sel.start <= now && sel.stop > now -> "OK  kijken"
                    sel.stop <= now && ch?.archive == true -> "OK  terugkijken vanaf het begin"
                    sel.stop <= now -> "Al geweest"
                    else -> "Begint ${dayLabel(sel.start, now).lowercase()}om ${hm(sel.start)}"
                }
                Text(action, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (t.note != null) Ink.AccentSoft else Ink.Text, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // ---- the grid
        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.6f).background(Ink.Bg).padding(start = 40.dp, end = 28.dp, top = 6.dp)) {
            val chW = 214.dp
            val perMin: Dp = (maxWidth - chW) / (SPAN / 60_000f)
            fun x(ms: Long): Dp = perMin * ((ms - t.windowStart) / 60_000f)

            // half-hour lines behind everything
            for (i in 0..4) {
                Box(Modifier.offset(x = chW + x(t.windowStart + i * HALF_HOUR)).width(1.dp).fillMaxHeight().background(Ink.Line.copy(alpha = 0.6f)))
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(32.dp)) {
                    Text(listName, style = MaterialTheme.typography.titleSmall, color = Ink.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(chW - 16.dp).align(Alignment.CenterStart))
                    for (i in 0..4) {
                        val ms = t.windowStart + i * HALF_HOUR
                        Text(hm(ms), style = MaterialTheme.typography.labelLarge, color = Ink.Muted,
                            modifier = Modifier.align(Alignment.CenterStart).offset(x = chW + x(ms) + 6.dp))
                    }
                }
                LazyColumn(
                    state = listState, userScrollEnabled = false,
                    contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                        ChannelRow(t, c, i + 1, i == t.row, c.id == playing, chW, perMin, now, load)
                    }
                }
            }
            // now line, over the blocks
            if (now >= t.windowStart && now < t.windowStart + SPAN) {
                Box(Modifier.offset(x = chW + x(now) - 1.dp, y = 26.dp).width(2.dp).fillMaxHeight().background(Ink.Accent))
                Box(Modifier.offset(x = chW + x(now) - 5.dp, y = 22.dp).size(10.dp).clip(CircleShape).background(Ink.Accent))
            }
        }
    }
}

@Composable
private fun ChannelRow(
    t: TimelineUi, ch: ChannelEntity, number: Int, selectedRow: Boolean, playing: Boolean,
    chW: Dp, perMin: Dp, now: Long, load: suspend (ChannelEntity) -> List<ProgrammeEntity>,
) {
    // Only rows on screen are composed, so the guide is fetched for what you look at, top to bottom.
    LaunchedEffect(ch.id) { if (ch.id !in t.programmes) t.programmes[ch.id] = runCatching { load(ch) }.getOrDefault(emptyList()) }
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(ROW_H.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(chW - 8.dp).fillMaxHeight().clip(shape)
                .background(if (selectedRow) Ink.SurfaceHi else Ink.Surface.copy(alpha = 0.55f)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$number", style = MaterialTheme.typography.titleSmall, color = if (playing) Ink.AccentSoft else Ink.Muted, modifier = Modifier.width(34.dp), maxLines = 1)
            if (!ch.logo.isNullOrBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp, 26.dp))
            Text(ch.name, style = MaterialTheme.typography.titleSmall, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val list = t.programmes[ch.id]
            val end = t.windowStart + SPAN
            when {
                list == null -> Box(Modifier.fillMaxSize().clip(shape).background(Ink.Surface.copy(alpha = 0.35f)))
                list.none { it.stop > t.windowStart && it.start < end } -> Box(
                    Modifier.fillMaxSize().clip(shape).background(if (selectedRow) Ink.Text else Ink.Surface.copy(alpha = 0.35f)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("Geen gids", style = MaterialTheme.typography.labelLarge, color = if (selectedRow) Ink.Bg else Ink.Muted) }
                else -> for (seg in segments(list)) {
                    if (seg.stop <= t.windowStart || seg.start >= end) continue
                    val from = maxOf(seg.start, t.windowStart)
                    val to = minOf(seg.stop, end)
                    val x = perMin * ((from - t.windowStart) / 60_000f)
                    val w = (perMin * ((to - from) / 60_000f) - 4.dp).coerceAtLeast(3.dp)
                    val inner = if (selectedRow) seg.items.firstOrNull { it.start <= t.cursor && it.stop > t.cursor } else null
                    val sel = inner != null
                    val live = seg.start <= now && seg.stop > now
                    val past = seg.stop <= now
                    // Selected inside a run of short programmes: that one's title first.
                    val label = if (inner != null && seg.items.size > 1) (listOf(inner.title) + seg.titles.filter { it != inner.title }).joinToString(" · ") else seg.titles.joinToString(" · ")
                    Box(
                        Modifier.offset(x = x).width(w).fillMaxHeight().clip(shape)
                            .background(when { sel -> Ink.Text; live -> Ink.SurfaceHi; past -> Ink.Surface.copy(alpha = 0.5f); else -> Ink.Surface })
                            .padding(horizontal = if (w > 30.dp) 10.dp else 2.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // Too narrow to read: the block alone shows there is something; selecting it shows the title above.
                        if (w > 46.dp) Column {
                            Text((if (seg.start < t.windowStart) "‹ " else "") + label, style = MaterialTheme.typography.titleSmall,
                                color = when { sel -> Ink.Bg; past -> Ink.Muted; else -> Ink.Text }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (w > 80.dp) Text(hm((inner ?: seg.items.first()).start), style = MaterialTheme.typography.labelSmall,
                                color = if (sel) Ink.Bg.copy(alpha = 0.7f) else Ink.Muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
