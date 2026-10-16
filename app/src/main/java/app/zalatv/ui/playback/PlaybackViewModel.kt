package app.zalatv.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import app.zalatv.AppContainer
import app.zalatv.core.ZalaTime
import app.zalatv.data.api.Channel
import app.zalatv.data.api.Programme
import app.zalatv.data.repo.ChannelLists
import app.zalatv.data.repo.EpgDay
import app.zalatv.data.repo.EpgLogic
import app.zalatv.data.session.NotLoggedInException
import app.zalatv.data.store.Settings
import app.zalatv.player.ArchiveTimeline
import app.zalatv.player.PlaybackTarget
import app.zalatv.player.PlayerEvent
import app.zalatv.player.SeekAccumulator
import app.zalatv.player.TimelinePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Overlay { NONE, LIST, MENU, EPG }
enum class ListMode { FAVORITES, ALL }

sealed interface Dialog {
    data class Favorite(val channel: Channel, val isFavorite: Boolean) : Dialog
    data class StartOver(val channel: Channel, val programme: Programme) : Dialog
    data object ExitArchive : Dialog
}

data class EpgUi(
    val channel: Channel,
    val days: List<EpgDay>,
    val dayIndex: Int,
    val items: List<Programme>,
    val loading: Boolean,
    val returnTo: Overlay,
    val focused: Programme? = null,
)

data class ArchiveUi(
    val programme: Programme,
    val controlsVisible: Boolean = true,
    val positionMs: Long = 0,
    /** Wall-clock time the user is seeking to, while the jump is pending. */
    val seekTargetMs: Long? = null,
    val seekDeltaMs: Long = 0,
)

data class PlaybackUi(
    val overlay: Overlay = Overlay.NONE,
    val mode: ListMode? = null,
    val current: Channel? = null,
    val focused: Channel? = null,
    val bannerVisible: Boolean = false,
    val now: Programme? = null,
    val next: Programme? = null,
    val focusedNext: Programme? = null,
    val epg: EpgUi? = null,
    val archive: ArchiveUi? = null,
    val dialog: Dialog? = null,
    val toast: String? = null,
    val channelsFailed: Boolean = false,
    val needLogin: Boolean = false,
)

@UnstableApi
class PlaybackViewModel(private val c: AppContainer) : ViewModel() {
    val player = c.player
    val lists: StateFlow<ChannelLists> = c.channels.lists
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChannelLists(emptyList(), emptyList(), emptyList()))
    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _ui = MutableStateFlow(PlaybackUi())
    val ui: StateFlow<PlaybackUi> = _ui

    private var bannerJob: Job? = null
    private var previewJob: Job? = null
    private var toastJob: Job? = null
    private var controlsJob: Job? = null
    private var seekJob: Job? = null
    private var epgJob: Job? = null
    private val seek = SeekAccumulator()
    private var timeline: List<Programme> = emptyList()

    init {
        viewModelScope.launch {
            c.channels.loadCached()
            loadChannels()
        }
        viewModelScope.launch {
            val l = lists.first { it.all.isNotEmpty() }
            val s = settings.first()
            val start = l.all.firstOrNull { it.code == s.lastChannel } ?: l.favorites.firstOrNull() ?: l.all.first()
            if (_ui.value.current == null) switchTo(start)
        }
        viewModelScope.launch { player.events.collect(::onPlayerEvent) }
        viewModelScope.launch {
            while (isActive) {
                delay(30 * 60_000)
                runCatching { c.channels.refresh() }
            }
        }
    }

    private suspend fun loadChannels() {
        val delays = listOf(2_000L, 4_000, 8_000, 16_000, 30_000)
        for (attempt in 0..delays.size) {
            try {
                c.channels.refresh()
                c.epg.refreshDepth()
                _ui.update { it.copy(channelsFailed = false) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotLoggedInException) {
                _ui.update { it.copy(needLogin = true) }
                return
            } catch (e: Exception) {
                c.log.add("Каналы: ${e.javaClass.simpleName}")
                if (lists.value.all.isNotEmpty()) return // cached list is good enough; refresh later
                if (attempt < delays.size) delay(delays[attempt])
            }
        }
        _ui.update { it.copy(channelsFailed = true) }
    }

    fun retryChannels() {
        _ui.update { it.copy(channelsFailed = false) }
        viewModelScope.launch { loadChannels() }
    }

    fun retryPlayer() = player.retry()

    // ---- channel lists ----

    private fun mode(): ListMode = _ui.value.mode ?: if (lists.value.favorites.isNotEmpty()) ListMode.FAVORITES else ListMode.ALL

    fun activeList(): List<Channel> {
        val l = lists.value
        return if (mode() == ListMode.FAVORITES && l.favorites.isNotEmpty()) l.favorites else l.all
    }

    fun currentMode(): ListMode = if (mode() == ListMode.FAVORITES && lists.value.favorites.isNotEmpty()) ListMode.FAVORITES else ListMode.ALL

    // ---- live ----

    fun switchTo(ch: Channel) {
        exitArchiveState()
        _ui.update { it.copy(current = ch, now = null, next = null) }
        player.play(PlaybackTarget.Live(ch))
        viewModelScope.launch { c.settings.update { it.copy(lastChannel = ch.code) } }
        showBanner()
    }

    fun stepChannel(delta: Int) {
        val list = activeList()
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.code == _ui.value.current?.code }
        val next = list[((if (idx < 0) 0 else idx + delta) % list.size + list.size) % list.size]
        switchTo(next)
    }

    fun showBanner() {
        val ch = _ui.value.current ?: return
        _ui.update { it.copy(bannerVisible = true) }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            loadNowNext(ch)?.let { (n, x) -> _ui.update { u -> if (u.current?.code == ch.code) u.copy(now = n, next = x) else u } }
            delay(5_000)
            _ui.update { it.copy(bannerVisible = false) }
        }
    }

    private suspend fun loadNowNext(ch: Channel): Pair<Programme?, Programme?>? = runCatching {
        val now = c.clock.nowMs()
        val list = c.epg.day(ch.code, ZalaTime.localDate(now)) + runCatching { c.epg.day(ch.code, ZalaTime.localDate(now).plusDays(1)) }.getOrDefault(emptyList())
        EpgLogic.current(list, now) to EpgLogic.next(list, now)
    }.getOrNull()

    // ---- list overlay ----

    fun openList() {
        _ui.update { it.copy(overlay = Overlay.LIST, focused = it.current, bannerVisible = false) }
        viewModelScope.launch { runCatching { c.channels.refresh() } }
    }

    fun onListFocus(ch: Channel) {
        if (_ui.value.focused?.code == ch.code && _ui.value.overlay == Overlay.LIST) return
        _ui.update { it.copy(focused = ch, focusedNext = null) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(1_000)
            val t = player.state.value.target
            if (_ui.value.overlay == Overlay.LIST && (t !is PlaybackTarget.Live || t.channel.code != ch.code)) {
                player.play(PlaybackTarget.Live(ch))
            }
            loadNowNext(ch)?.let { (_, x) -> _ui.update { u -> if (u.focused?.code == ch.code) u.copy(focusedNext = x) else u } }
        }
    }

    fun onListSelect(ch: Channel) {
        previewJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.NONE) }
        val t = player.state.value.target
        if (t is PlaybackTarget.Live && t.channel.code == ch.code && _ui.value.archive == null) {
            _ui.update { it.copy(current = ch) }
            viewModelScope.launch { c.settings.update { it.copy(lastChannel = ch.code) } }
            showBanner()
        } else {
            switchTo(ch)
        }
    }

    fun closeOverlays() {
        previewJob?.cancel()
        val cur = _ui.value.current
        _ui.update { it.copy(overlay = Overlay.NONE, epg = null) }
        val t = player.state.value.target
        if (cur != null && _ui.value.archive == null && (t !is PlaybackTarget.Live || t.channel.code != cur.code)) {
            player.play(PlaybackTarget.Live(cur))
        }
    }

    fun openMenu() = _ui.update { it.copy(overlay = Overlay.MENU) }

    /** Leaving to settings: come back to plain fullscreen on the current channel. */
    fun resetOverlays() {
        previewJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.NONE, epg = null, dialog = null, focused = it.current) }
        val cur = _ui.value.current
        val t = player.state.value.target
        if (cur != null && _ui.value.archive == null && t is PlaybackTarget.Live && t.channel.code != cur.code) {
            player.play(PlaybackTarget.Live(cur))
        }
    }

    fun selectMode(m: ListMode) {
        _ui.update { it.copy(mode = m, overlay = Overlay.LIST) }
    }

    fun askFavorite(ch: Channel) {
        val fav = settings.value.favorites.contains(ch.code)
        _ui.update { it.copy(dialog = Dialog.Favorite(ch, fav)) }
    }

    fun toggleFavorite(ch: Channel) {
        dismissDialog()
        viewModelScope.launch {
            c.settings.update {
                if (ch.code in it.favorites) it.copy(favorites = it.favorites - ch.code) else it.copy(favorites = it.favorites + ch.code)
            }
            val nowFav = ch.code in settings.value.favorites
            toast(if (nowFav) "★ ${ch.name} — в избранном" else "${ch.name} убран из избранного")
        }
    }

    // ---- EPG ----

    fun openEpg(ch: Channel) {
        val now = c.clock.nowMs()
        val days = EpgLogic.days(now, c.epg.depthMs)
        val today = days.indexOfFirst { it.date == ZalaTime.localDate(now) }.coerceAtLeast(0)
        val returnTo = if (_ui.value.overlay == Overlay.LIST) Overlay.LIST else Overlay.NONE
        _ui.update { it.copy(overlay = Overlay.EPG, bannerVisible = false, epg = EpgUi(ch, days, today, emptyList(), true, returnTo)) }
        loadEpgDay()
    }

    fun epgDay(delta: Int) {
        val e = _ui.value.epg ?: return
        val i = (e.dayIndex + delta).coerceIn(0, e.days.lastIndex)
        if (i == e.dayIndex) return
        _ui.update { it.copy(epg = e.copy(dayIndex = i, items = emptyList(), loading = true, focused = null)) }
        loadEpgDay()
    }

    private fun loadEpgDay() {
        val e = _ui.value.epg ?: return
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val items = runCatching { c.epg.day(e.channel.code, e.days[e.dayIndex].date) }.getOrElse {
                c.log.add("Программа: ${it.javaClass.simpleName}")
                emptyList()
            }
            _ui.update { u -> u.epg?.let { cur -> if (cur.dayIndex == e.dayIndex) u.copy(epg = cur.copy(items = items, loading = false)) else u } ?: u }
        }
    }

    fun onEpgFocus(p: Programme) = _ui.update { u -> u.copy(epg = u.epg?.copy(focused = p)) }

    fun canRecord(ch: Channel, p: Programme) = EpgLogic.canPlayRecording(p, ch, c.clock.nowMs(), c.epg.depthMs)

    fun onEpgSelect(p: Programme) {
        val e = _ui.value.epg ?: return
        val now = c.clock.nowMs()
        when {
            p.startMs > now -> toast("Передача ещё не началась")
            now < p.endMs -> if (canRecord(e.channel, p)) _ui.update { it.copy(dialog = Dialog.StartOver(e.channel, p)) }
            else { _ui.update { it.copy(overlay = Overlay.NONE, epg = null) }; switchTo(e.channel) }
            canRecord(e.channel, p) -> playArchive(e.channel, p, 0)
            else -> toast("Запись недоступна")
        }
    }

    fun epgBack() {
        val e = _ui.value.epg
        if (e?.returnTo == Overlay.LIST) _ui.update { it.copy(overlay = Overlay.LIST, epg = null) } else closeOverlays()
    }

    fun watchLiveFromDialog(ch: Channel) {
        dismissDialog()
        _ui.update { it.copy(overlay = Overlay.NONE, epg = null) }
        switchTo(ch)
    }

    // ---- archive ----

    fun playArchive(ch: Channel, p: Programme, offsetMs: Long) {
        dismissDialog()
        previewJob?.cancel()
        seek.cancel()
        _ui.update { it.copy(overlay = Overlay.NONE, epg = null, current = ch, bannerVisible = false, archive = ArchiveUi(p, positionMs = offsetMs)) }
        player.play(PlaybackTarget.Archive(ch, p, offsetMs))
        showControls()
        viewModelScope.launch { timeline = runCatching { c.epg.around(ch.code, p.startMs) }.getOrDefault(listOf(p)) }
        ensureArchiveTicker()
    }

    private fun exitArchiveState() {
        seek.cancel()
        seekJob?.cancel()
        controlsJob?.cancel()
        _ui.update { it.copy(archive = null) }
    }

    fun backToLive() {
        val ch = _ui.value.current ?: return
        dismissDialog()
        switchTo(ch)
    }

    fun togglePause() {
        val paused = !player.state.value.paused
        player.setPaused(paused)
        showControls(autoHide = !paused)
    }

    fun seekPress(direction: Int, big: Boolean, isRepeat: Boolean) {
        val a = _ui.value.archive ?: return
        seek.press(direction, big, isRepeat, c.clock.nowMs())
        val target = a.programme.startMs + player.positionMs + seek.pendingMs
        _ui.update { it.copy(archive = a.copy(seekTargetMs = target, seekDeltaMs = seek.pendingMs)) }
        showControls()
    }

    private fun ensureArchiveTicker() {
        if (seekJob?.isActive == true) return
        seekJob = viewModelScope.launch {
            while (isActive && _ui.value.archive != null) {
                delay(100)
                seek.takeIfSettled(c.clock.nowMs())?.let { applySeek(it) }
                _ui.update { u -> u.archive?.let { a -> u.copy(archive = a.copy(positionMs = if (a.seekTargetMs == null) player.positionMs else a.positionMs)) } ?: u }
            }
        }
    }

    private fun applySeek(deltaMs: Long) {
        val a = _ui.value.archive ?: return
        val ch = _ui.value.current ?: return
        val wall = a.programme.startMs + player.positionMs + deltaMs
        val tl = ArchiveTimeline(timeline.ifEmpty { listOf(a.programme) }, c.clock.nowMs(), c.epg.depthMs)
        when (val r = tl.resolve(wall)) {
            is TimelinePoint.InProgramme ->
                if (r.programme.code == a.programme.code && r.programme.startMs == a.programme.startMs) {
                    player.seekTo(r.offsetMs)
                    _ui.update { it.copy(archive = a.copy(seekTargetMs = null, seekDeltaMs = 0, positionMs = r.offsetMs)) }
                } else {
                    playArchive(ch, r.programme, r.offsetMs)
                }
            TimelinePoint.Live -> {
                toast("Прямой эфир")
                switchTo(ch)
            }
        }
    }

    fun showControls(autoHide: Boolean = true) {
        _ui.update { u -> u.archive?.let { u.copy(archive = it.copy(controlsVisible = true)) } ?: u }
        controlsJob?.cancel()
        if (!autoHide) return
        controlsJob = viewModelScope.launch {
            delay(5_000)
            while (seek.active) delay(500)
            if (!player.state.value.paused) _ui.update { u -> u.archive?.let { u.copy(archive = it.copy(controlsVisible = false)) } ?: u }
        }
    }

    private fun onPlayerEvent(e: PlayerEvent) {
        when (e) {
            is PlayerEvent.RecordingEnded -> {
                val tl = ArchiveTimeline(timeline.ifEmpty { listOf(e.target.programme) }, c.clock.nowMs(), c.epg.depthMs)
                when (val r = tl.onEnded(e.target.programme, e.positionMs)) {
                    is TimelinePoint.InProgramme -> playArchive(e.target.channel, r.programme, r.offsetMs)
                    TimelinePoint.Live -> switchTo(e.target.channel)
                }
            }
            is PlayerEvent.RecordingUnavailable -> {
                toast("Запись недоступна")
                switchTo(e.target.channel)
            }
        }
    }

    // ---- back / dialogs / toast ----

    fun onBack() {
        val u = _ui.value
        when {
            u.dialog != null -> dismissDialog()
            u.overlay == Overlay.EPG -> epgBack()
            u.overlay == Overlay.MENU -> _ui.update { it.copy(overlay = Overlay.LIST) }
            u.overlay == Overlay.LIST -> closeOverlays()
            u.archive != null -> _ui.update { it.copy(dialog = Dialog.ExitArchive) }
            else -> showBanner()
        }
    }

    fun dismissDialog() = _ui.update { it.copy(dialog = null) }

    fun toast(text: String) {
        _ui.update { it.copy(toast = text) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(3_000)
            _ui.update { it.copy(toast = null) }
        }
    }

    fun nowMs() = c.clock.nowMs()
}
