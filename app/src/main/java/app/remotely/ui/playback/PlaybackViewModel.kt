package app.remotely.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import app.remotely.AppContainer
import app.remotely.core.ZalaTime
import app.remotely.data.api.Channel
import app.remotely.data.api.Programme
import app.remotely.data.repo.ChannelGroup
import app.remotely.data.repo.ChannelLists
import app.remotely.data.repo.EpgDay
import app.remotely.data.repo.EpgLogic
import app.remotely.data.session.NotLoggedInException
import app.remotely.data.store.Settings
import app.remotely.data.update.UpdateState
import app.remotely.player.ArchiveTimeline
import app.remotely.player.PlaybackTarget
import app.remotely.player.PlayerEvent
import app.remotely.player.SeekAccumulator
import app.remotely.player.TimelinePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Overlay { NONE, LIST, MENU, EPG }
enum class ListMode { FAVORITES, ALL, GROUP }

sealed interface Dialog {
    data class Favorite(val channel: Channel, val isFavorite: Boolean) : Dialog
    data class StartOver(val channel: Channel, val programme: Programme) : Dialog
    data object ExitArchive : Dialog
    data object ExitApp : Dialog
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
    /** Category code while [mode] is [ListMode.GROUP]. */
    val group: String? = null,
    /** The menu shows its second page, the list of categories. */
    val groupsOpen: Boolean = false,
    val current: Channel? = null,
    val focused: Channel? = null,
    val bannerVisible: Boolean = false,
    /** Banner opened with OK: shows the programme description too. */
    val bannerDetailed: Boolean = false,
    val now: Programme? = null,
    val next: Programme? = null,
    /** Current and following programmes of the focused channel (list overlay). */
    val focusedUpcoming: List<Programme> = emptyList(),
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

    /** A newer version is out (or on its way): the menu marks «Настройки». */
    val updatePending: StateFlow<Boolean> = c.updates.state.map { it !is UpdateState.Idle && it !is UpdateState.Checking && it !is UpdateState.UpToDate && it !is UpdateState.CheckFailed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
        // Each new version is announced once, with a short toast after the picture has started.
        viewModelScope.launch {
            c.updates.state.collect { s ->
                val st = settings.first()
                if (s !is UpdateState.Available || !st.autoCheckUpdates || st.announcedUpdate == s.release.version) return@collect
                _ui.first { it.current != null }
                delay(8_000)
                toast("Доступна версия ${s.release.version}: Меню → Настройки")
                c.settings.update { it.copy(announcedUpdate = s.release.version) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(30 * 60_000)
                runCatching { c.channels.refresh() }
                runCatching { c.channels.refreshGroups() }
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
                viewModelScope.launch { runCatching { c.channels.refreshGroups() }.onFailure { c.log.add("Категории: ${it.javaClass.simpleName}") } }
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

    private fun group(): ChannelGroup? = lists.value.groups.firstOrNull { it.code == _ui.value.group }

    fun activeList(): List<Channel> {
        val l = lists.value
        return when (currentMode()) {
            ListMode.FAVORITES -> l.favorites
            ListMode.GROUP -> group()?.channels ?: l.all
            ListMode.ALL -> l.all
        }
    }

    /** The mode actually shown: an empty favourites list or a vanished category falls back to all channels. */
    fun currentMode(): ListMode = when (mode()) {
        ListMode.FAVORITES -> if (lists.value.favorites.isNotEmpty()) ListMode.FAVORITES else ListMode.ALL
        ListMode.GROUP -> if (group() != null) ListMode.GROUP else ListMode.ALL
        ListMode.ALL -> ListMode.ALL
    }

    /** Category code of the list on screen, or null. */
    fun currentGroup(): String? = if (currentMode() == ListMode.GROUP) _ui.value.group else null

    fun listTitle(): String = when (currentMode()) {
        ListMode.FAVORITES -> "★ Избранное"
        ListMode.GROUP -> group()?.name ?: "Все каналы"
        ListMode.ALL -> "Все каналы"
    }

    // ---- live ----

    fun switchTo(ch: Channel) {
        exitArchiveState()
        _ui.update { it.copy(current = ch, now = null, next = null) }
        player.play(PlaybackTarget.Live(ch))
        viewModelScope.launch { c.settings.update { it.copy(lastChannel = ch.code) } }
        showBanner()
    }

    private var zapJob: Job? = null
    private var lastStepMs = 0L

    /**
     * ↑/↓ in fullscreen. The banner follows every press at once. A single press opens the stream at
     * once too; while presses follow each other the stream waits until they stop, so fast zapping
     * does not open a stream per channel and does not stall the UI.
     */
    fun stepChannel(delta: Int, isRepeat: Boolean = false) {
        val now = c.clock.nowMs()
        if (isRepeat && now - lastStepMs < ZAP_REPEAT_MS) return
        val burst = isRepeat || now - lastStepMs < ZAP_BURST_MS
        lastStepMs = now
        val list = activeList()
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.code == _ui.value.current?.code }
        val next = list[((if (idx < 0) 0 else idx + delta) % list.size + list.size) % list.size]
        _ui.update { it.copy(current = next, now = null, next = null) }
        showBanner()
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            if (burst) delay(ZAP_SETTLE_MS)
            switchTo(next)
        }
    }

    /** [detailed]: with the programme description, and kept longer so there is time to read it. */
    fun showBanner(detailed: Boolean = false) {
        val ch = _ui.value.current ?: return
        _ui.update { it.copy(bannerVisible = true, bannerDetailed = detailed) }
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            val started = c.clock.nowMs()
            loadNowNext(ch)?.let { (n, x) -> _ui.update { u -> if (u.current?.code == ch.code) u.copy(now = n, next = x) else u } }
            delay(((if (detailed) DETAILED_BANNER_MS else BANNER_MS) - (c.clock.nowMs() - started)).coerceAtLeast(1_000))
            hideBanner()
        }
    }

    fun hideBanner() {
        bannerJob?.cancel()
        _ui.update { it.copy(bannerVisible = false, bannerDetailed = false) }
    }

    /** OK in fullscreen: first the programme with its description, OK again opens the channel list. */
    fun onOk() {
        val u = _ui.value
        if (u.bannerVisible && u.bannerDetailed) openList() else showBanner(detailed = true)
    }

    private suspend fun loadNowNext(ch: Channel): Pair<Programme?, Programme?>? = runCatching {
        val now = c.clock.nowMs()
        val list = c.epg.day(ch.code, ZalaTime.localDate(now)) + runCatching { c.epg.day(ch.code, ZalaTime.localDate(now).plusDays(1)) }.getOrDefault(emptyList())
        EpgLogic.current(list, now) to EpgLogic.next(list, now)
    }.getOrNull()

    // ---- list overlay ----

    fun openList() {
        bannerJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.LIST, focused = it.current, focusedUpcoming = emptyList(), bannerVisible = false, bannerDetailed = false) }
        _ui.value.current?.let(::loadUpcoming)
        viewModelScope.launch { runCatching { c.channels.refresh() } }
    }

    private var upcomingJob: Job? = null

    /** Programme under the preview: short debounce so fast scrolling does not fetch every channel. */
    private fun loadUpcoming(ch: Channel) {
        upcomingJob?.cancel()
        upcomingJob = viewModelScope.launch {
            delay(250)
            val list = runCatching {
                val now = c.clock.nowMs()
                val today = ZalaTime.localDate(now)
                val all = c.epg.day(ch.code, today) + runCatching { c.epg.day(ch.code, today.plusDays(1)) }.getOrDefault(emptyList())
                EpgLogic.upcoming(all, now)
            }.getOrDefault(emptyList())
            _ui.update { u -> if (u.focused?.code == ch.code) u.copy(focusedUpcoming = list) else u }
        }
    }

    fun onListFocus(ch: Channel) {
        if (_ui.value.focused?.code == ch.code && _ui.value.overlay == Overlay.LIST) return
        _ui.update { it.copy(focused = ch, focusedUpcoming = emptyList()) }
        loadUpcoming(ch)
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_SETTLE_MS)
            val t = player.state.value.target
            if (_ui.value.overlay == Overlay.LIST && (t !is PlaybackTarget.Live || t.channel.code != ch.code)) {
                player.play(PlaybackTarget.Live(ch))
            }
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
        _ui.update { it.copy(overlay = Overlay.NONE, epg = null, groupsOpen = false) }
        val t = player.state.value.target
        if (cur != null && _ui.value.archive == null && (t !is PlaybackTarget.Live || t.channel.code != cur.code)) {
            player.play(PlaybackTarget.Live(cur))
        }
    }

    fun openMenu() = _ui.update { it.copy(overlay = Overlay.MENU, groupsOpen = false) }

    fun closeMenu() = _ui.update { it.copy(overlay = Overlay.LIST, groupsOpen = false) }

    fun openGroups() = _ui.update { it.copy(groupsOpen = true) }

    /** Menu item under focus: [MENU_CATEGORIES], a category code, or null. */
    var menuFocus: String? = null

    /** → in the menu goes one step deeper: into the categories, into the focused category, else back to the list. */
    fun menuRight() {
        val f = menuFocus
        when {
            !_ui.value.groupsOpen && f == MENU_CATEGORIES -> openGroups()
            _ui.value.groupsOpen && f != null && lists.value.groups.any { it.code == f } -> selectGroup(f)
            else -> closeMenu()
        }
    }

    fun closeGroups() = _ui.update { it.copy(groupsOpen = false) }

    /** Leaving to settings: come back to plain fullscreen on the current channel. */
    fun resetOverlays() {
        previewJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.NONE, epg = null, dialog = null, focused = it.current, groupsOpen = false) }
        val cur = _ui.value.current
        val t = player.state.value.target
        if (cur != null && _ui.value.archive == null && t is PlaybackTarget.Live && t.channel.code != cur.code) {
            player.play(PlaybackTarget.Live(cur))
        }
    }

    fun selectMode(m: ListMode) {
        _ui.update { it.copy(mode = m, group = null, overlay = Overlay.LIST, groupsOpen = false) }
    }

    fun selectGroup(code: String) {
        _ui.update { it.copy(mode = ListMode.GROUP, group = code, overlay = Overlay.LIST, groupsOpen = false) }
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
        bannerJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.EPG, bannerVisible = false, bannerDetailed = false, epg = EpgUi(ch, days, today, emptyList(), true, returnTo)) }
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
            else -> toast("Нет в архиве")
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

    /** The recording on screen was reached by playing or seeking on, not picked in the programme list. */
    private var continuedRecording = false

    fun playArchive(ch: Channel, p: Programme, offsetMs: Long, continued: Boolean = false) {
        continuedRecording = continued
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

    /** Touch seek on the progress bar: [fraction] of the programme; [commit] when the finger lifts. */
    fun seekToFraction(fraction: Float, commit: Boolean) {
        val a = _ui.value.archive ?: return
        val p = a.programme
        seek.cancel()
        val target = p.startMs + ((p.endMs - p.startMs) * fraction.coerceIn(0f, 1f)).toLong()
        val delta = target - (p.startMs + player.positionMs)
        _ui.update { it.copy(archive = a.copy(seekTargetMs = target, seekDeltaMs = delta)) }
        showControls(autoHide = commit)
        if (commit) applySeek(delta)
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
                    playArchive(ch, r.programme, r.offsetMs, continued = true)
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
                val p = e.target.programme
                val now = c.clock.nowMs()
                val tl = ArchiveTimeline(timeline.ifEmpty { listOf(p) }, now, c.epg.depthMs)
                // Zala's recording broke off before the programme did.
                val cut = p.endMs <= now && e.positionMs < p.endMs - p.startMs - CUT_MARGIN_MS
                when (val r = tl.onEnded(p, e.positionMs, e.target.startOffsetMs)) {
                    is TimelinePoint.InProgramme -> {
                        if (cut) toast("В архиве обрыв — следующая передача")
                        playArchive(e.target.channel, r.programme, r.offsetMs, continued = true)
                    }
                    TimelinePoint.Live -> {
                        if (now - (p.startMs + e.positionMs) > CUT_MARGIN_MS) toast("Дальше архива нет — прямой эфир")
                        switchTo(e.target.channel)
                    }
                }
            }
            is PlayerEvent.RecordingUnavailable -> {
                val p = e.target.programme
                val next = if (continuedRecording) ArchiveTimeline(timeline.ifEmpty { listOf(p) }, c.clock.nowMs(), c.epg.depthMs).next(p) else null
                if (next != null) {
                    toast("«${p.title}» нет в архиве — следующая передача")
                    playArchive(e.target.channel, next, 0, continued = true)
                } else {
                    toast("Нет в архиве")
                    switchTo(e.target.channel)
                }
            }
        }
    }

    // ---- back / dialogs / toast ----

    /**
     * Back walks outwards: fullscreen → channel list → menu → fullscreen again (from the categories page:
     * back to the menu first). It never leaves the app:
     * that takes «Выход» in the menu, so pressing Back a few times while browsing does not ask to quit.
     */
    fun onBack() {
        val u = _ui.value
        when {
            u.dialog != null -> dismissDialog()
            u.overlay == Overlay.EPG -> epgBack()
            u.overlay == Overlay.MENU && u.groupsOpen -> closeGroups()
            u.overlay == Overlay.MENU -> closeOverlays()
            u.overlay == Overlay.LIST -> openMenu()
            u.archive != null -> _ui.update { it.copy(dialog = Dialog.ExitArchive) }
            u.bannerVisible && u.bannerDetailed -> hideBanner()
            else -> openList()
        }
    }

    fun askExit() = _ui.update { it.copy(dialog = Dialog.ExitApp) }

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

    private companion object {
        const val ZAP_SETTLE_MS = 300L
        const val ZAP_REPEAT_MS = 250L
        /** The list opens the focused channel once the cursor rests this long. */
        const val PREVIEW_SETTLE_MS = 600L
        /** A press this soon after the previous one is zapping through: the stream waits for the presses to stop. */
        const val ZAP_BURST_MS = 600L
        const val BANNER_MS = 5_000L
        const val DETAILED_BANNER_MS = 10_000L
        /** A recording this much shorter than its programme was cut by the server. */
        const val CUT_MARGIN_MS = 60_000L
    }
}
