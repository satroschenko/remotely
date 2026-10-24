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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.remotely.AppContainer
import app.remotely.BuildConfig
import app.remotely.core.ZalaTime
import app.remotely.data.api.Channel
import app.remotely.data.session.SessionState
import app.remotely.data.store.Quality
import app.remotely.data.store.Settings
import app.remotely.ui.common.MessageDialog
import app.remotely.ui.common.pointerClick
import app.remotely.ui.theme.FocusStyle
import app.remotely.ui.theme.Palette
import kotlinx.coroutines.launch

private enum class Page { MAIN, CHANNELS, DIAGNOSTICS }

@UnstableApi
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    var page by remember { mutableStateOf(Page.MAIN) }
    BackHandler { if (page == Page.MAIN) onBack() else page = Page.MAIN }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.MAIN
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { if (forward) it / 5 else -it / 5 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { if (forward) -it / 6 else it / 6 })
        },
        label = "settingsPage",
        modifier = Modifier.fillMaxSize().background(Palette.Background),
    ) { p ->
        when (p) {
            Page.MAIN -> MainPage(c, onBack, onLoggedOut) { page = it }
            Page.CHANNELS -> ChannelsPage(c)
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
private fun MainPage(c: AppContainer, onBack: () -> Unit, onLoggedOut: () -> Unit, open: (Page) -> Unit) {
    val s by c.settings.settings.collectAsState(Settings())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var login by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        login = c.currentLogin() ?: ""
        runCatching { first.requestFocus() }
    }

    BackHandler(enabled = confirmLogout) { confirmLogout = false }
    PageFrame(dialog = { if (confirmLogout) LogoutDialog(c, onLoggedOut) { confirmLogout = false } }) {
        Title("Настройки")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Item("← Вернуться к просмотру", modifier = Modifier.focusRequester(first), onClick = onBack) }
            item { Item("Каналы: скрыть, избранное, порядок") { open(Page.CHANNELS) } }
            item {
                Item("Размер шрифта", s.fontSize.label) {
                    scope.launch { c.settings.update { it.copy(fontSize = it.fontSize.next()) } }
                }
            }
            item {
                Item("Качество видео", if (s.quality == Quality.AUTO) "Авто" else "Максимальное") {
                    scope.launch { c.settings.update { it.copy(quality = if (it.quality == Quality.AUTO) Quality.MAX else Quality.AUTO) } }
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
            item { Item("Диагностика") { open(Page.DIAGNOSTICS) } }
            item { Item("Аккаунт", login) {} }
            item { Item("Выйти из аккаунта") { confirmLogout = true } }
            item { Item("Версия", BuildConfig.VERSION_NAME) {} }
        }
    }
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
