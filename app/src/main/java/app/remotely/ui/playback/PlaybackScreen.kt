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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                val up = ev.type == KeyEventType.KeyUp
                val repeat = ev.nativeKeyEvent.repeatCount > 0
                // Back is handled for every state here: the app only exits through the "exit?" dialog.
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
                        Key.DirectionRight -> { if (down) vm.closeMenu(); true }
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
                        if (ui.archive != null) archiveKeys(vm, ev.key, down, up, repeat) else liveKeys(vm, ui, ev.key, down, up, repeat)
                    }
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        VideoSurface(c.engine.player, preview = withPanel)
        LaunchedEffect(withPanel) { c.engine.setPreview(withPanel) }

        AnimatedVisibility(
            visible = ui.overlay == Overlay.LIST || ui.overlay == Overlay.MENU,
            enter = Motion.fromLeft, exit = Motion.toLeft,
        ) {
            Box(Modifier.fillMaxSize()) {
                ChannelPanel(vm, ui, lists, settings.favorites, dimmed = ui.overlay == Overlay.MENU, dialogOpen = ui.dialog != null)
                PreviewInfo(ui, vm)
            }
        }
        AnimatedVisibility(visible = ui.overlay == Overlay.MENU, enter = Motion.fromLeft, exit = Motion.toLeft) {
            Box(Modifier.fillMaxSize()) {
                SideMenu(vm, dialogOpen = ui.dialog != null, onOpenSettings = { vm.resetOverlays(); onOpenSettings() }, onExit = { activity?.finish() })
            }
        }
        AnimatedNullable(ui.epg.takeIf { ui.overlay == Overlay.EPG }, enter = Motion.fromRight, exit = Motion.toRight) { epg ->
            Box(Modifier.fillMaxSize()) { EpgPanel(vm, epg) }
        }

        ui.archive?.let { ArchiveOverlay(it, ps.paused, vm) }
        AnimatedNullable(
            ui.current.takeIf { ui.overlay == Overlay.NONE && ui.archive == null && ui.bannerVisible },
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = Motion.fromBottom, exit = Motion.toBottom,
        ) { ch -> Box(Modifier.fillMaxWidth()) { Banner(ch, lists.numberOf(ch), ui.now, ui.next, vm.nowMs()) } }

        StatusPill(ps.phase, overPreview = withPanel)
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
                "Вы смотрите запись", null,
                listOf("Вернуться в прямой эфир" to vm::backToLive, "Продолжить запись" to vm::dismissDialog),
            )
            Dialog.ExitApp -> DialogSpec(
                "Выйти из приложения?", null,
                listOf("Отмена" to vm::dismissDialog, "Выйти" to { activity?.finish() }),
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

private data class DialogSpec(val title: String, val message: String?, val buttons: List<Pair<String, () -> Unit>>)

private fun liveKeys(vm: PlaybackViewModel, ui: PlaybackUi, key: Key, down: Boolean, up: Boolean, repeat: Boolean): Boolean = when (key) {
    Key.DirectionUp, Key.ChannelUp -> { if (down) vm.stepChannel(+1, repeat); true }
    Key.DirectionDown, Key.ChannelDown -> { if (down) vm.stepChannel(-1, repeat); true }
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (up) vm.openList(); true }
    Key.DirectionLeft -> { if (down && !repeat) vm.openList(); true }
    Key.DirectionRight -> { if (down && !repeat) ui.current?.let(vm::openEpg); true }
    Key.Info, Key.Menu -> { if (down) vm.showBanner(); true }
    else -> false
}

private fun archiveKeys(vm: PlaybackViewModel, key: Key, down: Boolean, up: Boolean, repeat: Boolean): Boolean = when (key) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> { if (up) vm.togglePause(); true }
    Key.DirectionLeft, Key.MediaRewind -> { if (down) vm.seekPress(-1, false, repeat); true }
    Key.DirectionRight, Key.MediaFastForward -> { if (down) vm.seekPress(+1, false, repeat); true }
    Key.DirectionUp -> { if (down) vm.seekPress(+1, true, repeat); true }
    Key.DirectionDown -> { if (down) vm.seekPress(-1, true, repeat); true }
    else -> false
}

/** Fullscreen ↔ preview by animating a graphics layer: no relayout, no SurfaceView resize jank. */
@Composable
private fun VideoSurface(exo: Player, preview: Boolean) {
    val t by animateFloatAsState(if (preview) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "video")
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
                },
        )
    }
}
