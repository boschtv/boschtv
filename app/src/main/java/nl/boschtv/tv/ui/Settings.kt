@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package nl.boschtv.tv.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import nl.boschtv.tv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.boschtv.tv.core.ContentPrefs
import nl.boschtv.tv.data.AccountState
import nl.boschtv.tv.data.SyncState
import nl.boschtv.tv.data.xtream.Source
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(focus: FocusRequester) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val source by c.prefs.source.collectAsState(Source())
    val content by c.prefs.content.collectAsState(ContentPrefs())
    val account by c.prefs.account.collectAsState(AccountState(null, 1, false, null))
    val lastSync by c.prefs.lastSync.collectAsState(0L)
    val sync by c.sync.state.collectAsState()
    val homeRow by c.watchNext.status.collectAsState()
    LaunchedEffect(Unit) { c.watchNext.publish() }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val date = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val time = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() } }

    fun setContent(new: ContentPrefs) = scope.launch { c.prefs.saveContent(new); note = Texts.get(R.string.settings_changed); c.refreshIfStale(force = true) }

    // Two columns instead of one long list: everything fits on one screen, nothing scrolls under the top bar.
    Row(Modifier.fillMaxSize().padding(start = EDGE, end = EDGE, top = 88.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        // Both columns scroll along with the remote once they are taller than the screen.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge, color = Ink.Text)

            Heading(stringResource(R.string.subscription))
            Info(stringResource(R.string.server), source.host.removePrefix("http://").removePrefix("https://"))
            Info(stringResource(R.string.user), source.username)
            Info(stringResource(R.string.valid_until), account.expiresAt?.let { date.format(Date(it)).let { d -> if (account.trial) stringResource(R.string.trial_suffix, d) else d } } ?: stringResource(R.string.unknown))
            Info(stringResource(R.string.screens), androidx.compose.ui.res.pluralStringResource(R.plurals.n_screens, account.maxConnections, account.maxConnections))
            account.problem?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.AccentSoft) }

            Heading(stringResource(R.string.catalog))
            val status = when (val st = sync) {
                is SyncState.Running -> "${st.message} ${(st.progress * 100).toInt()}%"
                is SyncState.Failed -> stringResource(R.string.failed_msg, st.message)
                SyncState.Idle -> if (lastSync > 0) stringResource(R.string.last_updated, time.format(Date(lastSync))) else stringResource(R.string.not_updated)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill(stringResource(R.string.update_catalog), { c.refreshIfStale(force = true) }, Modifier.focusRequester(focus), selected = true)
                Text(status, style = MaterialTheme.typography.titleMedium, color = Ink.Soft)
            }
            Text(stringResource(R.string.auto_updates), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            val report by c.prefs.syncReport.collectAsState(initial = null)
            report?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Soft, maxLines = 2) }

            Heading(stringResource(R.string.gtv_home))
            Info(stringResource(R.string.continue_watching), homeRow)
            Text(stringResource(R.string.gtv_note), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 44.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading(stringResource(R.string.what_to_see))
            // Your language: kept, with English as fallback; every other language is left out.
            var choosing by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill(content.home.title(), { choosing = !choosing }, selected = true, icon = if (choosing) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore)
                Column {
                    Text(stringResource(R.string.content_language), style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                    Text(stringResource(R.string.other_langs_left_out), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                }
            }
            if (choosing) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in nl.boschtv.tv.core.Language.choices) {
                    Pill(l.title(), { choosing = false; if (l != content.home) setContent(content.copy(home = l)) }, selected = l == content.home, primary = l == content.home)
                }
            }
            if (content.home != nl.boschtv.tv.core.Language.ENGLISH) {
                Toggle(stringResource(R.string.english_fallback), stringResource(R.string.english_fallback_hint, content.home.title().substringBefore(" (")), content.english) { setContent(content.copy(english = !content.english)) }
                Toggle(stringResource(R.string.english_live), stringResource(R.string.english_live_hint), content.englishLive) { setContent(content.copy(englishLive = !content.englishLive)) }
            }
            if (content.home == nl.boschtv.tv.core.Language.DUTCH) {
                Toggle(stringResource(R.string.flemish), "", content.belgian) { setContent(content.copy(belgian = !content.belgian)) }
            }

            Heading(stringResource(R.string.previews))
            val previews by c.prefs.previews.collectAsState(true to true)
            Toggle(stringResource(R.string.previews_on), "", previews.first) { scope.launch { c.prefs.setPreviews(!previews.first, previews.second) } }
            if (previews.first) Toggle(stringResource(R.string.previews_sound), "", previews.second) { scope.launch { c.prefs.setPreviews(true, !previews.second) } }

            Heading(stringResource(R.string.data))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.clear_history), { scope.launch { c.library.clearHistory(); note = Texts.get(R.string.history_cleared) } }, selected = true)
                Pill(if (confirmLogout) stringResource(R.string.sure_again) else stringResource(R.string.log_out), { if (confirmLogout) scope.launch { c.logout() } else confirmLogout = true }, selected = true, primary = confirmLogout)
            }
            note?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.AccentSoft, modifier = Modifier.padding(top = 8.dp)) }

            Heading(stringResource(R.string.app))
            Info(stringResource(R.string.version), nl.boschtv.tv.BuildConfig.VERSION_NAME)
            Text(stringResource(R.string.open_source),
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            // Required by TMDB's API terms.
            Text(stringResource(R.string.tmdb_credit),
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            Text(stringResource(R.string.avatar_credit), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            if (c.updater.enabled) {
                val up by c.updater.state.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val offer = up as? nl.boschtv.tv.system.Updater.State.Available
                    Pill(if (offer != null) stringResource(R.string.update_to, offer.versionName) else stringResource(R.string.check_updates),
                        { scope.launch { if (offer != null) c.updater.install() else c.updater.check(force = true) } }, selected = true, primary = offer != null)
                    val status = when (val s = up) {
                        is nl.boschtv.tv.system.Updater.State.Checking -> stringResource(R.string.checking)
                        is nl.boschtv.tv.system.Updater.State.UpToDate -> stringResource(R.string.up_to_date)
                        is nl.boschtv.tv.system.Updater.State.Downloading -> stringResource(R.string.downloading_pct, (s.progress * 100).toInt())
                        is nl.boschtv.tv.system.Updater.State.Failed -> s.message
                        else -> ""
                    }
                    Text(status, style = MaterialTheme.typography.titleMedium, color = Ink.Soft)
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted, modifier = Modifier.padding(top = 18.dp))

@Composable
private fun Info(label: String, value: String) = Row {
    Text(label, style = MaterialTheme.typography.titleMedium, color = Ink.Muted, modifier = Modifier.width(150.dp))
    Text(value, style = MaterialTheme.typography.titleMedium, color = Ink.Text)
}

@Composable
private fun Toggle(title: String, hint: String, on: Boolean, onClick: () -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Pill(stringResource(if (on) R.string.on else R.string.off), onClick, Modifier.width(84.dp), selected = true, primary = on)
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.Text)
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
    }
}
