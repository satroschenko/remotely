package app.remotely.player

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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.OkHttpClient

/** Media3 ExoPlayer behind [MediaEngine]. Must be used on the main thread. */
@UnstableApi
class ExoEngine(context: Context, httpClient: OkHttpClient) : MediaEngine {
    private val trackSelector = DefaultTrackSelector(context)
    private var isLive = true

    /**
     * A recording the server answered 4xx for will not appear if the same server is asked again:
     * report it at once, the controller takes a new link (another server) or skips the hole.
     */
    private val loadErrors = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = info.exception
            if (!isLive && e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
            return super.getRetryDelayMsFor(info)
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        // The picture starts after 1 s of video instead of the default 2.5 s: zapping feels like a TV.
        // After a stall the default (5 s) stays, so a slow connection does not stutter over and over.
        .setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                START_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            ).build(),
        )
        .setMediaSourceFactory(
            DashMediaSource.Factory(OkHttpDataSource.Factory(httpClient)).setLoadErrorHandlingPolicy(loadErrors),
        )
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
                    notFound = notFound(error),
                )
            }
        })
    }

    private fun notFound(error: PlaybackException): NotFound? {
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull() ?: return null
        if (http.responseCode != 404 && http.responseCode != 410) return null
        return if (http.dataSpec.uri.path.orEmpty().endsWith(".mpd")) NotFound.MANIFEST else NotFound.SEGMENT
    }

    override fun setListener(listener: MediaEngine.Listener?) {
        this.listener = listener
    }

    override fun open(url: String, isLive: Boolean, startPositionMs: Long) {
        this.isLive = isLive
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

    private var maxHeight = 0
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

    override fun setMaxHeight(height: Int) {
        maxHeight = height
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
        when {
            preview -> b.setMaxVideoSize(1024, minOf(576, maxHeight.takeIf { it > 0 } ?: 576)).setForceHighestSupportedBitrate(false)
            maxHeight > 0 -> b.setMaxVideoSize(Int.MAX_VALUE, maxHeight).setForceHighestSupportedBitrate(true)
            else -> b.clearVideoSizeConstraints().setForceHighestSupportedBitrate(false)
        }
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

/** Video buffered before the picture starts. */
private const val START_BUFFER_MS = 1_000
