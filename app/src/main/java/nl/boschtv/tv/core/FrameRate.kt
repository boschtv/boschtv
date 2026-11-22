package nl.boschtv.tv.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Frame rates of video, and which screen refresh rate shows each of them without judder. */
object FrameRates {
    private val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    /** The standard rate [fps] is meant to be (23.98 measured -> 23.976), or null when it is none of them. */
    fun snap(fps: Float): Float? = STANDARD.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) / it < 0.004f }

    /**
     * The refresh rate out of [available] that shows [fps] evenly: the rate itself or a whole multiple
     * (25 on 50 Hz). Null when none does, or when [current] already does: then nothing has to change.
     */
    fun refreshFor(fps: Float, current: Float, available: List<Float>): Float? {
        if (fps <= 0f) return null
        fun error(rate: Float): Float { val times = (rate / fps).roundToInt(); return if (times < 1) 1f else abs(rate / fps - times) / times }
        if (error(current) < 0.002f) return null
        // The closest fit; between equals the higher rate, which keeps the menus over the picture smooth.
        return available.filter { error(it) < 0.002f }.sortedWith(compareBy<Float> { error(it) }.thenByDescending { it }).firstOrNull()
    }
}

/**
 * Works out a stream's frame rate from the times of its pictures, for streams that do not say (live TV).
 * Feed it every picture's time; it answers once, when two dozen in a row were evenly spaced.
 */
class FrameRateMeter {
    private var last = -1L
    private var sum = 0L
    private var count = 0
    private var done = false

    @Synchronized fun reset() { last = -1; sum = 0; count = 0; done = false }

    @Synchronized fun add(timeUs: Long): Float? {
        if (done) return null
        val d = timeUs - last
        val first = last < 0
        last = timeUs
        if (first) return null
        // A jump (seek, another channel) or pictures arriving out of step: start counting again.
        val mean = if (count > 0) sum / count else d
        if (d <= 0 || d > 200_000 || abs(d - mean) > mean / 4) { sum = 0; count = 0; return null }
        sum += d; count++
        if (count < 24) return null
        val fps = FrameRates.snap(1_000_000f * count / sum)
        if (fps == null) { sum = 0; count = 0; return null }
        done = true
        return fps
    }
}
