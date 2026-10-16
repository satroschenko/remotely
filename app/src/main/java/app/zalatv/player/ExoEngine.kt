package app.zalatv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient

/** Media3 ExoPlayer behind [MediaEngine]. Must be used on the main thread. */
@UnstableApi
class ExoEngine(context: Context, httpClient: OkHttpClient) : MediaEngine {
    private val trackSelector = DefaultTrackSelector(context)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(DashMediaSource.Factory(OkHttpDataSource.Factory(httpClient)))
        .build()
    private var listener: MediaEngine.Listener? = null
    private val window = Timeline.Window()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                listener?.onStateChanged(state)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(
                    behindLiveWindow = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                    message = "${error.errorCodeName}: ${error.message}",
                )
            }
        })
    }

    override fun setListener(listener: MediaEngine.Listener?) {
        this.listener = listener
    }

    override fun open(url: String, isLive: Boolean, startPositionMs: Long) {
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build()
        if (isLive) player.setMediaItem(item) else player.setMediaItem(item, startPositionMs)
        player.prepare()
        player.playWhenReady = true
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    override fun setPaused(paused: Boolean) {
        player.playWhenReady = !paused
    }

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    override fun seekToLive() {
        player.seekToDefaultPosition()
        player.prepare()
    }

    private var maxQuality = false
    private var preview = false
    private var videoOff = false
    private val main = Handler(Looper.getMainLooper())

    /** Switching the video track off and on releases the decoder; audio is not interrupted. */
    override fun restartVideo() {
        if (videoOff) return
        videoOff = true
        applyTrackLimits()
        main.postDelayed({
            videoOff = false
            applyTrackLimits()
        }, 300)
    }

    override fun setMaxQuality(max: Boolean) {
        maxQuality = max
        applyTrackLimits()
    }

    /** Small preview window: cap decoding at 1024x576 so flipping channels stays light. */
    fun setPreview(enabled: Boolean) {
        if (preview == enabled) return
        preview = enabled
        applyTrackLimits()
    }

    private fun applyTrackLimits() {
        val b = trackSelector.buildUponParameters()
        if (preview) b.setMaxVideoSize(1024, 576).setForceHighestSupportedBitrate(false)
        else b.clearVideoSizeConstraints().setForceHighestSupportedBitrate(maxQuality)
        b.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoOff)
        trackSelector.parameters = b.build()
    }

    override val state: EngineState
        get() = when (player.playbackState) {
            Player.STATE_BUFFERING -> EngineState.BUFFERING
            Player.STATE_READY -> EngineState.READY
            Player.STATE_ENDED -> EngineState.ENDED
            else -> EngineState.IDLE
        }
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val positionMs: Long get() = player.currentPosition
    override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
    override val progressMarker: Long
        get() {
            val tl = player.currentTimeline
            if (!tl.isEmpty) {
                tl.getWindow(player.currentMediaItemIndex, window)
                if (window.windowStartTimeMs != C.TIME_UNSET) return window.windowStartTimeMs + player.currentPosition
            }
            return player.currentPosition
        }
    override val renderedFrames: Long?
        get() {
            if (videoOff || player.surfaceSize.width == 0) return null
            val counters = player.videoDecoderCounters ?: return null
            counters.ensureUpdated()
            return counters.renderedOutputBufferCount.toLong()
        }
}
