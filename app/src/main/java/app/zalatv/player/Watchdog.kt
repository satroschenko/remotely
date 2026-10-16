package app.zalatv.player

enum class EngineState { IDLE, BUFFERING, READY, ENDED }

/** Detects a stuck player: buffering too long, or "playing" without media time moving. */
class Watchdog(private val limitMs: Long = 12_000) {
    private var bufferingSince: Long? = null
    private var lastMarker = Long.MIN_VALUE
    private var lastProgressAt = 0L

    fun reset(now: Long) {
        bufferingSince = null
        lastMarker = Long.MIN_VALUE
        lastProgressAt = now
    }

    /** @return true when the stream should be restarted. */
    fun tick(now: Long, state: EngineState, playWhenReady: Boolean, marker: Long): Boolean {
        if (!playWhenReady || state == EngineState.IDLE || state == EngineState.ENDED) {
            reset(now)
            return false
        }
        if (state == EngineState.BUFFERING) {
            val since = bufferingSince ?: now.also { bufferingSince = it }
            return now - since > limitMs
        }
        bufferingSince = null
        if (marker != lastMarker) {
            lastMarker = marker
            lastProgressAt = now
            return false
        }
        return now - lastProgressAt > limitMs
    }
}

/**
 * Detects a frozen picture while sound plays on: the player is READY and playing,
 * but the video decoder has rendered no new frame for [limitMs].
 * [frames] is null when there is no video output to watch (no video track, no surface).
 */
class VideoFreezeDetector(private val limitMs: Long = 6_000) {
    private var lastFrames: Long? = null
    private var lastFrameAt = 0L

    fun reset(now: Long) {
        lastFrames = null
        lastFrameAt = now
    }

    /** @return true when the video should be restarted. */
    fun tick(now: Long, playing: Boolean, frames: Long?): Boolean {
        if (!playing || frames == null) {
            reset(now)
            return false
        }
        if (frames != lastFrames) {
            lastFrames = frames
            lastFrameAt = now
            return false
        }
        if (now - lastFrameAt <= limitMs) return false
        reset(now)
        return true
    }
}
