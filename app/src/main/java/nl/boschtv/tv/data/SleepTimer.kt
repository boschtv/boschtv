package nl.boschtv.tv.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Stop playing in half an hour." One timer for the whole app: it keeps counting from one episode into
 * the next and from a film into live TV. Each press on its button gives the next longer time; after
 * the longest it is off again.
 */
class SleepTimer(private val now: () -> Long = System::currentTimeMillis) {
    /** When playback stops (clock time), or null when the timer is off. */
    val until = MutableStateFlow<Long?>(null)

    /** Whole minutes left, rounded up; 0 when off. */
    fun minutesLeft(): Int = until.value?.let { ((it - now() + 59_999) / 60_000).toInt().coerceAtLeast(0) } ?: 0

    fun cycle() {
        val left = minutesLeft()
        val next = STEPS.firstOrNull { it > left }
        until.value = next?.let { now() + it * 60_000L }
    }

    /** True once, when the time is up: the timer is off again afterwards. */
    fun due(): Boolean {
        val at = until.value ?: return false
        if (now() < at) return false
        until.value = null
        return true
    }

    companion object { val STEPS = listOf(15, 30, 60, 90) }
}
