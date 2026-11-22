package nl.boschtv.tv.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import nl.boschtv.tv.R
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * A trailer, full screen inside the app. Trailers live on YouTube, which hands out no video file our own
 * player could play, so this uses YouTube's official embedded player. The remote works as in the player:
 * OK pauses and plays, left and right skip 10 seconds, Back closes; it also closes when the trailer ends.
 *
 * Until the video really plays, the title's own backdrop and a spinner stand in front of it, so starting
 * a trailer looks like starting a film rather than a black screen with YouTube's loading chrome.
 */
class TrailerActivity : ComponentActivity() {
    private var web: WebView? = null
    private var playing by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getStringExtra(VIDEO)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return finish()
        val name = intent.getStringExtra(NAME)
        val image = intent.getStringExtra(IMAGE)
        val view = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false   // the remote's keys go to this screen, not into the page
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false   // starts by itself, like a film does
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "BoschTV")
            // The embedded player wants to know which site it is on; any https address will do.
            loadDataWithBaseURL("https://boschtv.github.io/", page(id), "text/html", "utf-8", null)
        }
        web = view
        setContent {
            LaunchedEffect(Unit) { delay(TIMEOUT_MS); if (!playing) failed() }
            Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
                AndroidView({ view }, Modifier.fillMaxSize())
                AnimatedVisibility(!playing, enter = fadeIn(tween(0)), exit = fadeOut(tween(400))) {
                    Box(Modifier.fillMaxSize().background(ComposeColor.Black)) {
                        if (image != null) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.55f)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ComposeColor.Transparent, ComposeColor.Black.copy(alpha = 0.85f)))))
                        Column(Modifier.align(Alignment.BottomStart).padding(48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Spinner(Modifier.size(36.dp))
                            if (name != null) Text(name, color = ComposeColor.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(getString(R.string.trailer), color = ComposeColor.White.copy(alpha = 0.7f), fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }

    /**
     * The player's iframe is in the page from the start, so YouTube starts loading at once; the script that
     * lets us steer it loads alongside instead of first. Connections to YouTube's servers are opened early.
     */
    private fun page(id: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <link rel="preconnect" href="https://www.youtube.com"><link rel="preconnect" href="https://i.ytimg.com">
        <link rel="preconnect" href="https://www.google.com">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
        </head><body>
        <iframe id="p" allow="autoplay; encrypted-media"
          src="https://www.youtube.com/embed/$id?autoplay=1&controls=0&rel=0&iv_load_policy=3&playsinline=1&fs=0&disablekb=1&enablejsapi=1&origin=https%3A%2F%2Fboschtv.github.io"></iframe>
        <script>
          var player, started = false;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('p', { events: {
              onReady: function (e) { e.target.playVideo(); },
              onStateChange: function (e) {
                if (e.data === 1 && !started) { started = true; BoschTV.playing(); }
                if (e.data === 0) BoschTV.ended();
              },
              onError: function (e) { BoschTV.failed(e.data); }
            } });
          }
          function toggle() { if (!player || !player.getPlayerState) return; if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo(); }
          function skip(s) { if (player && player.seekTo) player.seekTo(Math.max(0, player.getCurrentTime() + s), true); }
        </script>
        <script async src="https://www.youtube.com/iframe_api"></script>
        </body></html>
    """.trimIndent()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val w = web ?: return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> w.evaluateJavascript("toggle()", null)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> w.evaluateJavascript("skip(-10)", null)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> w.evaluateJavascript("skip(10)", null)
            KeyEvent.KEYCODE_BACK -> finish()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun failed() {
        if (isFinishing) return
        Toast.makeText(this, getString(R.string.trailer_failed), Toast.LENGTH_LONG).show()
        finish()
    }

    private inner class Bridge {
        // YouTube shows its own title for a moment when playback starts; the backdrop fades out just after.
        @JavascriptInterface fun playing() = runOnUiThread { web?.postDelayed({ playing = true }, 500) }
        @JavascriptInterface fun ended() = runOnUiThread { finish() }
        @JavascriptInterface fun failed(code: Int) = runOnUiThread { failed() }
    }

    // Leaving the screen stops the sound at once; nothing keeps playing in the background.
    override fun onPause() { super.onPause(); web?.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() {
        web?.run { loadUrl("about:blank"); destroy() }
        web = null
        super.onDestroy()
    }

    companion object {
        const val VIDEO = "video"
        const val NAME = "name"
        const val IMAGE = "image"
        private const val TIMEOUT_MS = 25_000L
    }
}
