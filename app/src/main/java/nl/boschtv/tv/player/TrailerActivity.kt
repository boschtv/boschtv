package nl.boschtv.tv.player

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import nl.boschtv.tv.R

/**
 * A trailer, full screen inside the app. Trailers live on YouTube, which hands out no video file our own
 * player could play, so this uses YouTube's official embedded player. The remote works as in the player:
 * OK pauses and plays, left and right skip 10 seconds, Back closes; it also closes when the trailer ends.
 */
class TrailerActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getStringExtra(VIDEO)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return finish()
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false   // the remote's keys go to this screen, not into the page
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false   // starts by itself, like a film does
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "BoschTV")
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        // The embedded player wants to know which site it is on; any https address will do.
        web.loadDataWithBaseURL("https://boschtv.github.io/", page(id), "text/html", "utf-8", null)
    }

    private fun page(id: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe,#p{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
        </head><body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          var player;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('p', { videoId: '$id',
              playerVars: { autoplay: 1, controls: 0, rel: 0, iv_load_policy: 3, playsinline: 1, fs: 0, origin: 'https://boschtv.github.io' },
              events: {
                onReady: function (e) { e.target.playVideo(); },
                onStateChange: function (e) { if (e.data === 0) BoschTV.ended(); },
                onError: function (e) { BoschTV.failed(e.data); }
              } });
          }
          function toggle() { if (!player) return; if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo(); }
          function skip(s) { if (player) player.seekTo(Math.max(0, player.getCurrentTime() + s), true); }
        </script></body></html>
    """.trimIndent()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> web.evaluateJavascript("toggle()", null)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> web.evaluateJavascript("skip(-10)", null)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> web.evaluateJavascript("skip(10)", null)
            KeyEvent.KEYCODE_BACK -> finish()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private inner class Bridge {
        @JavascriptInterface fun ended() = runOnUiThread { finish() }
        @JavascriptInterface fun failed(code: Int) = runOnUiThread {
            Toast.makeText(this@TrailerActivity, getString(R.string.trailer_failed), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Leaving the screen stops the sound at once; nothing keeps playing in the background.
    override fun onPause() { super.onPause(); if (::web.isInitialized) web.onPause() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        if (::web.isInitialized) { web.loadUrl("about:blank"); web.destroy() }
        super.onDestroy()
    }

    companion object { const val VIDEO = "video" }
}
