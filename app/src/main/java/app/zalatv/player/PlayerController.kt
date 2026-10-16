package app.zalatv.player

import app.zalatv.core.Clock
import app.zalatv.core.EventLog
import app.zalatv.core.RetryDecision
import app.zalatv.core.RetryPolicy
import app.zalatv.data.api.ArchiveUnavailableException
import app.zalatv.data.api.BadCredentialsException
import app.zalatv.data.api.Channel
import app.zalatv.data.api.Programme
import app.zalatv.data.repo.StreamSource
import app.zalatv.data.session.NotLoggedInException
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
        fun onError(behindLiveWindow: Boolean, message: String)
    }

    fun setListener(listener: Listener?)
    fun open(url: String, isLive: Boolean, startPositionMs: Long)
    fun stop()
    fun setPaused(paused: Boolean)
    fun seekTo(positionMs: Long)
    fun seekToLive()
    fun setMaxQuality(max: Boolean)
    val state: EngineState
    val playWhenReady: Boolean
    val positionMs: Long
    val durationMs: Long
    /** Monotonic media time (live: window start + position) used by the watchdog. */
    val progressMarker: Long
}

sealed interface PlaybackTarget {
    val channel: Channel

    data class Live(override val channel: Channel) : PlaybackTarget
    data class Archive(override val channel: Channel, val programme: Programme, val startOffsetMs: Long) : PlaybackTarget
}

enum class Phase { IDLE, CONNECTING, PLAYING, RECONNECTING, NO_NETWORK, NEEDS_USER_RETRY, BAD_CREDENTIALS, NOT_LOGGED_IN }

data class PlayerState(
    val target: PlaybackTarget? = null,
    val phase: Phase = Phase.IDLE,
    val attempt: Int = 0,
    val paused: Boolean = false,
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
    private val policy: RetryPolicy = RetryPolicy(),
    private val watchdog: Watchdog = Watchdog(),
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

    init {
        engine.setListener(this)
    }

    val positionMs: Long get() = engine.positionMs
    val durationMs: Long get() = engine.durationMs

    fun play(target: PlaybackTarget) {
        policy.onSuccess()
        archivePositionMs = (target as? PlaybackTarget.Archive)?.startOffsetMs ?: 0
        _state.value = PlayerState(target = target, phase = Phase.CONNECTING)
        open(target, Phase.CONNECTING)
    }

    private fun open(target: PlaybackTarget, phase: Phase) {
        openJob?.cancel()
        _state.update { it.copy(phase = phase, paused = false) }
        openJob = scope.launch {
            try {
                val url = when (target) {
                    is PlaybackTarget.Live -> streams.liveUrl(target.channel)
                    is PlaybackTarget.Archive -> streams.archiveUrl(target.channel, target.programme)
                }
                watchdog.reset(clock.nowMs())
                engine.open(url, target is PlaybackTarget.Live, if (target is PlaybackTarget.Archive) archivePositionMs else 0)
                ensureTicking()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
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
        if (s.target is PlaybackTarget.Archive && engine.state == EngineState.READY) archivePositionMs = engine.positionMs
        if (s.phase != Phase.PLAYING && s.phase != Phase.CONNECTING) return
        if (s.paused) return
        if (watchdog.tick(clock.nowMs(), engine.state, engine.playWhenReady, engine.progressMarker)) {
            log.add("Плеер завис — перезапуск")
            fail(IllegalStateException("stalled"))
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
        if (_state.value.target is PlaybackTarget.Archive && engine.state == EngineState.READY) archivePositionMs = engine.positionMs
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
    }

    fun setMaxQuality(max: Boolean) = engine.setMaxQuality(max)

    override fun onStateChanged(state: EngineState) {
        when (state) {
            EngineState.READY -> {
                if (_state.value.phase != Phase.PLAYING) log.add("Показ идёт")
                policy.onSuccess()
                _state.update { it.copy(phase = Phase.PLAYING, attempt = 0) }
            }
            EngineState.ENDED -> {
                val t = _state.value.target
                if (t is PlaybackTarget.Archive) _events.tryEmit(PlayerEvent.RecordingEnded(t, engine.positionMs))
                else fail(IllegalStateException("live ended"))
            }
            else -> Unit
        }
    }

    override fun onError(behindLiveWindow: Boolean, message: String) {
        if (behindLiveWindow) {
            engine.seekToLive()
            return
        }
        fail(RuntimeException(message))
    }
}
