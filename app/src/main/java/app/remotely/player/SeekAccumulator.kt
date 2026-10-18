package app.remotely.player

/**
 * Collects D-pad seek presses into one pending jump, applied after [debounceMs] of silence.
 * Left/Right: 30 s, growing to 1 min and 5 min while held; Up/Down ("big"): 5 min.
 */
class SeekAccumulator(private val debounceMs: Long = 800, private val repeatThrottleMs: Long = 250) {
    var pendingMs: Long = 0
        private set
    private var lastInput = 0L
    private var holdStart = 0L
    private var lastStep = 0L
    var active: Boolean = false
        private set

    fun press(direction: Int, big: Boolean, isRepeat: Boolean, now: Long) {
        if (!isRepeat) holdStart = now
        else if (now - lastStep < repeatThrottleMs) {
            lastInput = now
            return
        }
        val held = now - holdStart
        val step = when {
            big -> 300_000L
            !isRepeat || held < 2_000 -> 30_000L
            held < 4_000 -> 60_000L
            else -> 300_000L
        }
        pendingMs += direction * step
        lastInput = now
        lastStep = now
        active = true
    }

    /** Returns the accumulated delta once input settled, else null. */
    fun takeIfSettled(now: Long): Long? {
        if (!active || now - lastInput < debounceMs) return null
        val d = pendingMs
        cancel()
        return d
    }

    fun cancel() {
        pendingMs = 0
        active = false
    }
}
