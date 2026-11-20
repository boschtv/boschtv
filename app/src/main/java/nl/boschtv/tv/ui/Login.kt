package nl.boschtv.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.boschtv.tv.R
import nl.boschtv.tv.data.SyncState
import nl.boschtv.tv.data.xtream.Source
import nl.boschtv.tv.setup.SetupServer

/** First run: scan the QR code and paste the line on your phone, or type it here. */
@Composable
fun LoginScreen() {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    fun login(src: Source) {
        if (busy) return
        if (!src.isConfigured) { status = "Vul server, gebruikersnaam en wachtwoord in."; return }
        busy = true; status = "Inloggen…"
        scope.launch {
            val result = c.sync.sync(src)
            if (result.isSuccess) { c.prefs.saveSource(src); c.refreshIfStale() }
            else status = "Dat lukte niet: ${result.exceptionOrNull()?.message ?: "onbekende fout"}"
            busy = false
        }
    }

    val server = remember { SetupServer { src -> host = src.host; user = src.username; pass = src.password; login(src) } }
    DisposableEffect(Unit) { server.start(); onDispose { server.stop() } }
    LaunchedEffect(Unit) { delay(200); runCatching { first.requestFocus() } }
    val sync by c.sync.state.collectAsState()

    Row(Modifier.fillMaxSize().background(Ink.Bg).padding(horizontal = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(72.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), "BoschTV", Modifier.size(52.dp))
                Text("  boschtv", style = MaterialTheme.typography.displayMedium, color = Ink.Text)
            }
            Text("Log in met je IPTV-abonnement", style = MaterialTheme.typography.titleLarge, color = Ink.Soft, modifier = Modifier.padding(bottom = 8.dp))
            Field(host, { host = it }, "Server, bijvoorbeeld http://voorbeeld.nl", type = KeyboardType.Uri)
            Field(user, { user = it }, "Gebruikersnaam")
            Field(pass, { pass = it }, "Wachtwoord", last = true)
            Pill(if (busy) "Bezig…" else "Inloggen", { login(Source.of(host, user, pass)) }, Modifier.padding(top = 8.dp).focusRequester(first), primary = true, height = 48.dp)
            val running = sync as? SyncState.Running
            if (busy && running != null) {
                Text("${running.message}  ${(running.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = Ink.AccentSoft)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Ink.Line)) {
                    Box(Modifier.fillMaxWidth(running.progress.coerceIn(0.02f, 1f)).height(6.dp).background(Ink.Accent))
                }
                Text("De eerste keer duurt dit een minuut of twee. Daarna gaat bijwerken vanzelf.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            } else status?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Ink.AccentSoft) }
        }
        val address = remember { server.address }
        if (address != null) Column(Modifier.width(300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val qr = remember(address) { qrBitmap(address) }
            Image(qr.asImageBitmap(), "QR-code naar $address", Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp), filterQuality = FilterQuality.None)
            Text("Makkelijker: scan met je telefoon", style = MaterialTheme.typography.titleLarge, color = Ink.Text)
            Text("Plak daar de link van je aanbieder. Je telefoon moet op hetzelfde wifi-netwerk zitten.\n$address",
                style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, type: KeyboardType = KeyboardType.Text, last: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val fm = LocalFocusManager.current
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = Ink.Text),
        cursorBrush = SolidColor(Ink.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (last) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
        modifier = modifier.fillMaxWidth().height(54.dp)
            .onFocusChanged { focused = it.isFocused }
            // Up/down must move between fields instead of getting stuck inside the text.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) { Key.DirectionDown -> { fm.moveFocus(FocusDirection.Down); true }; Key.DirectionUp -> { fm.moveFocus(FocusDirection.Up); true }; else -> false }
            }
            .background(Ink.Surface, shape).border(2.dp, if (focused) Ink.Text else Ink.Line, shape).padding(horizontal = 18.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.titleMedium, color = Ink.Muted)
                inner()
            }
        },
    )
}

private fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    return Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565).apply {
        for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF0A0A24.toInt() else 0xFFFFFFFF.toInt())
    }
}
