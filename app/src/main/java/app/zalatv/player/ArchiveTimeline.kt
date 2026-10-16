package app.zalatv.player

import app.zalatv.data.api.Programme

sealed interface TimelinePoint {
    data class InProgramme(val programme: Programme, val offsetMs: Long) : TimelinePoint
    data object Live : TimelinePoint
}

/**
 * Continuous archive "tape" of one channel: maps wall-clock time to (programme, offset) so seeking
 * crosses programme boundaries; clamps to the archive window and turns into live near "now".
 */
class ArchiveTimeline(
    programmes: List<Programme>,
    private val nowMs: Long,
    private val depthMs: Long,
    private val liveMarginMs: Long = 30_000,
) {
    private val list = programmes.sortedBy { it.startMs }

    val earliestMs: Long get() = nowMs - depthMs

    fun resolve(targetMs: Long): TimelinePoint {
        val t = maxOf(targetMs, earliestMs)
        if (t >= nowMs - liveMarginMs) return TimelinePoint.Live
        list.firstOrNull { t >= it.startMs && t < it.endMs }?.let {
            return TimelinePoint.InProgramme(it, maxOf(0, t - it.startMs))
        }
        // In a gap: jump to the start of the following programme that already began.
        list.firstOrNull { it.startMs > t && it.startMs < nowMs }?.let { return TimelinePoint.InProgramme(it, 0) }
        return TimelinePoint.Live
    }

    fun next(p: Programme): Programme? = list.firstOrNull { it.startMs >= p.endMs && it.startMs < nowMs }

    /** What to do when a recording's manifest ended. */
    fun onEnded(p: Programme, positionMs: Long): TimelinePoint {
        val reached = p.startMs + positionMs
        if (p.endMs > nowMs) {
            // Start-over of the programme on air: manifest was cut at request time.
            return if (nowMs - reached < liveMarginMs) TimelinePoint.Live else TimelinePoint.InProgramme(p, positionMs)
        }
        return next(p)?.let { TimelinePoint.InProgramme(it, 0) } ?: TimelinePoint.Live
    }
}
