package app.remotely.player

import app.remotely.data.api.Programme

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

    /** The programme after [p]; the guide's neighbours sometimes overlap by a little. */
    fun next(p: Programme): Programme? = list.firstOrNull { it.startMs > p.startMs && it.startMs >= p.endMs - OVERLAP_MS && it.startMs < nowMs }

    /** The programme before [p], while the archive still keeps its start. */
    fun previous(p: Programme): Programme? = list.lastOrNull { it.startMs < p.startMs && it.endMs <= p.startMs && it.startMs >= earliestMs }

    /**
     * A recording opened while [p] was on air holds only what was there at [coveredUntilMs] (the moment it was
     * asked for). True when playing reached that end: more of the programme may exist by now.
     */
    fun ranOut(p: Programme, positionMs: Long, coveredUntilMs: Long): Boolean {
        if (coveredUntilMs >= p.endMs) return false
        if (p.startMs + positionMs >= coveredUntilMs - SNAPSHOT_EDGE_MS) return true
        // It ended well before the moment it was asked for: the server's edge lagged, or the recording broke off.
        // Asked for a while ago, asking again may bring more; asked just now, it would end in the same place.
        return nowMs - coveredUntilMs >= SNAPSHOT_FRESH_MS
    }

    /** What to do when a recording asked for up to [coveredUntilMs] ended at [positionMs]. */
    fun onEnded(p: Programme, positionMs: Long, coveredUntilMs: Long): TimelinePoint {
        val reached = p.startMs + positionMs
        if (ranOut(p, positionMs, coveredUntilMs)) {
            // Ask again from here (whether or not the programme is still on air); caught up with now: live.
            return if (nowMs - reached < liveMarginMs) TimelinePoint.Live else TimelinePoint.InProgramme(p, positionMs)
        }
        // The recording broke off before what was asked for: on air there is nothing further to wait for.
        if (p.endMs > nowMs) return TimelinePoint.Live
        return next(p)?.let { TimelinePoint.InProgramme(it, 0) } ?: TimelinePoint.Live
    }

    private companion object {
        /** The last pieces of a recording asked for on air can end this much before the moment it was asked. */
        const val SNAPSHOT_EDGE_MS = 60_000L
        /** Neighbours in the guide may overlap this much and still follow each other. */
        const val OVERLAP_MS = 60_000L
        /** A recording asked for less than this long ago that still ends early is broken, not behind. */
        const val SNAPSHOT_FRESH_MS = 2 * 60_000L
    }
}
