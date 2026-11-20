@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package nl.boschtv.tv.ui

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
    val date = remember { SimpleDateFormat("d MMMM yyyy", Locale("nl")) }
    val time = remember { SimpleDateFormat("d MMM HH:mm", Locale("nl")) }
    LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus() } }

    fun setContent(new: ContentPrefs) = scope.launch { c.prefs.saveContent(new); note = "Aangepast. De catalogus wordt opnieuw opgebouwd."; c.refreshIfStale(force = true) }

    // Two columns instead of one long list: everything fits on one screen, nothing scrolls under the top bar.
    Row(Modifier.fillMaxSize().padding(start = EDGE, end = EDGE, top = 88.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Instellingen", style = MaterialTheme.typography.headlineLarge, color = Ink.Text)

            Heading("Abonnement")
            Info("Server", source.host.removePrefix("http://").removePrefix("https://"))
            Info("Gebruiker", source.username)
            Info("Geldig tot", account.expiresAt?.let { date.format(Date(it)) + if (account.trial) " (proef)" else "" } ?: "onbekend")
            Info("Tegelijk kijken", "${account.maxConnections} scherm" + if (account.maxConnections == 1) "" else "en")
            account.problem?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.AccentSoft) }

            Heading("Catalogus")
            val status = when (val st = sync) {
                is SyncState.Running -> "${st.message} ${(st.progress * 100).toInt()}%"
                is SyncState.Failed -> "Mislukt: ${st.message}"
                SyncState.Idle -> if (lastSync > 0) "Laatst bijgewerkt ${time.format(Date(lastSync))}" else "Nog niet bijgewerkt"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill("Nu bijwerken", { c.refreshIfStale(force = true) }, Modifier.focusRequester(focus), selected = true)
                Text(status, style = MaterialTheme.typography.titleMedium, color = Ink.Soft)
            }
            Text("Bijwerken gaat vanzelf, een paar keer per dag.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            val report by c.prefs.syncReport.collectAsState(initial = null)
            report?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Soft, maxLines = 2) }

            Heading("Google TV-startscherm")
            Info("Verder kijken", homeRow)
            Text("De app biedt deze titels aan voor de rij Verder kijken. Of Google TV ze toont, bepaalt Google.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Column(Modifier.weight(1f).padding(top = 44.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("Wat wil je zien")
            // Your language: kept, with English as fallback; every other language is left out.
            var choosing by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill(content.home.label, { choosing = !choosing }, selected = true, icon = if (choosing) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore)
                Column {
                    Text("Taal van films, series en zenders", style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                    Text("Andere talen worden weggelaten.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                }
            }
            if (choosing) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in nl.boschtv.tv.core.Language.choices) {
                    Pill(l.label, { choosing = false; if (l != content.home) setContent(content.copy(home = l)) }, selected = l == content.home, primary = l == content.home)
                }
            }
            if (content.home != nl.boschtv.tv.core.Language.ENGLISH) {
                Toggle("Engelstalige films en series als aanvulling", "Alleen titels die er niet in het ${content.home.label.substringBefore(' ')} zijn.", content.english) { setContent(content.copy(english = !content.english)) }
                Toggle("Engelse tv-zenders (UK en VS)", "Voegt een paar duizend zenders toe aan live tv.", content.englishLive) { setContent(content.copy(englishLive = !content.englishLive)) }
            }
            if (content.home == nl.boschtv.tv.core.Language.DUTCH) {
                Toggle("Vlaamse films, series en zenders", "", content.belgian) { setContent(content.copy(belgian = !content.belgian)) }
            }

            Heading("Gegevens")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Kijkgeschiedenis wissen", { scope.launch { c.library.clearHistory(); note = "Kijkgeschiedenis gewist." } }, selected = true)
                Pill(if (confirmLogout) "Zeker weten? Druk nog eens" else "Uitloggen", { if (confirmLogout) scope.launch { c.prefs.logout() } else confirmLogout = true }, selected = true, primary = confirmLogout)
            }
            note?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.AccentSoft, modifier = Modifier.padding(top = 8.dp)) }

            Heading("App")
            Info("Versie", nl.boschtv.tv.BuildConfig.VERSION_NAME)
            Text("Open source (MIT) · github.com/florisbosch/boschtv · licenties van gebruikte software staan daar.",
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            // Required by TMDB's API terms.
            Text("Gebruikt de TMDB API, maar is niet goedgekeurd of gecertificeerd door TMDB. Gegevens over streamingdiensten: JustWatch.",
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            if (c.updater.enabled) {
                val up by c.updater.state.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val offer = up as? nl.boschtv.tv.system.Updater.State.Available
                    Pill(if (offer != null) "Bijwerken naar ${offer.versionName}" else "Controleren op updates",
                        { scope.launch { if (offer != null) c.updater.install() else c.updater.check(force = true) } }, selected = true, primary = offer != null)
                    val status = when (val s = up) {
                        is nl.boschtv.tv.system.Updater.State.Checking -> "Controleren…"
                        is nl.boschtv.tv.system.Updater.State.UpToDate -> "Je hebt de nieuwste versie"
                        is nl.boschtv.tv.system.Updater.State.Downloading -> "Downloaden… ${(s.progress * 100).toInt()}%"
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
    Pill(if (on) "Aan" else "Uit", onClick, Modifier.width(84.dp), selected = true, primary = on)
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink.Text)
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
    }
}
