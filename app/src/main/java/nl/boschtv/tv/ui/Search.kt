package nl.boschtv.tv.ui

import nl.boschtv.tv.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import nl.boschtv.tv.data.Card

/** On-screen keyboard on the left, live results on the right: far quicker with a remote than the system keyboard. */
/** Search over the whole catalog, or with [service] only within that streaming service. */
@Composable
fun SearchScreen(nav: Nav, focus: FocusRequester, service: nl.boschtv.tv.core.Service? = null) {
    val c = rememberContainer()
    var text by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<Card>>(emptyList()) }
    val firstKey = remember { FocusRequester() }
    val homeLocale = c.prefs.content.collectAsState(nl.boschtv.tv.core.ContentPrefs()).value.home.locale
    // Speak instead of type: uses the TV's own speech recognition when it has one.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
    }
    val prompt = stringResource(R.string.search_prompt)
    val speak = {
        runCatching {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, homeLocale)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt))
        }
        Unit
    }

    LaunchedEffect(text) {
        if (text.trim().length < 2) { titles = emptyList(); return@LaunchedEffect }
        delay(180)
        titles = c.catalog.search(text, services = service?.bit ?: 0L)
    }
    LaunchedEffect(Unit) { delay(120); runCatching { firstKey.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(top = 84.dp)) {
        Column(Modifier.width(300.dp).padding(start = EDGE).focusRequester(focus).focusRestorer { firstKey }) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                Text(text.ifEmpty { if (service != null) stringResource(R.string.search_in, service.label) else stringResource(R.string.search_hint) }, style = MaterialTheme.typography.headlineMedium,
                    color = if (text.isEmpty()) Ink.Muted else Ink.Text, maxLines = 1)
            }
            val keys = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }
            val rowEnds = remember { List(6) { FocusRequester() } }
            keys.chunked(6).forEachIndexed { r, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    line.forEachIndexed { i, k ->
                        // Left on the first key jumps to the last key of the row. Right on the last key goes to the results.
                        val wrap = when (i) {
                            0 -> Modifier.focusProperties { left = rowEnds[r] }
                            line.lastIndex -> Modifier.focusRequester(rowEnds[r])
                            else -> Modifier
                        }
                        Tile({ text += k }, Modifier.size(38.dp).then(if (r == 0 && i == 0) Modifier.focusRequester(firstKey) else Modifier).then(wrap), scale = 1.12f) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(k.uppercase(), style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                Tile(speak, Modifier.size(59.dp, 38.dp), scale = 1.06f, color = Ink.Accent, focusedColor = Ink.Text, focusedContent = Ink.Bg) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Mic, "Inspreken") } }
                Tile({ text += " " }, Modifier.size(59.dp, 38.dp), scale = 1.06f) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SpaceBar, stringResource(R.string.key_space)) } }
                Tile({ text = text.dropLast(1) }, Modifier.size(59.dp, 38.dp), scale = 1.06f) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.key_delete)) } }
                Tile({ text = "" }, Modifier.size(59.dp, 38.dp), scale = 1.06f) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.key_clear), style = MaterialTheme.typography.labelLarge) } }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(POSTER_W),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 28.dp, end = EDGE, top = 22.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(titles, key = { it.key }) { card -> PosterCard(card, { nav.openTitle(card.key) }) }
            if (text.trim().length >= 2 && titles.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.nothing_found_for, text), style = MaterialTheme.typography.titleLarge, color = Ink.Muted)
            }
        }
    }
}
