package app.remotely.ui.playback

import android.view.ViewGroup
import android.app.Activity
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import app.remotely.R
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import android.view.View
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.tv.material3.Text
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.CueGroup
import app.remotely.player.SubtitleChoice
import androidx.media3.ui.PlayerView
import app.remotely.AppContainer
import app.remotely.player.Phase
import app.remotely.ui.common.AnimatedNullable
import app.remotely.ui.common.MessageDialog
import app.remotely.ui.common.Motion
import androidx.compose.foundation.layout.fillMaxWidth

@UnstableApi
@Composable
fun PlaybackScreen(c: AppContainer, onOpenSettings: () -> Unit, onNeedLogin: () -> Unit) {
    val vm: PlaybackViewModel = viewModel(initializer = { PlaybackViewModel(c) })
    val ui by vm.ui.collectAsState()
    val ps by vm.player.state.collectAsState()
    val lists by vm.lists.collectAsState()
    val settings by vm.settings.collectAsState()
    val tracks by c.engine.tracks.collectAsState()
    // Until the new channel plays, the engine still reports the previous one's tracks: no badges then.
    val playingTracks = tracks.takeIf { ps.phase == Phase.PLAYING }
    // The sound chosen before for the channel about to play.
    val targetCode = ps.target?.channel?.code
    LaunchedEffect(targetCode, settings.audio) { c.engine.preferAudio(targetCode?.let { settings.audio[it] }) }
    val rootFocus = remember { FocusRequester() }
    val activity = LocalContext.current as? Activity
    val okArmed = remember { booleanArrayOf(false) }

    DisposableEffect(Unit) {
        if (ps.phase == Phase.IDLE && ps.target != null) vm.player.start()
        onDispose { vm.player.stop() }
    }
    LaunchedEffect(ps.phase, ui.needLogin) {
        if (ps.phase == Phase.NOT_LOGGED_IN || ui.needLogin) onNeedLogin()
    }

    val blocking = ps.phase == Phase.NEEDS_USER_RETRY || ps.phase == Phase.BAD_CREDENTIALS || ui.channelsFailed
    val fullscreenKeys = ui.overlay == Overlay.NONE && ui.dialog == null && !blocking
    LaunchedEffect(fullscreenKeys) {
        if (fullscreenKeys) repeat(10) { if (runCatching { rootFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(50) }
    }

    val withPanel = ui.overlay != Overlay.NONE
    // A touch or mouse click can leave nothing focused; Back then bypasses the key handler below
    // and would close the app. Route it the same way.
    BackHandler { if (blocking && ui.dialog == null) vm.askExit() else vm.onBack() }
    // Pointer handlers live as long as the screen; they read the current state through this.
    val state = rememberUpdatedState(PointerState(ui, blocking, fullscreenKeys))

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                val up = ev.type == KeyEventType.KeyUp
                val repeat = ev.nativeKeyEvent.repeatCount > 0
                // Back is handled for every state here: the app only exits through «Выход» in the menu.
                if (ev.key == Key.Back) {
                    if (up) {
                        if (blocking && ui.dialog == null) vm.askExit() else vm.onBack()
                    }
                    return@onPreviewKeyEvent true
                }
                if (ui.dialog != null || blocking) return@onPreviewKeyEvent false
                when (ui.overlay) {
                    Overlay.LIST -> when (ev.key) {
                        Key.DirectionLeft -> { if (down) vm.openMenu(); true }
                        Key.DirectionRight -> { if (down) ui.focused?.let(vm::openEpg); true }
                        else -> false
                    }
                    Overlay.MENU -> when (ev.key) {
                        Key.DirectionRight -> { if (down) vm.menuRight(); true }
                        Key.DirectionLeft -> { if (down && ui.groupsOpen) vm.closeGroups(); ui.groupsOpen }
                        else -> false
                    }
                    Overlay.EPG -> when (ev.key) {
                        Key.DirectionLeft -> { if (down && !repeat) vm.epgDay(-1); true }
                        Key.DirectionRight -> { if (down && !repeat) vm.epgDay(+1); true }
                        else -> false
                    }
                    Overlay.NONE -> {
                        val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                        if (isOk && down) okArmed[0] = true
                        if (isOk && up && !okArmed[0]) return@onPreviewKeyEvent true
                        if (isOk && up) okArmed[0] = false
                        if (ui.archive != null) archiveKeys(vm, ev.key, down, up, repeat) else liveKeys(vm, ui, ev.key, down, up, repeat, soundChoice = ui.bannerVisible && ui.bannerDetailed && (playingTracks?.audio?.size ?: 0) > 1)
                    }
                }
            }
            // Mouse: the right button is Back everywhere; the wheel zaps (live) or seeks (archive) in fullscreen.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val s = state.value
                        when (ev.type) {
                            PointerEventType.Press -> if (ev.buttons.isSecondaryPressed) {
                                ev.changes.forEach { it.consume() }
                                if (s.blocking && s.ui.dialog == null) vm.askExit() else vm.onBack()
                            }
                            PointerEventType.Scroll -> if (s.fullscreen) {
                                val dy = ev.changes.sumOf { it.scrollDelta.y.toDouble() }
                                if (dy != 0.0) {
                                    ev.changes.forEach { it.consume() }
                                    val towardsEnd = dy > 0 // wheel down, like the Down key
                                    if (s.ui.archive != null) vm.seekPress(if (towardsEnd) -1 else +1, false, false)
                                    else vm.stepChannel(if (towardsEnd) -1 else +1)
                                }
                            }
                        }
                    }
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        // Browsing the list: the old channel's picture and sound go at once, the new picture shows when it plays.
        val shown = ps.target?.channel?.code
        val oldChannel = ui.overlay == Overlay.LIST && ui.focused != null && ui.focused?.code != shown
        val hideVideo = oldChannel || (ui.overlay == Overlay.LIST && ps.phase == Phase.CONNECTING) || ps.phase == Phase.BLACKOUT
        LaunchedEffect(oldChannel) { c.engine.player.volume = if (oldChannel) 0f else 1f }
        VideoSurface(c.engine.player, preview = withPanel, hidden = hideVideo)
        LaunchedEffect(withPanel) { c.engine.setPreview(withPanel) }
        // A click or tap on the video does what OK does; a vertical swipe does what Up/Down do.
        if (fullscreenKeys) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures {
                    runCatching { rootFocus.requestFocus() } // keep the remote working after a click
                    if (state.value.ui.archive != null) vm.togglePause() else vm.onOk()
                }
            }.pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = {
                        if (abs(dy) >= SWIPE_DP.dp.toPx()) {
                            runCatching { rootFocus.requestFocus() }
                            val up = dy < 0 // finger moves up, like the Up key
                            if (state.value.ui.archive != null) vm.seekPress(if (up) +1 else -1, true, false)
                            else vm.stepChannel(if (up) +1 else -1)
                        }
                    },
                ) { change, amount -> change.consume(); dy += amount }
            })
        }

        AnimatedVisibility(
            visible = ui.overlay == Overlay.LIST || ui.overlay == Overlay.MENU,
            enter = Motion.fromLeft, exit = Motion.toLeft,
        ) {
            Box(Modifier.fillMaxSize()) {
                ChannelPanel(vm, ui, lists, settings.favorites, dimmed = ui.overlay == Overlay.MENU, dialogOpen = ui.dialog != null)
                CompositionLocalProvider(LocalGenreColors provides settings.genreColors) {
                    PreviewInfo(ui, vm, playingTracks?.takeIf { ps.target?.channel?.code == ui.focused?.code })
                }
            }
        }
        AnimatedVisibility(visible = ui.overlay == Overlay.MENU, enter = Motion.fromLeft, exit = Motion.toLeft) {
            Box(Modifier.fillMaxSize()) {
                SideMenu(vm, lists.groups, ui.groupsOpen, active = ui.overlay == Overlay.MENU, dialogOpen = ui.dialog != null, onOpenSettings = { vm.resetOverlays(); onOpenSettings() }, onExit = vm::askExit)
            }
        }
        AnimatedNullable(ui.epg.takeIf { ui.overlay == Overlay.EPG }, enter = Motion.fromRight, exit = Motion.toRight) { epg ->
            Box(Modifier.fillMaxSize()) { CompositionLocalProvider(LocalGenreColors provides settings.genreColors) { EpgPanel(vm, epg) } }
        }

        ui.archive?.let { ArchiveOverlay(it, ps.paused, vm) }
        AnimatedNullable(
            ui.current.takeIf { ui.overlay == Overlay.NONE && ui.archive == null && ui.bannerVisible },
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = Motion.fromBottom, exit = Motion.toBottom,
        ) { ch ->
            Box(Modifier.fillMaxWidth()) {
                CompositionLocalProvider(LocalGenreColors provides settings.genreColors) {
                    Banner(ch, lists.numberOf(ch), ui.now, ui.next, vm.nowMs(), ui.bannerDetailed, playingTracks?.takeIf { ch.code == targetCode }, onSound = vm::cycleAudio)
                }
            }
        }

        // Not under the banner or the archive controls: they would cover each other.
        if (!withPanel && !ui.bannerVisible && ui.archive?.controlsVisible != true) Subtitles(c.engine.player)
        // Touch: a tap on the small video goes back to fullscreen, as OK on the channel in the list.
        if (withPanel && ui.dialog == null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = PreviewTop, end = PreviewEnd).size(PreviewWidth, PreviewHeight)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            runCatching { rootFocus.requestFocus() }
                            val u = state.value.ui
                            val f = u.focused
                            if (u.overlay == Overlay.LIST && f != null) vm.onListSelect(f) else vm.closeOverlays()
                        }
                    },
            )
        }
        StatusPill(ps.phase, ps.target, overPreview = withPanel, blackoutUntilMs = ps.blackoutUntilMs)
        AnimatedNullable(ui.toast, modifier = Modifier.align(Alignment.TopCenter), enter = Motion.fromTop, exit = Motion.toTop) { Toast(it) }

        val dialog: DialogSpec? = when (val d = ui.dialog) {
            is Dialog.Favorite -> DialogSpec(
                d.channel.name, null,
                listOf((if (d.isFavorite) "Убрать из избранного" else "★ Добавить в избранное") to { vm.toggleFavorite(d.channel) }, "Отмена" to vm::dismissDialog),
            )
            is Dialog.StartOver -> DialogSpec(
                d.programme.title, "Передача идёт сейчас",
                listOf("Смотреть эфир" to { vm.watchLiveFromDialog(d.channel) }, "⟲ С начала" to { vm.playArchive(d.channel, d.programme, 0) }),
            )
            Dialog.ExitArchive -> DialogSpec(
                "Вы смотрите передачу из архива", null,
                listOf("Вернуться в прямой эфир" to vm::backToLive, "Смотреть дальше" to vm::dismissDialog),
            )
            Dialog.ExitApp -> DialogSpec(
                "Выйти из приложения?", null,
                // «Выйти» first: it takes the focus, the menu item was already a deliberate choice.
                listOf("Выйти" to { activity?.finish() }, "Отмена" to vm::dismissDialog),
            )
            null -> when {
                ui.channelsFailed -> DialogSpec("Не удаётся загрузить каналы", "Проверьте интернет", listOf("Повторить" to vm::retryChannels))
                ps.phase == Phase.NEEDS_USER_RETRY -> DialogSpec("Не удаётся подключиться к Zala", "Проверьте интернет и нажмите «Повторить»", listOf("Повторить" to vm::retryPlayer))
                ps.phase == Phase.BAD_CREDENTIALS -> DialogSpec("Нужна помощь: неверный логин", "Попросите помочь с настройкой", listOf("Настройки" to onOpenSettings))
                else -> null
            }
        }
        AnimatedNullable(dialog, enter = Motion.pop, exit = Motion.unpop) { MessageDialog(it.title, it.message, it.buttons) }
    }
}

private const val SWIPE_DP = 48

private data class PointerState(val ui: PlaybackUi, val blocking: Boolean, val fullscreen: Boolean)

private data class DialogSpec(val title: String, val message: String?, val buttons: List<Pair<String, () -> Unit>>)

/** [soundChoice]: the description is open on a channel with several sound tracks — ▼ switches the sound, ▲ does nothing. */
private fun liveKeys(vm: PlaybackViewModel, ui: PlaybackUi, key: Key, down: Boolean, up: Boolean, repeat: Boolean, soundChoice: Boolean): Boolean = when (key) {
    Key.DirectionUp -> { if (down && !soundChoice) vm.stepChannel(+1, repeat); true }
    Key.DirectionDown -> { if (down) { if (soundChoice) { if (!repeat) vm.cycleAudio() } else vm.stepChannel(-1, repeat) }; true }
    Key.ChannelUp -> { if (down) vm.stepChannel(+1, repeat); true }
    Key.ChannelDown -> { if (down) vm.stepChannel(-1, repeat); true }
    Key.MediaAudioTrack -> { if (down && !repeat) vm.cycleAudio(); true }
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (up) vm.onOk(); true }
    Key.DirectionLeft -> { if (down && !repeat) vm.openList(); true }
    Key.DirectionRight -> { if (down && !repeat) ui.current?.let(vm::openEpg); true }
    Key.Info, Key.Menu -> { if (down) vm.showBanner(detailed = true); true }
    else -> false
}

private fun archiveKeys(vm: PlaybackViewModel, key: Key, down: Boolean, up: Boolean, repeat: Boolean): Boolean = when (key) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> { if (up) vm.togglePause(); true }
    Key.DirectionLeft, Key.MediaRewind -> { if (down) vm.seekPress(-1, false, repeat); true }
    Key.DirectionRight, Key.MediaFastForward -> { if (down) vm.seekPress(+1, false, repeat); true }
    Key.DirectionUp -> { if (down) vm.seekPress(+1, true, repeat); true }
    Key.DirectionDown -> { if (down) vm.seekPress(-1, true, repeat); true }
    Key.MediaAudioTrack -> { if (down && !repeat) vm.cycleAudio(); true }
    else -> false
}

/** Subtitles like on Apple TV: all lines on one slightly transparent box with rounded corners. */
@Composable
private fun BoxScope.Subtitles(exo: Player) {
    var text by remember { mutableStateOf("") }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    .joinToString("\n").let(SubtitleChoice::fixText)
            }
        }
        exo.addListener(l)
        text = exo.currentCues.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n").let(SubtitleChoice::fixText)
        onDispose { exo.removeListener(l) }
    }
    if (text.isEmpty()) return
    Text(
        text,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).widthIn(max = 760.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color(0xB3000000)).padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Fullscreen ↔ preview by animating a graphics layer: no relayout, no SurfaceView resize jank. */
@Composable
private fun VideoSurface(exo: Player, preview: Boolean, hidden: Boolean) {
    val t by animateFloatAsState(if (preview) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "video")
    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(if (hidden) 80 else 200), label = "videoAlpha")
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullW = maxWidth
        val scale = 1f - t * (1f - PreviewWidth / fullW)
        val tx = with(density) { ((fullW - PreviewEnd - PreviewWidth) * t).toPx() }
        val ty = with(density) { (PreviewTop * t).toPx() }
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = exo
                    // Subtitles are drawn by [Subtitles] on one shared background, not line by line here.
                    subtitleView?.visibility = View.GONE
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = tx
                    translationY = ty
                    shape = RoundedCornerShape((10f * t / scale.coerceAtLeast(0.01f)).dp)
                    clip = t > 0f
                    this.alpha = alpha
                },
        )
    }
}
