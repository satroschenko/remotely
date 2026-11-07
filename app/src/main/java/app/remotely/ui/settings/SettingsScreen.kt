package app.remotely.ui.settings

import app.remotely.core.tr

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import kotlinx.coroutines.Job
import app.remotely.player.SleepTimer
import kotlinx.coroutines.delay
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.remotely.AppContainer
import app.remotely.BuildConfig
import app.remotely.R
import app.remotely.core.ZalaTime
import app.remotely.data.api.Channel
import app.remotely.ui.common.wheelMovesFocus
import app.remotely.data.api.UserPackage
import app.remotely.data.repo.AccountRepository
import app.remotely.data.repo.GenreGroup
import app.remotely.data.repo.ServerSettings
import app.remotely.data.session.SessionState
import app.remotely.data.store.Settings
import app.remotely.data.update.InstallResult
import app.remotely.data.update.Installer
import app.remotely.data.update.UpdateState
import app.remotely.ui.common.AnimatedNullable
import app.remotely.ui.common.MessageDialog
import app.remotely.ui.common.Motion
import app.remotely.ui.playback.Toast
import app.remotely.ui.common.pointerClick
import app.remotely.ui.theme.FocusStyle
import app.remotely.ui.theme.Palette
import kotlinx.coroutines.launch

private enum class Page { MAIN, CHANNELS, HELP, UPDATES, DIAGNOSTICS, PACKAGES }

@UnstableApi
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    var page by remember { mutableStateOf(Page.MAIN) }
    var openedFrom by remember { mutableStateOf<Page?>(null) }
    BackHandler { if (page == Page.MAIN) onBack() else page = Page.MAIN }
    // On pages without sideways controls ← goes back too, like Back.
    val leftIsBack = page == Page.HELP || page == Page.UPDATES || page == Page.DIAGNOSTICS || page == Page.PACKAGES
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.MAIN
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { if (forward) it / 5 else -it / 5 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { if (forward) -it / 6 else it / 6 })
        },
        label = "settingsPage",
        modifier = Modifier.fillMaxSize().background(Palette.Background).wheelMovesFocus().onPreviewKeyEvent { ev ->
            if (!leftIsBack || ev.key != Key.DirectionLeft) return@onPreviewKeyEvent false
            if (ev.type == KeyEventType.KeyDown) page = Page.MAIN
            true
        },
    ) { p ->
        when (p) {
            Page.MAIN -> MainPage(c, onBack, onLoggedOut, returnFrom = openedFrom) { openedFrom = it; page = it }
            Page.CHANNELS -> ChannelsPage(c)
            Page.HELP -> HelpPage()
            Page.UPDATES -> UpdatesPage(c)
            Page.DIAGNOSTICS -> DiagnosticsPage(c)
            Page.PACKAGES -> PackagesPage(c)
        }
    }
}

/** Page body with the dialog layered on top: inside the Column a dialog gets no height and stays invisible. */
@Composable
private fun PageFrame(dialog: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), content = content)
        dialog()
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(bottom = 16.dp))
}

@Composable
private fun Item(text: String, value: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().pointerClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = FocusStyle.colors(),
        border = FocusStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            if (value != null) Text(value, fontSize = 19.sp, color = Palette.Focus)
        }
    }
}

@UnstableApi
@Composable
private fun MainPage(c: AppContainer, onBack: () -> Unit, onLoggedOut: () -> Unit, returnFrom: Page?, open: (Page) -> Unit) {
    val s by c.settings.settings.collectAsState(Settings())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var login by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmLoad by remember { mutableStateOf(false) }
    var serverBusy by remember { mutableStateOf(false) }
    var serverToast by remember { mutableStateOf<String?>(null) }
    var toastJob by remember { mutableStateOf<Job?>(null) }
    val first = remember { FocusRequester() }
    // The dialog takes the focus; on close it goes back to the server item that opened it.
    val saveItem = remember { FocusRequester() }
    val loadItem = remember { FocusRequester() }
    var serverItem by remember { mutableStateOf(saveItem) }
    // Back from a sub-page the focus returns to the item that opened it.
    val back = remember { FocusRequester() }
    val listState = rememberLazyListState()
    fun Modifier.returnHere(p: Page) = if (p == returnFrom) focusRequester(back) else this
    LaunchedEffect(Unit) {
        login = c.currentLogin() ?: ""
        val index = when (returnFrom) { Page.HELP -> 1; Page.UPDATES -> 2; Page.CHANNELS -> 3; Page.PACKAGES -> 4; Page.DIAGNOSTICS -> 12; else -> null }
        if (index == null) { runCatching { first.requestFocus() }; return@LaunchedEffect }
        listState.scrollToItem((index - 2).coerceAtLeast(0))
        withFrameNanos { }
        if (runCatching { back.requestFocus() }.isFailure) runCatching { first.requestFocus() }
    }

    val update by c.updates.state.collectAsState()
    val newVersion = (update as? UpdateState.Available)?.release?.version

    fun refocusServerItem() = scope.launch { withFrameNanos { }; runCatching { serverItem.requestFocus() } }
    fun closeServerDialog() {
        confirmLoad = false
        refocusServerItem()
    }
    fun runOnServer(item: FocusRequester, action: suspend () -> ServerSettings.Result) {
        if (serverBusy) return
        serverItem = item
        serverBusy = true
        scope.launch {
            val result = action()
            serverBusy = false
            serverToast = serverResultText(result, saving = item == saveItem)
            toastJob?.cancel()
            toastJob = scope.launch { delay(3_000); serverToast = null }
        }
    }

    BackHandler(enabled = confirmLogout) { confirmLogout = false }
    BackHandler(enabled = confirmLoad) { closeServerDialog() }
    PageFrame(dialog = {
        when {
            confirmLogout -> LogoutDialog(c, onLoggedOut) { confirmLogout = false }
            confirmLoad -> MessageDialog(
                tr("Загрузить настройки с сервера?", "Загрузіць налады з сервера?", "Load the settings from the server?"), tr("Избранное, порядок каналов и настройки заменятся сохранёнными", "Абранае, парадак каналаў і налады заменяцца захаванымі", "Favourites, channel order and settings will be replaced with the saved ones"),
                listOf(tr("Загрузить", "Загрузіць", "Load") to { confirmLoad = false; refocusServerItem(); runOnServer(loadItem) { c.serverSettings.load() } }, tr("Отмена", "Скасаваць", "Cancel") to ::closeServerDialog),
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedNullable(serverToast, enter = Motion.fromTop, exit = Motion.toTop) { Toast(it) }
        }
    }) {
        Title(tr("Настройки", "Налады", "Settings"))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Item("← " + tr("Вернуться к просмотру", "Вярнуцца да прагляду", "Back to watching"), modifier = Modifier.focusRequester(first), onClick = onBack) }
            item { Item(tr("Как пользоваться / наш сайт", "Як карыстацца / наш сайт", "How to use / our website"), modifier = Modifier.returnHere(Page.HELP)) { open(Page.HELP) } }
            item { Item(tr("Обновления", "Абнаўленні", "Updates"), newVersion?.let { tr("есть версия $it", "ёсць версія $it", "version $it is out") } ?: tr("версия ${BuildConfig.VERSION_NAME}", "версія ${BuildConfig.VERSION_NAME}", "version ${BuildConfig.VERSION_NAME}"), Modifier.returnHere(Page.UPDATES)) { open(Page.UPDATES) } }
            item { Item(tr("Каналы: скрыть, избранное, порядок", "Каналы: схаваць, абранае, парадак", "Channels: hide, favourites, order"), modifier = Modifier.returnHere(Page.CHANNELS)) { open(Page.CHANNELS) } }
            item { Item(tr("Подписка: пакеты и баланс", "Падпіска: пакеты і баланс", "Subscription: packages and balance"), modifier = Modifier.returnHere(Page.PACKAGES)) { open(Page.PACKAGES) } }
            item {
                // In all three languages, so whoever switched it by mistake finds the way back.
                Item("Язык / Мова / Language", s.language.label) {
                    scope.launch { c.settings.update { it.copy(language = it.language.next()) } }
                }
            }
            item {
                Item(tr("Размер шрифта", "Памер шрыфта", "Text size"), s.fontSize.label) {
                    scope.launch { c.settings.update { it.copy(fontSize = it.fontSize.next()) } }
                }
            }
            item {
                Item(tr("Качество видео", "Якасць відэа", "Video quality"), s.quality.label) {
                    scope.launch { c.settings.update { it.copy(quality = it.quality.next()) } }
                }
            }
            item {
                var changes by remember { mutableStateOf(0) }
                val left = remember(changes) { c.sleep.label(c.clock.nowMs()) }
                Item(tr("Таймер сна", "Таймер сну", "Sleep timer"), left) {
                    c.sleep.cycle(c.clock.nowMs())
                    changes++
                }
            }
            item {
                Item(tr("В перерыв вещания выключать", "У перапынак вяшчання выключаць", "Turn off in a broadcast break"), if (s.breakStopMinutes > 0) tr("через ${s.breakStopMinutes} мин", "праз ${s.breakStopMinutes} хв", "after ${s.breakStopMinutes} min") else yesNo(false)) {
                    scope.launch {
                        c.settings.update {
                            val all = SleepTimer.BREAK_CHOICES
                            it.copy(breakStopMinutes = all[(all.indexOf(it.breakStopMinutes) + 1) % all.size])
                        }
                    }
                }
            }
            item {
                Item(tr("Субтитры (где есть)", "Субтытры (дзе ёсць)", "Subtitles (where available)"), yesNo(s.subtitles)) {
                    scope.launch { c.settings.update { it.copy(subtitles = !it.subtitles) } }
                }
            }
            item {
                Item(tr("Сообщения оператора", "Паведамленні аператара", "Operator announcements"), yesNo(s.operatorMessages)) {
                    // Turning them on again shows the current message again from the start.
                    scope.launch { c.settings.update { it.copy(operatorMessages = !it.operatorMessages, operatorMessageKey = null, operatorMessageShows = 0) } }
                }
            }
            item {
                Item(tr("Цвет жанров", "Колер жанраў", "Genre colours"), yesNo(s.genreColors)) {
                    scope.launch { c.settings.update { it.copy(genreColors = !it.genreColors) } }
                }
            }
            item {
                Item(tr("Запускать при включении приставки", "Запускаць пры ўключэнні прыстаўкі", "Start when the box turns on"), yesNo(s.autostart)) {
                    val enable = !s.autostart
                    scope.launch { c.settings.update { it.copy(autostart = enable) } }
                    // Android 10+ needs "display over other apps" to start from boot.
                    if (enable && !AndroidSettings.canDrawOverlays(ctx)) {
                        runCatching {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }
            item {
                Item(tr("Сохранить настройки на сервер", "Захаваць налады на сервер", "Save settings to the server"), if (serverBusy && serverItem == saveItem) "…" else null, Modifier.focusRequester(saveItem)) {
                    runOnServer(saveItem) { c.serverSettings.save() }
                }
            }
            item {
                Item(tr("Загрузить настройки с сервера", "Загрузіць налады з сервера", "Load settings from the server"), if (serverBusy && serverItem == loadItem) "…" else null, Modifier.focusRequester(loadItem)) {
                    if (!serverBusy) { serverItem = loadItem; confirmLoad = true }
                }
            }
            item { Item(tr("Диагностика", "Дыягностыка", "Diagnostics"), modifier = Modifier.returnHere(Page.DIAGNOSTICS)) { open(Page.DIAGNOSTICS) } }
            item { Item(tr("Аккаунт", "Акаўнт", "Account"), login) {} }
            item { Item(tr("Выйти из аккаунта", "Выйсці з акаўнта", "Sign out")) { confirmLogout = true } }
        }
    }
}

private fun serverResultText(r: ServerSettings.Result, saving: Boolean): String = when (r) {
    ServerSettings.Result.Saved -> tr("Настройки сохранены на сервер", "Налады захаваны на сервер", "Settings saved to the server")
    is ServerSettings.Result.Loaded -> tr("Настройки загружены с сервера", "Налады загружаны з сервера", "Settings loaded from the server")
    ServerSettings.Result.Empty -> tr("Не удалось загрузить: на сервере нет сохранённых настроек", "Не ўдалося загрузіць: на серверы няма захаваных налад", "Could not load: nothing is saved on the server")
    ServerSettings.Result.Unreadable -> tr("Не удалось загрузить: настройки на сервере повреждены", "Не ўдалося загрузіць: налады на серверы пашкоджаны", "Could not load: the saved settings are damaged")
    ServerSettings.Result.Failed -> if (saving) tr("Не удалось сохранить настройки", "Не ўдалося захаваць налады", "Could not save the settings") else tr("Не удалось загрузить настройки", "Не ўдалося загрузіць налады", "Could not load the settings")
}

@UnstableApi
@Composable
private fun LogoutDialog(c: AppContainer, onLoggedOut: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    MessageDialog(
        tr("Выйти из аккаунта?", "Выйсці з акаўнта?", "Sign out?"), tr("Потребуется снова ввести логин и пароль", "Трэба будзе зноў увесці лагін і пароль", "You will need to enter the login and password again"),
        listOf(tr("Отмена", "Скасаваць", "Cancel") to close, tr("Выйти", "Выйсці", "Sign out") to {
            close()
            scope.launch {
                c.player.stop()
                c.session.logout()
                onLoggedOut()
            }
        }),
    )
}

@UnstableApi
@Composable
private fun ChannelsPage(c: AppContainer) {
    val scope = rememberCoroutineScope()
    val lists by c.channels.lists.collectAsState(null)
    val s by c.settings.settings.collectAsState(Settings())
    val order = remember { mutableStateListOf<Channel>() }
    val listState = rememberLazyListState()
    var moving by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Channel?>(null) }
    // The row that gets focus back after a dialog closes or a move ends.
    var anchor by remember { mutableStateOf<String?>(null) }
    val anchorFocus = remember { FocusRequester() }

    LaunchedEffect(lists) {
        val l = lists ?: return@LaunchedEffect
        if (moving == null) {
            order.clear()
            order.addAll(l.manageable)
        }
    }
    LaunchedEffect(order.isNotEmpty(), menuFor) {
        if (order.isNotEmpty() && menuFor == null) runCatching { anchorFocus.requestFocus() }
    }

    fun finishMove() {
        val codes = order.map { it.code }
        moving = null
        scope.launch { c.settings.update { it.copy(order = codes) } }
    }
    BackHandler(enabled = moving != null) { finishMove() }
    BackHandler(enabled = menuFor != null) { menuFor = null }

    PageFrame(dialog = {
        menuFor?.let { ch ->
            val fav = ch.code in s.favorites
            val hidden = ch.code in s.hidden
            MessageDialog(
                ch.name, null,
                listOf(
                    (if (fav) tr("Убрать из избранного", "Прыбраць з абранага", "Remove from favourites") else tr("★ В избранное", "★ У абранае", "★ Add to favourites")) to {
                        menuFor = null
                        scope.launch { c.settings.update { if (fav) it.copy(favorites = it.favorites - ch.code) else it.copy(favorites = it.favorites + ch.code) } }
                    },
                    (if (hidden) tr("Показать", "Паказаць", "Show") else tr("Скрыть", "Схаваць", "Hide")) to {
                        menuFor = null
                        scope.launch { c.settings.update { if (hidden) it.copy(hidden = it.hidden - ch.code) else it.copy(hidden = it.hidden + ch.code) } }
                    },
                    tr("Переместить", "Перамясціць", "Move") to { menuFor = null; moving = ch.code },
                    tr("Отмена", "Скасаваць", "Cancel") to { menuFor = null },
                ),
            )
        }
    }) {
        Title(tr("Каналы", "Каналы", "Channels"))
        Text(
            if (moving != null) tr("Перемещение: ▲ ▼ — двигать канал, OK или Назад — готово", "Перамяшчэнне: ▲ ▼ — рухаць канал, OK або Назад — гатова", "Moving: ▲ ▼ — move the channel, OK or Back — done") else tr("OK на канале — избранное, скрыть, переместить", "OK на канале — абранае, схаваць, перамясціць", "OK on a channel — favourite, hide, move"),
            fontSize = 18.sp, color = if (moving != null) Palette.Focus else Palette.TextDim, modifier = Modifier.padding(bottom = 12.dp),
        )
        // Same numbering as the playback list: position among visible channels, updated live while moving.
        val numbers = order.filter { it.code !in s.hidden }.withIndex().associate { it.value.code to it.index + 1 }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
            modifier = Modifier.onPreviewKeyEvent { ev ->
                val code = moving ?: return@onPreviewKeyEvent false
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent ev.key == Key.DirectionUp || ev.key == Key.DirectionDown
                val i = order.indexOfFirst { it.code == code }
                val to = when (ev.key) {
                    Key.DirectionUp -> i - 1
                    Key.DirectionDown -> i + 1
                    else -> return@onPreviewKeyEvent false
                }
                if (i >= 0 && to in order.indices) {
                    order.add(to, order.removeAt(i))
                    // Moving a row does not scroll by itself: keep it on screen.
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.isNotEmpty() && (to <= visible.first().index || to >= visible.last().index)) {
                        scope.launch { listState.animateScrollToItem((to - 3).coerceAtLeast(0)) }
                    }
                }
                true
            },
        ) {
            itemsIndexed(order, key = { _, ch -> ch.code }) { i, ch ->
                val hidden = ch.code in s.hidden
                val fav = ch.code in s.favorites
                val tags = listOfNotNull("★".takeIf { fav }, tr("скрыт", "схаваны", "hidden").takeIf { hidden }, ("↕ " + tr("двигается", "рухаецца", "moving")).takeIf { moving == ch.code }).joinToString("  ")
                val isAnchor = ch.code == anchor || (anchor == null && i == 0)
                Item(
                    "${numbers[ch.code]?.let { "$it. " } ?: ""}${ch.name}", tags,
                    modifier = if (isAnchor) Modifier.focusRequester(anchorFocus) else Modifier,
                ) {
                    anchor = ch.code
                    if (moving != null) finishMove() else menuFor = ch
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun UpdatesPage(c: AppContainer) {
    val s by c.settings.settings.collectAsState(Settings())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val update by c.updates.state.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // Updates: the download starts on demand and the system installer opens once it is done.
    var installAsked by remember { mutableStateOf(false) }
    var installNote by remember { mutableStateOf<String?>(null) }
    var awaitingPermission by remember { mutableStateOf(false) }
    fun install(apk: java.io.File) {
        val r = Installer.install(ctx, apk)
        awaitingPermission = r == InstallResult.NEEDS_PERMISSION
        installNote = when (r) {
            InstallResult.STARTED -> null
            InstallResult.NEEDS_PERMISSION -> tr("Разрешите установку и нажмите ещё раз", "Дазвольце ўсталёўку і націсніце яшчэ раз", "Allow installing and press again")
            InstallResult.FAILED -> tr("Не удалось открыть установщик", "Не ўдалося адкрыць усталёўшчык", "Could not open the installer")
        }
    }
    // Back from the "install unknown apps" switch: carry on if it was turned on.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            val u = c.updates.state.value
            if (e == Lifecycle.Event.ON_RESUME && awaitingPermission && u is UpdateState.Downloaded && Installer.canInstall(ctx)) install(u.apk)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(update) {
        val u = update
        if (u is UpdateState.Downloaded && installAsked) { installAsked = false; install(u.apk) }
    }
    val offer = when (val u = update) {
        is UpdateState.Available -> u.release
        is UpdateState.Downloading -> u.release
        is UpdateState.Downloaded -> u.release
        is UpdateState.DownloadFailed -> u.release
        else -> null
    }
    val updateHint = when (val u = update) {
        is UpdateState.Downloading -> tr("Загрузка ${u.percent}%", "Загрузка ${u.percent}%", "Downloading ${u.percent}%")
        is UpdateState.DownloadFailed -> tr("Не удалось скачать, ещё раз", "Не ўдалося спампаваць, яшчэ раз", "Download failed, try again")
        is UpdateState.Downloaded -> installNote ?: tr("Установить", "Усталяваць", "Install")
        else -> installNote ?: tr("Скачать и установить", "Спампаваць і ўсталяваць", "Download and install")
    }
    val checkLabel = when (update) {
        UpdateState.Checking -> tr("Проверяю…", "Правяраю…", "Checking…")
        UpdateState.UpToDate -> tr("Обновлений нет", "Абнаўленняў няма", "Up to date")
        UpdateState.CheckFailed -> tr("Нет связи, ещё раз", "Няма сувязі, яшчэ раз", "No connection, try again")
        UpdateState.Idle -> null
        else -> tr("Есть новая версия", "Ёсць новая версія", "A new version is out")
    }
    fun onUpdate() {
        when (val u = update) {
            is UpdateState.Downloaded -> install(u.apk)
            is UpdateState.Downloading -> {}
            else -> { installAsked = true; c.updates.download(c.appScope) }
        }
    }

    PageFrame {
        Title(tr("Обновления", "Абнаўленні", "Updates"))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "installed") { Item(tr("Установлена версия", "Усталявана версія", "Installed version"), BuildConfig.VERSION_NAME) {} }
            if (offer != null) item(key = "update") { Item(tr("Обновить до версии ${offer.version}", "Абнавіць да версіі ${offer.version}", "Update to version ${offer.version}"), updateHint, Modifier.focusRequester(first)) { onUpdate() } }
            if (offer != null && offer.notes.isNotEmpty()) item(key = "notes") {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Что нового в ${offer.version}", "Што новага ў ${offer.version}", "What is new in ${offer.version}"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    offer.notes.forEach {
                        // Wrapped lines line up with the text, not with the bullet.
                        Row {
                            Text("•", fontSize = 16.sp, color = Palette.TextDim, modifier = Modifier.width(18.dp))
                            Text(it, fontSize = 16.sp, color = Palette.TextDim)
                        }
                    }
                }
            }
            item(key = "check") {
                Item(tr("Проверить сейчас", "Праверыць зараз", "Check now"), checkLabel, if (offer == null) Modifier.focusRequester(first) else Modifier) {
                    // A version found by hand needs no toast later.
                    scope.launch { c.updates.check(c.appScope)?.join(); (c.updates.state.value as? UpdateState.Available)?.let { a -> c.settings.update { it.copy(announcedUpdate = a.release.version) } } }
                }
            }
            item(key = "auto") {
                Item(tr("Проверять автоматически", "Правяраць аўтаматычна", "Check automatically"), yesNo(s.autoCheckUpdates)) {
                    val on = !s.autoCheckUpdates
                    scope.launch {
                        c.settings.update { it.copy(autoCheckUpdates = on) }
                        if (on) c.updates.start(c.appScope)
                    }
                }
            }
            item(key = "note") {
                Text(
                    if (s.autoCheckUpdates) tr("Приложение проверяет новую версию при запуске и раз в сутки и коротко сообщает о ней. Устанавливается обновление только после вашего подтверждения.", "Праграма правярае новую версію пры запуску і раз на суткі і коратка паведамляе пра яе. Усталёўваецца абнаўленне толькі пасля вашага пацверджання.", "The app looks for a new version at start and once a day and tells you briefly. An update is installed only after you confirm.")
                    else tr("Автоматическая проверка выключена: о новой версии приложение не сообщает. Проверить можно кнопкой «Проверить сейчас».", "Аўтаматычная праверка выключана: пра новую версію праграма не паведамляе. Праверыць можна кнопкай «Праверыць зараз».", "Automatic check is off: the app does not tell about new versions. Use «Check now»."),
                    fontSize = 16.sp, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** [swatches]: a colour per row, drawn as the stripe the programmes get (the genre legend); the names stay white, coloured text smears on TVs. */
private class HelpSection(val title: String, val rows: List<Pair<String, String>>, val swatches: List<Long>? = null)

/** The genre colours in rainbow order, then pink and grey. Only the legend: [GenreGroup]'s own order decides ties. */
private val legendOrder = listOf(
    GenreGroup.MOVIES, GenreGroup.SPORT, GenreGroup.KIDS, GenreGroup.LEARNING,
    GenreGroup.NEWS, GenreGroup.MUSIC, GenreGroup.SHOWS, GenreGroup.OTHER,
)

/** Only what a viewer needs: which key does what. */
private fun helpSections() = listOf(
    HelpSection(tr("Просмотр", "Прагляд", "Watching"), listOf(
        "▲ ▼" to tr("следующий / предыдущий канал", "наступны / папярэдні канал", "next / previous channel"),
        "OK" to tr("описание передачи, ещё раз — список каналов", "апісанне перадачы, яшчэ раз — спіс каналаў", "programme description, again — channel list"),
        "◀" to tr("список каналов", "спіс каналаў", "channel list"),
        "▶" to tr("программа передач", "праграма перадач", "TV guide"),
        tr("Назад", "Назад", "Back") to tr("список каналов, ещё раз — меню", "спіс каналаў, яшчэ раз — меню", "channel list, again — menu"),
        tr("Цифры", "Лічбы", "Digits") to tr("набрать номер канала, как в списке", "набраць нумар канала, як у спісе", "dial a channel number as in the list"),
    )),
    HelpSection(
        tr("Цвет жанров (Настройки → «Цвет жанров»)", "Колер жанраў (Налады → «Колер жанраў»)", "Genre colours (Settings → «Genre colours»)"),
        legendOrder.map { it.colour to it.label },
        swatches = legendOrder.map { it.argb },
    ),
    HelpSection(tr("Субтитры и звук", "Субтытры і гук", "Subtitles and sound"), listOf(
        "SUB" to tr("у канала есть субтитры: жёлтая плашка — включены, перечёркнутая — выключены", "у канала ёсць субтытры: жоўтая плашка — уключаны, перакрэсленая — выключаны", "the channel has subtitles: yellow — on, crossed out — off"),
        tr("OK, затем ▲", "OK, потым ▲", "OK, then ▲") to tr("включить или выключить субтитры (в описании передачи, в архиве — на паузе)", "уключыць або выключыць субтытры (у апісанні перадачы, у архіве — на паўзе)", "subtitles on or off (in the description; in the archive — when paused)"),
        tr("Нажать на SUB", "Націснуць на SUB", "Tap SUB") to tr("то же на телефоне; или кнопка субтитров на пульте", "тое ж на тэлефоне; або кнопка субтытраў на пульце", "the same on a phone; or the subtitles key on the remote"),
        tr("Русский / Английский", "Руская / Англійская", "Russian / English") to tr("звуковые дорожки канала, играющая выделена", "гукавыя дарожкі канала, тая, што гучыць, вылучана", "sound tracks of the channel, the playing one highlighted"),
        tr("OK, затем ▼", "OK, потым ▼", "OK, then ▼") to tr("сменить звук (в описании передачи, в архиве — на паузе); выбор запоминается", "змяніць гук (у апісанні перадачы, у архіве — на паўзе); выбар запамінаецца", "change the sound (in the description; in the archive — when paused); the choice is remembered"),
        tr("Нажать на язык", "Націснуць на мову", "Tap the language") to tr("сменить звук на телефоне", "змяніць гук на тэлефоне", "change the sound on a phone"),
        tr("Кнопка «Аудио»", "Кнопка «Аўдыя»", "«Audio» key") to tr("сменить звук, если такая есть на пульте", "змяніць гук, калі такая ёсць на пульце", "change the sound, if the remote has one"),
    )),
    HelpSection(tr("Список каналов и меню", "Спіс каналаў і меню", "Channel list and menu"), listOf(
        "OK" to tr("смотреть канал", "глядзець канал", "watch the channel"),
        tr("Удерживать OK", "Утрымліваць OK", "Hold OK") to tr("добавить в избранное или убрать", "дадаць у абранае або прыбраць", "add to favourites or remove"),
        "◀" to tr("меню: все каналы, избранное, категории, настройки", "меню: усе каналы, абранае, катэгорыі, налады", "menu: all channels, favourites, categories, settings"),
        tr("Выход", "Выхад", "Exit") to tr("Меню → «Выход»", "Меню → «Выхад»", "Menu → «Exit»"),
    )),
    HelpSection(tr("Архив", "Архіў", "Archive"), listOf(
        tr("Как открыть", "Як адкрыць", "How to open") to tr("▶ на канале, выбрать прошедшую передачу с пометкой «Архив», OK", "▶ на канале, выбраць мінулую перадачу з пазнакай «Архіў», OK", "▶ on a channel, pick a past programme marked «Archive», OK"),
        "OK" to tr("пауза; на паузе видно описание передачи", "паўза; на паўзе відаць апісанне перадачы", "pause; when paused the description shows"),
        "◀ ▶" to tr("перемотка на 10 секунд (удерживать — быстрее, до 5 минут)", "перамотка на 10 секунд (утрымліваць — хутчэй, да 5 хвілін)", "seek 10 seconds (hold — faster, up to 5 minutes)"),
        "▲ ▼" to tr("следующая / предыдущая передача", "наступная / папярэдняя перадача", "next / previous programme"),
        tr("На паузе ▲ / ▼", "На паўзе ▲ / ▼", "Paused ▲ / ▼") to tr("включить или выключить субтитры / сменить звук", "уключыць або выключыць субтытры / змяніць гук", "subtitles on or off / change the sound"),
        tr("Назад", "Назад", "Back") to tr("вернуться в прямой эфир", "вярнуцца ў прамы эфір", "back to live TV"),
    )),
    HelpSection(tr("Телефон и планшет", "Тэлефон і планшэт", "Phone and tablet"), listOf(
        tr("Касание видео", "Дотык да відэа", "Tap the video") to tr("как OK", "як OK", "like OK"),
        tr("Свайп вверх / вниз", "Свайп уверх / уніз", "Swipe up / down") to tr("следующий / предыдущий канал, в архиве — передача", "наступны / папярэдні канал, у архіве — перадача", "next / previous channel, in the archive — programme"),
        tr("Долгое нажатие", "Доўгае націсканне", "Long press") to tr("на канале — избранное", "на канале — абранае", "on a channel — favourite"),
        tr("Полоса внизу", "Паласа ўнізе", "Bar at the bottom") to tr("в архиве: нажмите или перетащите — перемотка", "у архіве: націсніце або перацягніце — перамотка", "in the archive: tap or drag to seek"),
        tr("Назад", "Назад", "Back") to tr("как на пульте", "як на пульце", "as on the remote"),
    )),
    HelpSection(tr("Мышь и клавиатура", "Мыш і клавіятура", "Mouse and keyboard"), listOf(
        tr("Левая кнопка", "Левая кнопка", "Left button") to tr("как OK", "як OK", "like OK"),
        tr("Правая кнопка", "Правая кнопка", "Right button") to tr("назад", "назад", "back"),
        tr("Колёсико", "Колца", "Wheel") to tr("переключение каналов, в архиве — перемотка", "пераключэнне каналаў, у архіве — перамотка", "switch channels, in the archive — seek"),
        tr("Пробел", "Прабел", "Space") to tr("как OK", "як OK", "like OK"),
    )),
)

@Composable
private fun HelpPage() {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    PageFrame {
        Title(tr("Как пользоваться / наш сайт", "Як карыстацца / наш сайт", "How to use / our website"))
        // Each section takes the focus in turn, so the remote scrolls the page.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(helpSections()) { i, sec ->
                Surface(
                    onClick = {},
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    colors = FocusStyle.colors(),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sec.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(bottom = 2.dp))
                        sec.rows.forEachIndexed { r, (key, what) ->
                            val swatch = sec.swatches?.getOrNull(r)?.let { Color(it) }
                            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                                if (swatch != null) {
                                    Box(Modifier.fillMaxHeight().padding(end = 10.dp).width(6.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
                                }
                                Text(key, fontSize = 17.sp, color = if (swatch != null) Palette.Text else Palette.Focus, modifier = Modifier.width(if (swatch != null) 186.dp else 200.dp))
                                Text(what, fontSize = 17.sp, color = Palette.Text)
                            }
                        }
                    }
                }
            }
            item(key = "about") { AboutCard() }
        }
    }
}

/** Authorship and where to write. OK or a tap opens the page; a TV without a browser has the QR code for the phone camera. */
private const val PROJECT_URL = "https://github.com/satroschenko/remotely"
private const val TV_BROWSER_STUB = "com.android.tv.frameworkpackagestubs"

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    var noBrowser by remember { mutableStateOf(false) }
    val open = {
        // Without a browser Android shows only its own English "no app" note: ask first.
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android TV answers links with a stub that only says "no app": it does not count as a browser.
        val browsers = ctx.packageManager.queryIntentActivities(view, 0).filter { it.activityInfo.packageName != TV_BROWSER_STUB }
        noBrowser = browsers.isEmpty() || runCatching { ctx.startActivity(view) }.isFailure
    }
    Surface(
        onClick = open,
        modifier = Modifier.fillMaxWidth().pointerClick(onClick = open),
        colors = FocusStyle.colors(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("О приложении", "Пра праграму", "About"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(bottom = 2.dp))
                Text("Remotely ${BuildConfig.VERSION_NAME} — " + tr("бесплатное приложение с открытым кодом.", "бясплатная праграма з адкрытым кодам.", "a free open-source app."), fontSize = 17.sp, color = Palette.Text)
                Text(tr("Автор", "Аўтар", "Author") + ": satroschenko", fontSize = 17.sp, color = Palette.Text)
                Text(tr("Сайт проекта, вопросы и пожелания:", "Сайт праекта, пытанні і пажаданні:", "Project site, questions and wishes:"), fontSize = 17.sp, color = Palette.Text)
                Text("github.com/satroschenko/remotely", fontSize = 17.sp, color = Palette.Focus)
                Text(
                    if (noBrowser) tr("Браузера нет — наведите камеру телефона на код справа.", "Браўзера няма — навядзіце камеру тэлефона на код справа.", "No browser — point a phone camera at the code on the right.") else tr("Нажмите, чтобы открыть, или наведите камеру телефона на код справа.", "Націсніце, каб адкрыць, або навядзіце камеру тэлефона на код справа.", "Press to open, or point a phone camera at the code on the right."),
                    fontSize = 15.sp, color = if (noBrowser) Palette.Focus else Palette.TextDim,
                )
            }
            Spacer(Modifier.width(20.dp))
            Image(painterResource(R.drawable.qr_github), tr("QR-код ссылки на проект", "QR-код спасылкі на праект", "QR code of the project link"), Modifier.size(132.dp))
        }
    }
}

@UnstableApi
@Composable
private fun DiagnosticsPage(c: AppContainer) {
    val events by c.log.events.collectAsState()
    val net by c.network.available.collectAsState()
    val session by c.session.state.collectAsState()
    val ps by c.player.state.collectAsState()
    val now = c.clock.nowMs()
    val sessionText = when (val st = session) {
        is SessionState.Active -> tr("активна, токен до ${ZalaTime.relativeLabel(st.expiresAtMs, now)}", "актыўная, токен да ${ZalaTime.relativeLabel(st.expiresAtMs, now)}", "active, token until ${ZalaTime.relativeLabel(st.expiresAtMs, now)}")
        SessionState.Connecting -> tr("подключение", "падключэнне", "connecting")
        SessionState.BadCredentials -> tr("неверный логин/пароль", "няправільны лагін/пароль", "wrong login/password")
        SessionState.LoggedOut -> tr("нет", "няма", "none")
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    PageFrame {
        Title(tr("Диагностика", "Дыягностыка", "Diagnostics"))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            Text(tr("Сеть", "Сетка", "Network") + ": " + (if (net) tr("есть", "ёсць", "up") else tr("нет", "няма", "down")), fontSize = 18.sp, color = Palette.Text)
            Text(tr("Сессия", "Сесія", "Session") + ": $sessionText", fontSize = 18.sp, color = Palette.Text)
            Text(tr("Плеер", "Плэер", "Player") + ": ${ps.phase}  ${ps.target?.let { it.channel.number.toString() + ". " + it.channel.name } ?: ""}", fontSize = 18.sp, color = Palette.Text)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            items(events.withIndex().toList(), key = { it.index }) { (i, e) ->
                Surface(
                    onClick = {},
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    colors = FocusStyle.colors(),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(ZalaTime.relativeLabel(e.timeMs, now), fontSize = 15.sp, color = Palette.TextDim, modifier = Modifier.width(150.dp))
                        Text(e.text, fontSize = 15.sp, color = Palette.Text)
                    }
                }
            }
        }
    }
}

/** Настройки → «Подписка»: the packages and the balance, asked for when the page opens. */
@Composable
private fun PackagesPage(c: AppContainer) {
    val s by c.settings.settings.collectAsState(Settings())
    val scope = rememberCoroutineScope()
    val balance by c.account.balance.collectAsState()
    var packages by remember { mutableStateOf<List<UserPackage>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LaunchedEffect(Unit) {
        launch { c.account.refreshBalance(force = true) }
        val p = c.account.packages()
        if (p == null) failed = true else packages = p
    }
    PageFrame {
        Title(tr("Подписка", "Падпіска", "Subscription"))
        Text(
            tr("Баланс", "Баланс", "Balance") + ": " + (balance?.let(AccountRepository::roubles) ?: "—"),
            fontSize = 22.sp, color = Palette.Text, modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Item(tr("Показывать баланс в меню", "Паказваць баланс у меню", "Show the balance in the menu"), yesNo(s.showBalance), Modifier.focusRequester(first)) {
                    scope.launch { c.settings.update { it.copy(showBalance = !it.showBalance) } }
                }
            }
            item {
                Text(tr("Пакеты", "Пакеты", "Packages"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(top = 12.dp))
            }
            val list = packages
            when {
                failed -> item { Text(tr("Не удалось узнать пакеты. Попробуйте позже", "Не ўдалося даведацца пакеты. Паспрабуйце пазней", "Could not get the packages. Try again later"), fontSize = 20.sp, color = Palette.TextDim) }
                list == null -> item { Text(tr("Загрузка…", "Загрузка…", "Loading…"), fontSize = 20.sp, color = Palette.TextDim) }
                list.isEmpty() -> item { Text(tr("Пакетов нет", "Пакетаў няма", "No packages"), fontSize = 20.sp, color = Palette.TextDim) }
                else -> items(list.withIndex().toList(), key = { it.index }) { (_, p) ->
                    val term = p.untilMs?.let { tr("до ${ZalaTime.ddmmyyyy(it)}", "да ${ZalaTime.ddmmyyyy(it)}", "until ${ZalaTime.ddmmyyyy(it)}") }
                        ?: tr("без срока", "без тэрміну", "no end date")
                    val price = p.price?.let { ", " + AccountRepository.roubles(it) }.orEmpty()
                    Item(p.name, term + price) {}
                }
            }
        }
    }
}

private fun yesNo(on: Boolean) = if (on) tr("Да", "Так", "Yes") else tr("Нет", "Не", "No")
