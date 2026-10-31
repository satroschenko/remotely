package app.remotely.ui.settings

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
import kotlinx.coroutines.delay
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.remotely.AppContainer
import app.remotely.BuildConfig
import app.remotely.R
import app.remotely.core.ZalaTime
import app.remotely.data.api.Channel
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

private enum class Page { MAIN, CHANNELS, HELP, UPDATES, DIAGNOSTICS }

@UnstableApi
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    var page by remember { mutableStateOf(Page.MAIN) }
    var openedFrom by remember { mutableStateOf<Page?>(null) }
    BackHandler { if (page == Page.MAIN) onBack() else page = Page.MAIN }
    // On pages without sideways controls ← goes back too, like Back.
    val leftIsBack = page == Page.HELP || page == Page.UPDATES || page == Page.DIAGNOSTICS
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.MAIN
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { if (forward) it / 5 else -it / 5 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { if (forward) -it / 6 else it / 6 })
        },
        label = "settingsPage",
        modifier = Modifier.fillMaxSize().background(Palette.Background).onPreviewKeyEvent { ev ->
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
        val index = when (returnFrom) { Page.HELP -> 1; Page.UPDATES -> 2; Page.CHANNELS -> 3; Page.DIAGNOSTICS -> 10; else -> null }
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
                "Загрузить настройки с сервера?", "Избранное, порядок каналов и настройки заменятся сохранёнными",
                listOf("Загрузить" to { confirmLoad = false; refocusServerItem(); runOnServer(loadItem) { c.serverSettings.load() } }, "Отмена" to ::closeServerDialog),
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedNullable(serverToast, enter = Motion.fromTop, exit = Motion.toTop) { Toast(it) }
        }
    }) {
        Title("Настройки")
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Item("← Вернуться к просмотру", modifier = Modifier.focusRequester(first), onClick = onBack) }
            item { Item("Как пользоваться / наш сайт", modifier = Modifier.returnHere(Page.HELP)) { open(Page.HELP) } }
            item { Item("Обновления", newVersion?.let { "есть версия $it" } ?: "версия ${BuildConfig.VERSION_NAME}", Modifier.returnHere(Page.UPDATES)) { open(Page.UPDATES) } }
            item { Item("Каналы: скрыть, избранное, порядок", modifier = Modifier.returnHere(Page.CHANNELS)) { open(Page.CHANNELS) } }
            item {
                Item("Размер шрифта", s.fontSize.label) {
                    scope.launch { c.settings.update { it.copy(fontSize = it.fontSize.next()) } }
                }
            }
            item {
                Item("Качество видео", s.quality.label) {
                    scope.launch { c.settings.update { it.copy(quality = it.quality.next()) } }
                }
            }
            item {
                Item("Субтитры (где есть)", if (s.subtitles) "Да" else "Нет") {
                    scope.launch { c.settings.update { it.copy(subtitles = !it.subtitles) } }
                }
            }
            item {
                Item("Запускать при включении приставки", if (s.autostart) "Да" else "Нет") {
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
                Item("Сохранить настройки на сервер", if (serverBusy && serverItem == saveItem) "…" else null, Modifier.focusRequester(saveItem)) {
                    runOnServer(saveItem) { c.serverSettings.save() }
                }
            }
            item {
                Item("Загрузить настройки с сервера", if (serverBusy && serverItem == loadItem) "…" else null, Modifier.focusRequester(loadItem)) {
                    if (!serverBusy) { serverItem = loadItem; confirmLoad = true }
                }
            }
            item { Item("Диагностика", modifier = Modifier.returnHere(Page.DIAGNOSTICS)) { open(Page.DIAGNOSTICS) } }
            item { Item("Аккаунт", login) {} }
            item { Item("Выйти из аккаунта") { confirmLogout = true } }
        }
    }
}

private fun serverResultText(r: ServerSettings.Result, saving: Boolean): String = when (r) {
    ServerSettings.Result.Saved -> "Настройки сохранены на сервер"
    is ServerSettings.Result.Loaded -> "Настройки загружены с сервера"
    ServerSettings.Result.Empty -> "Не удалось загрузить: на сервере нет сохранённых настроек"
    ServerSettings.Result.Unreadable -> "Не удалось загрузить: настройки на сервере повреждены"
    ServerSettings.Result.Failed -> if (saving) "Не удалось сохранить настройки" else "Не удалось загрузить настройки"
}

@UnstableApi
@Composable
private fun LogoutDialog(c: AppContainer, onLoggedOut: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    MessageDialog(
        "Выйти из аккаунта?", "Потребуется снова ввести логин и пароль",
        listOf("Отмена" to close, "Выйти" to {
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
                    (if (fav) "Убрать из избранного" else "★ В избранное") to {
                        menuFor = null
                        scope.launch { c.settings.update { if (fav) it.copy(favorites = it.favorites - ch.code) else it.copy(favorites = it.favorites + ch.code) } }
                    },
                    (if (hidden) "Показать" else "Скрыть") to {
                        menuFor = null
                        scope.launch { c.settings.update { if (hidden) it.copy(hidden = it.hidden - ch.code) else it.copy(hidden = it.hidden + ch.code) } }
                    },
                    "Переместить" to { menuFor = null; moving = ch.code },
                    "Отмена" to { menuFor = null },
                ),
            )
        }
    }) {
        Title("Каналы")
        Text(
            if (moving != null) "Перемещение: ↑ ↓ — двигать канал, OK или Назад — готово" else "OK на канале — избранное, скрыть, переместить",
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
                val tags = listOfNotNull("★".takeIf { fav }, "скрыт".takeIf { hidden }, "↕ двигается".takeIf { moving == ch.code }).joinToString("  ")
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
            InstallResult.NEEDS_PERMISSION -> "Разрешите установку и нажмите ещё раз"
            InstallResult.FAILED -> "Не удалось открыть установщик"
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
        is UpdateState.Downloading -> "Загрузка ${u.percent}%"
        is UpdateState.DownloadFailed -> "Не удалось скачать, ещё раз"
        is UpdateState.Downloaded -> installNote ?: "Установить"
        else -> installNote ?: "Скачать и установить"
    }
    val checkLabel = when (update) {
        UpdateState.Checking -> "Проверяю…"
        UpdateState.UpToDate -> "Обновлений нет"
        UpdateState.CheckFailed -> "Нет связи, ещё раз"
        UpdateState.Idle -> null
        else -> "Есть новая версия"
    }
    fun onUpdate() {
        when (val u = update) {
            is UpdateState.Downloaded -> install(u.apk)
            is UpdateState.Downloading -> {}
            else -> { installAsked = true; c.updates.download(c.appScope) }
        }
    }

    PageFrame {
        Title("Обновления")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "installed") { Item("Установлена версия", BuildConfig.VERSION_NAME) {} }
            if (offer != null) item(key = "update") { Item("Обновить до версии ${offer.version}", updateHint, Modifier.focusRequester(first)) { onUpdate() } }
            if (offer != null && offer.notes.isNotEmpty()) item(key = "notes") {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Что нового в ${offer.version}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
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
                Item("Проверить сейчас", checkLabel, if (offer == null) Modifier.focusRequester(first) else Modifier) {
                    // A version found by hand needs no toast later.
                    scope.launch { c.updates.check(c.appScope)?.join(); (c.updates.state.value as? UpdateState.Available)?.let { a -> c.settings.update { it.copy(announcedUpdate = a.release.version) } } }
                }
            }
            item(key = "auto") {
                Item("Проверять автоматически", if (s.autoCheckUpdates) "Да" else "Нет") {
                    val on = !s.autoCheckUpdates
                    scope.launch {
                        c.settings.update { it.copy(autoCheckUpdates = on) }
                        if (on) c.updates.start(c.appScope)
                    }
                }
            }
            item(key = "note") {
                Text(
                    if (s.autoCheckUpdates) "Приложение проверяет новую версию при запуске и раз в сутки и коротко сообщает о ней. Устанавливается обновление только после вашего подтверждения."
                    else "Автоматическая проверка выключена: о новой версии приложение не сообщает. Проверить можно кнопкой «Проверить сейчас».",
                    fontSize = 16.sp, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private class HelpSection(val title: String, val rows: List<Pair<String, String>>)

/** Only what a viewer needs: which key does what. */
private val helpSections = listOf(
    HelpSection("Просмотр", listOf(
        "▲ ▼" to "следующий / предыдущий канал",
        "OK" to "описание передачи, ещё раз — список каналов",
        "◀" to "список каналов",
        "▶" to "программа передач",
        "Назад" to "список каналов, ещё раз — меню",
    )),
    HelpSection("Субтитры и звук", listOf(
        "SUB" to "у канала есть субтитры: Настройки → «Субтитры (где есть)»",
        "RU/EN" to "у канала несколько звуковых дорожек",
        "OK, затем ▼" to "сменить звук (в описании передачи); выбор запоминается",
        "Кнопка «Аудио»" to "сменить звук, если такая есть на пульте",
    )),
    HelpSection("Список каналов и меню", listOf(
        "OK" to "смотреть канал",
        "Удерживать OK" to "добавить в избранное или убрать",
        "◀" to "меню: все каналы, избранное, категории, настройки",
        "Выход" to "Меню → «Выход»",
    )),
    HelpSection("Архив", listOf(
        "Как открыть" to "▶ на канале, выбрать прошедшую передачу с пометкой «Архив», OK",
        "OK" to "пауза",
        "◀ ▶" to "перемотка на 30 секунд (удерживать — быстрее)",
        "▲ ▼" to "перемотка на 5 минут",
        "Назад" to "вернуться в прямой эфир",
    )),
    HelpSection("Телефон и планшет", listOf(
        "Касание видео" to "как OK",
        "Свайп вверх / вниз" to "следующий / предыдущий канал, в архиве — 5 минут",
        "Долгое нажатие" to "на канале — избранное",
        "Полоса внизу" to "в архиве: нажмите или перетащите — перемотка",
        "Назад" to "как на пульте",
    )),
    HelpSection("Мышь", listOf(
        "Левая кнопка" to "как OK",
        "Правая кнопка" to "назад",
        "Колёсико" to "переключение каналов, в архиве — перемотка",
    )),
)

@Composable
private fun HelpPage() {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    PageFrame {
        Title("Как пользоваться / наш сайт")
        // Each section takes the focus in turn, so the remote scrolls the page.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(helpSections) { i, sec ->
                Surface(
                    onClick = {},
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    colors = FocusStyle.colors(),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sec.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(bottom = 2.dp))
                        sec.rows.forEach { (key, what) ->
                            Row {
                                Text(key, fontSize = 17.sp, color = Palette.Focus, modifier = Modifier.width(200.dp))
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
                Text("О приложении", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(bottom = 2.dp))
                Text("Remotely ${BuildConfig.VERSION_NAME} — бесплатное приложение с открытым кодом.", fontSize = 17.sp, color = Palette.Text)
                Text("Автор: satroschenko", fontSize = 17.sp, color = Palette.Text)
                Text("Сайт проекта, вопросы и пожелания:", fontSize = 17.sp, color = Palette.Text)
                Text("github.com/satroschenko/remotely", fontSize = 17.sp, color = Palette.Focus)
                Text(
                    if (noBrowser) "Браузера нет — наведите камеру телефона на код справа." else "Нажмите, чтобы открыть, или наведите камеру телефона на код справа.",
                    fontSize = 15.sp, color = if (noBrowser) Palette.Focus else Palette.TextDim,
                )
            }
            Spacer(Modifier.width(20.dp))
            Image(painterResource(R.drawable.qr_github), "QR-код ссылки на проект", Modifier.size(132.dp))
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
        is SessionState.Active -> "активна, токен до ${ZalaTime.relativeLabel(st.expiresAtMs, now)}"
        SessionState.Connecting -> "подключение"
        SessionState.BadCredentials -> "неверный логин/пароль"
        SessionState.LoggedOut -> "нет"
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    PageFrame {
        Title("Диагностика")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            Text("Сеть: ${if (net) "есть" else "нет"}", fontSize = 18.sp, color = Palette.Text)
            Text("Сессия: $sessionText", fontSize = 18.sp, color = Palette.Text)
            Text("Плеер: ${ps.phase}  ${ps.target?.let { it.channel.number.toString() + ". " + it.channel.name } ?: ""}", fontSize = 18.sp, color = Palette.Text)
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
