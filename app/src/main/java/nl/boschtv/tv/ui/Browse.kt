package nl.boschtv.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.data.Card
import nl.boschtv.tv.data.Sort
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.KIND_SERIES

private const val PAGE = 60

private val thisYear = java.time.Year.now().value
/** Label, first year, last year. Combines with kind, sort and genres. */
private val yearChoices: List<Triple<String, Int?, Int?>> = listOf(
    Triple("Alle jaren", null, null),
    Triple("$thisYear", thisYear, thisYear),
    Triple("${thisYear - 1}", thisYear - 1, thisYear - 1),
    Triple("${thisYear - 2}", thisYear - 2, thisYear - 2),
    Triple("2020 – ${thisYear - 3}", 2020, thisYear - 3),
    Triple("Jaren 10", 2010, 2019),
    Triple("Jaren 00", 2000, 2009),
    Triple("Jaren 90", 1990, 1999),
    Triple("Ouder", null, 1989),
)

/** A full grid of titles: "Alles bekijken", Genres, Mijn lijst. With [BrowseArgs.filters] it has a filter rail. */
@Composable
fun BrowseScreen(args: BrowseArgs, nav: Nav, focus: FocusRequester, fixed: List<Card>? = null) {
    val c = rememberContainer()
    var query by remember(args) { mutableStateOf(args.query.copy(limit = PAGE, offset = 0)) }
    val items = remember(args) { mutableStateListOf<Card>() }
    var total by remember(args) { mutableIntStateOf(-1) }
    var exhausted by remember(args) { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }
    val firstFilter = remember { FocusRequester() }

    LaunchedEffect(query, fixed) {
        if (fixed != null) { items.clear(); items.addAll(fixed); total = fixed.size; exhausted = true; return@LaunchedEffect }
        val page = c.catalog.cards(query)
        if (query.offset == 0) { items.clear(); total = c.catalog.count(query) }
        val known = items.mapTo(HashSet()) { it.key }
        items.addAll(page.filter { it.key !in known })
        exhausted = page.size < PAGE
    }
    LaunchedEffect(args) { delay(150); runCatching { if (args.filters) firstFilter.requestFocus() else firstCard.requestFocus() } }

    var open by remember(args) { mutableStateOf<String?>("genres") }
    val heads = remember { listOf("kind", "sort", "year", "genres").associateWith { FocusRequester() } }
    val toggle = { id: String -> open = if (open == id) null else id }
    // Choosing a value closes the section again; focus goes to its header first, so it is never lost.
    val pick = { id: String, change: (nl.boschtv.tv.data.TitleQuery) -> nl.boschtv.tv.data.TitleQuery ->
        runCatching { heads.getValue(id).requestFocus() }
        query = change(query).copy(offset = 0); open = null
    }
    val kindLabel = when (query.kind) { KIND_MOVIE -> "Films"; KIND_SERIES -> "Series"; else -> "Alles" }
    val yearLabel = yearChoices.firstOrNull { it.second == query.minYear && it.third == query.maxYear }?.first ?: "Alle jaren"

    Row(Modifier.fillMaxSize().padding(top = 84.dp)) {
        if (args.filters) LazyColumn(
            Modifier.width(232.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = EDGE, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Each filter is one line showing its current choice; OK opens it. Genres start open,
            // because that is what this page is for, and the remote starts on the first genre.
            item { FilterHeader("Soort", kindLabel, open == "kind", Modifier.focusRequester(heads.getValue("kind"))) { toggle("kind") } }
            if (open == "kind") {
                item { Pill("Alles", { pick("kind") { it.copy(kind = null) } }, Modifier.fillMaxWidth(), selected = query.kind == null) }
                item { Pill("Films", { pick("kind") { it.copy(kind = KIND_MOVIE) } }, Modifier.fillMaxWidth(), selected = query.kind == KIND_MOVIE) }
                item { Pill("Series", { pick("kind") { it.copy(kind = KIND_SERIES) } }, Modifier.fillMaxWidth(), selected = query.kind == KIND_SERIES) }
            }
            item { FilterHeader("Sorteren", query.sort.label, open == "sort", Modifier.focusRequester(heads.getValue("sort"))) { toggle("sort") } }
            if (open == "sort") items(listOf(Sort.NEW, Sort.RATING, Sort.YEAR, Sort.AZ)) { o ->
                Pill(o.label, { pick("sort") { it.copy(sort = o, dutchFirst = o == Sort.NEW) } }, Modifier.fillMaxWidth(), selected = query.sort == o)
            }
            item { FilterHeader("Jaar", yearLabel, open == "year", Modifier.focusRequester(heads.getValue("year"))) { toggle("year") } }
            if (open == "year") items(yearChoices) { (label, from, to) ->
                Pill(label, { pick("year") { it.copy(minYear = from, maxYear = to) } }, Modifier.fillMaxWidth(),
                    selected = query.minYear == from && query.maxYear == to)
            }
            item {
                val n = java.lang.Long.bitCount(query.genresAll)
                FilterHeader("Genres", if (n == 0) "Alle" else "$n gekozen", open == "genres", Modifier.focusRequester(heads.getValue("genres"))) { toggle("genres") }
            }
            if (open == "genres") listItemsIndexed(Genre.entries) { i, g ->
                val on = query.genresAll and g.bit != 0L
                Pill(g.label, { query = query.copy(genresAll = query.genresAll xor g.bit, offset = 0) },
                    if (i == 0) Modifier.fillMaxWidth().focusRequester(firstFilter) else Modifier.fillMaxWidth(), selected = on)
            }
        }
        Column(Modifier.fillMaxSize()) {
            val genres = Genre.of(query.genresAll).joinToString(" + ") { it.label }
            val service = Service.of(query.services).firstOrNull()?.label
            val years = yearChoices.firstOrNull { it.second == query.minYear && it.third == query.maxYear && (it.second != null || it.third != null) }?.first
            val heading = if (args.filters) listOfNotNull(genres.ifBlank { "Alle genres" }, years, service).joinToString(" · ") else args.title
            Row(Modifier.padding(start = if (args.filters) 12.dp else EDGE, bottom = 12.dp)) {
                Text(heading, style = MaterialTheme.typography.headlineLarge, color = Ink.Text)
                if (total >= 0) Text("   $total titels", style = MaterialTheme.typography.titleMedium, color = Ink.Muted, modifier = Modifier.padding(top = 12.dp))
            }
            if (total == 0) Text("Niets gevonden met deze keuze.", style = MaterialTheme.typography.titleLarge, color = Ink.Muted,
                modifier = Modifier.padding(start = if (args.filters) 12.dp else EDGE))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(POSTER_W),
                state = grid,
                modifier = Modifier.fillMaxSize().focusRequester(focus),
                contentPadding = PaddingValues(start = if (args.filters) 16.dp else EDGE, end = EDGE, top = 22.dp, bottom = 64.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.key }) { i, card ->
                    if (!exhausted && i >= items.size - 14) LaunchedEffect(items.size) {
                        if (query.offset + PAGE <= items.size) query = query.copy(offset = query.offset + PAGE)
                    }
                    PosterCard(card, { nav.openTitle(card.key) }, if (i == 0) Modifier.focusRequester(firstCard) else Modifier)
                }
            }
        }
    }
}

/** A collapsible filter: its name, the current choice, and an arrow showing whether it is open. */
@Composable
private fun FilterHeader(name: String, value: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Ink.Surface, focusedContainerColor = Ink.Text, pressedContainerColor = Ink.Text,
            contentColor = Ink.Text, focusedContentColor = Ink.Bg,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.6f))
                Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            androidx.tv.material3.Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
        }
    }
}
