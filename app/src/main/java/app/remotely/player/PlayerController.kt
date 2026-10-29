package app.remotely.player

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.core.ZalaTime
import app.remotely.core.RetryDecision
import app.remotely.core.RetryPolicy
import app.remotely.data.api.ArchiveUnavailableException
import app.remotely.data.api.BadCredentialsException
import app.remotely.data.api.Channel
import app.remotely.data.api.Programme
import app.remotely.data.repo.StreamSource
import app.remotely.data.session.NotLoggedInException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Thin wrapper over the real player (ExoPlayer) so reconnect logic is testable. */
interface MediaEngine {
    interface Listener {
        fun onStateChanged(state: EngineState)
        fun onError(behindLiveWindow: Boolean, message: String, notFound: NotFound? = null)
    }

    fun setListener(listener: Listener?)
    fun open(url: String, isLive: Boolean, startPositionMs: Long)
    fun stop()
    fun setPaused(paused: Boolean)
    fun seekTo(positionMs: Long)
    fun seekToLive()
    /** 0 = adaptive; otherwise the best track no taller than [height]. */
    fun setMaxHeight(height: Int)
    val state: EngineState
    val playWhenReady: Boolean
    val positionMs: Long
    val durationMs: Long
    /** Monotonic media time (live: window start + position) used by the watchdog. */
    val progressMarker: Long
    /** Video frames rendered so far (any change = picture alive); null when no video is shown. */
    val renderedFrames: Long?
    /** Rebuild the video decoder while audio keeps playing. */
    fun restartVideo()
}

sealed interface PlaybackTarget {
    val channel: Channel

    data class Live(override val channel: Channel) : PlaybackTarget
    data class Archive(override val channel: Channel, val programme: Programme, val startOffsetMs: Long) : PlaybackTarget
}

/** What the server answered 404 for: the whole recording (manifest) or one piece of it. */
enum class NotFound { MANIFEST, SEGMENT }

/** [BLACKOUT]: Zala's break in the channel's broadcast, the stream is not shown until [PlayerState.blackoutUntilMs]. */
enum class Phase { IDLE, CONNECTING, PLAYING, RECONNECTING, NO_NETWORK, NEEDS_USER_RETRY, BAD_CREDENTIALS, NOT_LOGGED_IN, BLACKOUT }

data class PlayerState(
    val target: PlaybackTarget? = null,
    val phase: Phase = Phase.IDLE,
    val attempt: Int = 0,
    val paused: Boolean = false,
    val blackoutUntilMs: Long? = null,
)

sealed interface PlayerEvent {
    data class RecordingEnded(val target: PlaybackTarget.Archive, val positionMs: Long) : PlayerEvent
    data class RecordingUnavailable(val target: PlaybackTarget.Archive) : PlayerEvent
}

class PlayerController(
    private val engine: MediaEngine,
    private val streams: StreamSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val log: EventLog,
    /** When the channel's broadcast break ends, or null when it is on air. */
    private val blackoutUntil: (Channel) -> Long? = { null },
    private val policy: RetryPolicy = RetryPolicy(listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)),
    private val watchdog: Watchdog = Watchdog(),
    private val freeze: VideoFreezeDetector = VideoFreezeDetector(),
) : MediaEngine.Listener {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerEvent> = _events

    private var openJob: Job? = null
    private var tickJob: Job? = null
    private var networkUp = true
    /** Last known archive position, used to resume after reconnects. */
    private var archivePositionMs = 0L
    /** The engine already plays the current target (not the previous one while a new link is fetched). */
    private var engineOnTarget = false
    /** 404s in a row for the current recording, and where it happened. */
    private var notFoundTries = 0
    private var notFoundAtMs = 0L
    /** Skipping a hole in the recording: next 404 skips again without asking twice. */
    private var skippingHole = false
    /** When the video decoder was last restarted because of a frozen picture. */
    private var freezeFixAt: Long? = null

    init {
        engine.setListener(this)
    }

    /** Position in the recording; while a new link is fetched, where it is going to start. */
    val positionMs: Long get() = if (engineOnTarget) engine.positionMs else archivePositionMs
    val durationMs: Long get() = engine.durationMs

    fun play(target: PlaybackTarget) {
        policy.onSuccess()
        archivePositionMs = (target as? PlaybackTarget.Archive)?.startOffsetMs ?: 0
        resetNotFound()
        _state.value = PlayerState(target = target, phase = Phase.CONNECTING)
        open(target, Phase.CONNECTING)
    }

    private fun open(target: PlaybackTarget, phase: Phase) {
        openJob?.cancel()
        engineOnTarget = false
        if (target is PlaybackTarget.Live) blackoutUntil(target.channel)?.let { return blackout(target, it) }
        _state.update { it.copy(phase = phase, paused = false) }
        openJob = scope.launch {
            try {
                val url = when (target) {
                    is PlaybackTarget.Live -> streams.liveUrl(target.channel)
                    is PlaybackTarget.Archive -> streams.archiveUrl(target.channel, target.programme)
                }
                if (target is PlaybackTarget.Archive) log.add("Открываю запись «${target.programme.title}» с ${mmss(archivePositionMs)}")
                watchdog.reset(clock.nowMs())
                freeze.reset(clock.nowMs())
                engine.open(url, target is PlaybackTarget.Live, if (target is PlaybackTarget.Archive) archivePositionMs else 0)
                engineOnTarget = true
                ensureTicking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** A break started while the channel is on, or the schedule came after it was opened: stop showing it. */
    fun recheckBlackout(): Boolean {
        val s = _state.value
        val t = s.target as? PlaybackTarget.Live ?: return false
        if (s.phase == Phase.BLACKOUT || s.phase == Phase.IDLE || blackoutUntil(t.channel) == null) return false
        open(t, s.phase)
        return true
    }

    /** No stream during the break; it opens by itself when the break is over. */
    private fun blackout(target: PlaybackTarget.Live, untilMs: Long) {
        engine.stop()
        log.add("«${target.channel.name}»: перерыв в вещании до ${ZalaTime.hhmm(untilMs)}")
        _state.update { it.copy(phase = Phase.BLACKOUT, paused = false, blackoutUntilMs = untilMs) }
        openJob = scope.launch {
            delay((untilMs - clock.nowMs()).coerceAtLeast(0) + BLACKOUT_END_MARGIN_MS)
            open(target, Phase.CONNECTING)
        }
    }

    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    private fun tick() {
        val s = _state.value
        if (recheckBlackout()) return
        if (s.target is PlaybackTarget.Archive && engineOnTarget && engine.state == EngineState.READY) {
            archivePositionMs = engine.positionMs
            if (archivePositionMs > notFoundAtMs + NOT_FOUND_CLEAR_MS) resetNotFound()
        }
        if (s.phase != Phase.PLAYING && s.phase != Phase.CONNECTING) return
        if (s.paused) return
        if (watchdog.tick(clock.nowMs(), engine.state, engine.playWhenReady, engine.progressMarker)) {
            log.add("Плеер завис — перезапуск")
            fail(IllegalStateException("stalled"))
            return
        }
        checkFrozenPicture(s.target ?: return)
    }

    /** Sound plays but the picture stands still: first rebuild the video decoder, then the whole stream. */
    private fun checkFrozenPicture(target: PlaybackTarget) {
        val now = clock.nowMs()
        val playing = engine.state == EngineState.READY && engine.playWhenReady
        if (!freeze.tick(now, playing, engine.renderedFrames)) return
        val lastFix = freezeFixAt
        if (lastFix != null && now - lastFix < FREEZE_REPEAT_MS) {
            log.add("Картинка снова застыла — перезапуск потока")
            freezeFixAt = null
            engine.stop()
            open(target, Phase.RECONNECTING)
        } else {
            log.add("Картинка застыла (звук идёт) — перезапуск видео")
            freezeFixAt = now
            engine.restartVideo()
        }
    }

    private fun fail(e: Throwable) {
        val target = _state.value.target ?: return
        when (e) {
            is BadCredentialsException -> return setPhase(Phase.BAD_CREDENTIALS)
            is NotLoggedInException -> return setPhase(Phase.NOT_LOGGED_IN)
            is ArchiveUnavailableException -> if (target is PlaybackTarget.Archive) {
                log.add("Запись недоступна (${e.code})")
                _events.tryEmit(PlayerEvent.RecordingUnavailable(target))
                return
            }
        }
        engine.stop()
        if (!networkUp) {
            log.add("Нет сети: ${e.javaClass.simpleName}")
            return setPhase(Phase.NO_NETWORK)
        }
        when (val d = policy.onFailure()) {
            is RetryDecision.RetryAfter -> {
                log.add("Ошибка (${e.javaClass.simpleName}: ${e.message?.take(60)}), попытка ${d.attempt} через ${d.delayMs / 1000} с")
                _state.update { it.copy(phase = Phase.RECONNECTING, attempt = d.attempt) }
                openJob?.cancel()
                openJob = scope.launch {
                    delay(d.delayMs)
                    open(target, Phase.RECONNECTING)
                }
            }
            RetryDecision.GiveUp -> {
                log.add("Не удаётся подключиться — ждём «Повторить»")
                setPhase(Phase.NEEDS_USER_RETRY)
            }
        }
    }

    private fun resetNotFound() {
        notFoundTries = 0
        notFoundAtMs = 0
        skippingHole = false
    }

    /**
     * Zala keeps a recording on several servers and some of them miss it or a piece of it:
     * ask again a few times (another server answers), then skip what no server has.
     */
    private fun recordingNotFound(target: PlaybackTarget.Archive, what: NotFound) {
        if (engineOnTarget) archivePositionMs = maxOf(archivePositionMs, engine.positionMs)
        notFoundAtMs = archivePositionMs
        engine.stop()
        openJob?.cancel()
        if ((what == NotFound.MANIFEST || !skippingHole) && ++notFoundTries <= NOT_FOUND_RETRIES) {
            log.add("Сервер не нашёл запись — спрашиваю ещё раз ($notFoundTries)")
            if (_state.value.phase != Phase.CONNECTING) setPhase(Phase.RECONNECTING)
            openJob = scope.launch {
                delay(NOT_FOUND_DELAY_MS * notFoundTries)
                open(target, _state.value.phase)
            }
            return
        }
        notFoundTries = 0
        if (what == NotFound.MANIFEST) {
            log.add("Записи нет ни на одном сервере")
            _events.tryEmit(PlayerEvent.RecordingUnavailable(target))
            return
        }
        skippingHole = true
        archivePositionMs += HOLE_SKIP_MS
        log.add("Дыра в записи — пропускаю до ${mmss(archivePositionMs)}")
        if (archivePositionMs >= target.programme.endMs - target.programme.startMs) {
            _events.tryEmit(PlayerEvent.RecordingEnded(target, archivePositionMs))
        } else {
            open(target, Phase.RECONNECTING)
        }
    }

    private companion object {
        /** A second freeze this soon after a decoder restart means the restart did not help. */
        const val FREEZE_REPEAT_MS = 60_000L
        const val NOT_FOUND_RETRIES = 3
        const val NOT_FOUND_DELAY_MS = 300L
        /** Step over a hole in a recording; segments are 6-10 s long. */
        const val HOLE_SKIP_MS = 20_000L
        /** Played this far past the last 404: the trouble is over. */
        const val NOT_FOUND_CLEAR_MS = 5_000L
        /** After the break, a moment more so the schedule's clock surely agrees. */
        const val BLACKOUT_END_MARGIN_MS = 1_000L

        fun mmss(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
    }

    private fun setPhase(p: Phase) = _state.update { it.copy(phase = p) }

    /** "Повторить" button. */
    fun retry() {
        val t = _state.value.target ?: return
        policy.onSuccess()
        open(t, Phase.CONNECTING)
    }

    fun onNetworkChanged(available: Boolean) {
        val wasUp = networkUp
        networkUp = available
        if (!available || wasUp) return
        val s = _state.value
        val t = s.target ?: return
        if (s.phase in setOf(Phase.NO_NETWORK, Phase.NEEDS_USER_RETRY, Phase.RECONNECTING)) {
            log.add("Сеть появилась — переподключение")
            open(t, s.phase.takeIf { it == Phase.NEEDS_USER_RETRY } ?: Phase.CONNECTING)
        }
    }

    /** Activity stopped: release decoder, remember where we were. */
    fun stop() {
        if (_state.value.target is PlaybackTarget.Archive && engineOnTarget && engine.state == EngineState.READY) archivePositionMs = engine.positionMs
        openJob?.cancel()
        tickJob?.cancel()
        engine.stop()
        setPhase(Phase.IDLE)
    }

    /** Activity started again: fresh URL, same place. */
    fun start() {
        val t = _state.value.target ?: return
        policy.onSuccess()
        open(t, Phase.CONNECTING)
    }

    fun setPaused(paused: Boolean) {
        engine.setPaused(paused)
        _state.update { it.copy(paused = paused) }
    }

    fun seekTo(positionMs: Long) {
        archivePositionMs = positionMs
        engine.seekTo(positionMs)
        watchdog.reset(clock.nowMs())
        freeze.reset(clock.nowMs())
    }

    fun setMaxHeight(height: Int) = engine.setMaxHeight(height)

    override fun onStateChanged(state: EngineState) {
        when (state) {
            EngineState.READY -> {
                if (_state.value.phase != Phase.PLAYING) log.add("Показ идёт")
                policy.onSuccess()
                _state.update { it.copy(phase = Phase.PLAYING, attempt = 0) }
            }
            EngineState.ENDED -> {
                val t = _state.value.target
                if (t is PlaybackTarget.Archive) {
                    log.add("Запись кончилась на ${mmss(engine.positionMs)}")
                    _events.tryEmit(PlayerEvent.RecordingEnded(t, engine.positionMs))
                } else {
                    fail(IllegalStateException("live ended"))
                }
            }
            else -> Unit
        }
    }

    override fun onError(behindLiveWindow: Boolean, message: String, notFound: NotFound?) {
        if (behindLiveWindow) {
            engine.seekToLive()
            return
        }
        val t = _state.value.target
        if (notFound != null && t is PlaybackTarget.Archive) return recordingNotFound(t, notFound)
        fail(RuntimeException(message))
    }
}
