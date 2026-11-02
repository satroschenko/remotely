package app.remotely.player

enum class SleepAction { NONE, WARN, STOP }

/**
 * When to stop the picture so the device can fall asleep by itself: an app cannot switch a TV off.
 * Two reasons: the timer the viewer set (Настройки → «Таймер сна», lasts until it fires or the app restarts)
 * and a long broadcast break on the channel (Настройки → «В перерыв вещания»).
 */
class SleepTimer {
    /** The chosen time, 0 = off. */
    var minutes = 0
        private set
    private var timerEndMs: Long? = null
    private var breakEndMs: Long? = null
    private var warned = false

    /** The next choice, counted from now. */
    fun cycle(nowMs: Long) {
        minutes = CHOICES[(CHOICES.indexOf(minutes) + 1) % CHOICES.size]
        timerEndMs = (nowMs + minutes * MINUTE_MS).takeIf { minutes > 0 }
        warned = false
    }

    /** «Выкл» or the time left, rounded up. */
    fun label(nowMs: Long): String =
        timerEndMs?.let { "${((it - nowMs + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1)} мин" } ?: "Выкл"

    /** Once a second. [inBreak]: the channel is in a broadcast break; [breakMinutes]: the setting, 0 = off. */
    fun tick(nowMs: Long, inBreak: Boolean, breakMinutes: Int): SleepAction {
        breakEndMs = when {
            !inBreak || breakMinutes <= 0 -> null
            else -> breakEndMs ?: (nowMs + breakMinutes * MINUTE_MS)
        }
        val deadline = listOfNotNull(timerEndMs, breakEndMs).minOrNull()
        if (deadline == null) {
            warned = false
            return SleepAction.NONE
        }
        if (nowMs >= deadline) {
            if (timerEndMs?.let { nowMs >= it } == true) {
                timerEndMs = null
                minutes = 0
            }
            if (breakEndMs?.let { nowMs >= it } == true) breakEndMs = null
            warned = false
            return SleepAction.STOP
        }
        if (nowMs >= deadline - WARNING_MS && !warned) {
            warned = true
            return SleepAction.WARN
        }
        return SleepAction.NONE
    }

    /** «Смотреть ещё» in the warning: whatever was about to fire gets its time again. */
    fun extend(nowMs: Long, breakMinutes: Int) {
        timerEndMs?.let { if (it - nowMs <= WARNING_MS) timerEndMs = nowMs + minutes * MINUTE_MS }
        breakEndMs?.let { if (it - nowMs <= WARNING_MS) breakEndMs = nowMs + breakMinutes * MINUTE_MS }
        warned = false
    }

    companion object {
        val CHOICES = listOf(0, 15, 30, 45, 60, 75, 90, 105, 120)
        val BREAK_CHOICES = listOf(0, 10, 20, 30, 40, 50, 60)
        private const val MINUTE_MS = 60_000L
        private const val WARNING_MS = 60_000L
    }
}
