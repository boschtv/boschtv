package nl.boschtv.tv.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import nl.boschtv.tv.core.FrameRateMeter
import nl.boschtv.tv.core.FrameRates

/**
 * Has the TV run at the video's own frame rate, so a 24 or 25 pictures-a-second film does not stutter
 * on a 60 Hz screen. From Android 12 on the TV's own setting decides (Display & Sound > Match content
 * frame rate): "Never" is left alone, "Seamless" only switches when the screen can do it without going
 * black, "Always" switches every time. Older TVs get the best fitting screen mode.
 */
class FrameRateMatch(private val activity: Activity, private val player: ExoPlayer) {
    /** The view the picture is drawn in; set once it exists. */
    var view: PlayerView? = null
    private val meter = FrameRateMeter()
    private val main = Handler(Looper.getMainLooper())
    private var applied = 0f

    init {
        // We ask the screen ourselves, with the viewer's setting; the player's own attempt would undo it.
        player.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        player.setVideoFrameMetadataListener { timeUs, _, _, _ -> meter.add(timeUs)?.let { fps -> main.post { apply(fps) } } }
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                meter.reset()
                // A film file usually says its rate up front: then the screen switches before the first picture.
                player.videoFormat?.frameRate?.takeIf { it > 0f }?.let(FrameRates::snap)?.let(::apply)
            }
        })
    }

    private fun apply(fps: Float) {
        if (fps == applied || activity.isFinishing || activity.isDestroyed) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val wish = activity.getSystemService(DisplayManager::class.java).matchContentFrameRateUserPreference
                if (wish == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER) return
                val surface = (view?.videoSurfaceView as? SurfaceView)?.holder?.surface?.takeIf { it.isValid } ?: return
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    if (wish == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) Surface.CHANGE_FRAME_RATE_ALWAYS else Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                @Suppress("DEPRECATION") val display = activity.windowManager.defaultDisplay ?: return
                val now = display.mode
                val modes = display.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                val rate = FrameRates.refreshFor(fps, now.refreshRate, modes.map { it.refreshRate }) ?: return
                val mode = modes.first { it.refreshRate == rate }
                activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
            }
            applied = fps
        }
    }
}
