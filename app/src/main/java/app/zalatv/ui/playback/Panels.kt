package app.zalatv.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.zalatv.core.ZalaTime
import app.zalatv.data.api.Channel
import app.zalatv.data.api.Programme
import app.zalatv.data.repo.ChannelLists
import app.zalatv.data.repo.EpgLogic
import app.zalatv.player.Phase
import app.zalatv.ui.common.ProgressBar
import app.zalatv.ui.theme.FocusStyle
import app.zalatv.ui.theme.Palette
import coil.compose.AsyncImage

private val PanelShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)

@UnstableApi
@Composable
fun BoxScope.ChannelPanel(vm: PlaybackViewModel, ui: PlaybackUi, lists: ChannelLists, favorites: List<String>, dimmed: Boolean = false) {
    val mode = vm.currentMode()
    val list = vm.activeList()
    val startIndex = list.indexOfFirst { it.code == (ui.focused ?: ui.current)?.code }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }

    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.55f)
            .clip(PanelShape).background(Palette.Panel).padding(top = 28.dp)
            .alpha(if (dimmed) 0.5f else 1f),
    ) {
        Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mode == ListMode.FAVORITES) "★ Избранное" else "Все каналы", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            Text("← меню   → программа", fontSize = 18.sp, color = Palette.TextDim)
        }
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) {
            Text("Загрузка каналов…", fontSize = 26.sp, color = Palette.TextDim, modifier = Modifier.padding(32.dp))
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(list, key = { _, ch -> ch.code }) { i, ch ->
                ChannelRow(
                    ch = ch,
                    playing = ch.code == ui.current?.code,
                    favorite = ch.code in favorites,
                    now = vm.nowMs(),
                    modifier = if (i == startIndex) Modifier.focusRequester(focus) else Modifier,
                    onFocus = { vm.onListFocus(ch) },
                    onClick = { vm.onListSelect(ch) },
                    onLongClick = { vm.askFavorite(ch) },
                    enabled = !dimmed,
                )
            }
        }
    }
    LaunchedEffect(dimmed, list.size) {
        if (!dimmed && list.isNotEmpty()) {
            state.scrollToItem((startIndex - 3).coerceAtLeast(0))
            runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    playing: Boolean,
    favorite: Boolean,
    now: Long,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = FocusStyle.colors(),
        border = FocusStyle.border(),
        scale = FocusStyle.scale(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.number}", fontSize = 26.sp, color = Palette.TextDim, modifier = Modifier.width(64.dp))
            Box(Modifier.size(72.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF26324A)), contentAlignment = Alignment.Center) {
                if (ch.logoUrl != null) AsyncImage(ch.logoUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) Text("▶ ", fontSize = 24.sp, color = Palette.Focus)
                    Text(ch.name, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (favorite) Text("  ★", fontSize = 24.sp, color = Palette.Focus)
                }
                if (ch.nowTitle.isNotBlank() && now < ch.nowEndMs) {
                    Text(ch.nowTitle, fontSize = 20.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    ProgressBar(EpgLogic.progress(ch.nowStartMs, ch.nowEndMs, now), Modifier.fillMaxWidth(0.8f))
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun BoxScope.SideMenu(vm: PlaybackViewModel, onOpenSettings: () -> Unit) {
    val first = remember { FocusRequester() }
    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().width(420.dp).clip(PanelShape).background(Palette.PanelSolid).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Меню", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        MenuItem("★ Избранное", Modifier.focusRequester(first)) {
            if (vm.lists.value.favorites.isEmpty()) vm.toast("Избранное пусто: удерживайте OK на канале") else vm.selectMode(ListMode.FAVORITES)
        }
        MenuItem("Все каналы") { vm.selectMode(ListMode.ALL) }
        MenuItem("⚙ Настройки", onClick = onOpenSettings)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun MenuItem(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = FocusStyle.colors(),
        border = FocusStyle.border(),
        scale = FocusStyle.scale(),
    ) {
        Text(text, fontSize = 28.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp))
    }
}

/** Info under the preview window about the focused channel. */
@UnstableApi
@Composable
fun BoxScope.PreviewInfo(ui: PlaybackUi, vm: PlaybackViewModel) {
    val ch = ui.focused ?: ui.current ?: return
    val now = vm.nowMs()
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = 375.dp, end = 40.dp).width(560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${ch.number}. ${ch.name}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        if (ch.nowTitle.isNotBlank() && now < ch.nowEndMs) {
            Text("Сейчас: ${ZalaTime.hhmm(ch.nowStartMs)}–${ZalaTime.hhmm(ch.nowEndMs)}", fontSize = 20.sp, color = Palette.Accent)
            Text(ch.nowTitle, fontSize = 26.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        ui.focusedNext?.takeIf { ui.focused?.code == ch.code }?.let {
            Text("Далее: ${ZalaTime.hhmm(it.startMs)}  ${it.title}", fontSize = 22.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("OK — смотреть   удерживать OK — избранное", fontSize = 18.sp, color = Palette.TextDim)
    }
}

@UnstableApi
@Composable
fun BoxScope.EpgPanel(vm: PlaybackViewModel, e: EpgUi) {
    val now = vm.nowMs()
    val startIndex = e.items.indexOfFirst { now >= it.startMs && now < it.endMs }.let { if (it < 0) 0 else it }
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }

    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.58f).clip(PanelShape).background(Palette.PanelSolid).padding(top = 28.dp),
    ) {
        Text("${e.channel.number}. ${e.channel.name} — программа", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◀", fontSize = 24.sp, color = if (e.dayIndex > 0) Palette.Focus else Palette.TextDim)
            e.days.forEachIndexed { i, d ->
                val sel = i == e.dayIndex
                Text(
                    d.label,
                    fontSize = 24.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) Color.Black else Palette.TextDim,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Palette.Focus else Color.Transparent).padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Text("▶", fontSize = 24.sp, color = if (e.dayIndex < e.days.lastIndex) Palette.Focus else Palette.TextDim)
        }
        Spacer(Modifier.height(12.dp))
        when {
            e.loading -> Text("Загрузка…", fontSize = 26.sp, color = Palette.TextDim, modifier = Modifier.padding(32.dp))
            e.items.isEmpty() -> Text("Нет программы на этот день", fontSize = 26.sp, color = Palette.TextDim, modifier = Modifier.padding(32.dp))
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(e.items, key = { _, p -> p.code + p.startMs }) { i, p ->
                ProgrammeRow(
                    p, now, vm.canRecord(e.channel, p),
                    if (i == startIndex) Modifier.focusRequester(focus) else Modifier,
                    onFocus = { vm.onEpgFocus(p) },
                    onClick = { vm.onEpgSelect(p) },
                )
            }
        }
    }
    // Description of the focused programme under the preview.
    e.focused?.let { p ->
        Column(Modifier.align(Alignment.TopEnd).padding(top = 375.dp, end = 40.dp).width(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ZalaTime.hhmm(p.startMs)}–${ZalaTime.hhmm(p.endMs)}", fontSize = 22.sp, color = Palette.Accent)
            Text(p.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.genre.isNotBlank()) Text(p.genre, fontSize = 20.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) Text(p.description, fontSize = 20.sp, color = Palette.TextDim, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
    LaunchedEffect(e.items) {
        if (e.items.isNotEmpty()) {
            state.scrollToItem((startIndex - 2).coerceAtLeast(0))
            runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun ProgrammeRow(p: Programme, now: Long, recordable: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val onAir = now >= p.startMs && now < p.endMs
    val past = p.endMs <= now
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = FocusStyle.colors(if (onAir) Color(0xFF243B55) else Palette.Row),
        border = FocusStyle.border(),
        scale = FocusStyle.scale(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ZalaTime.hhmm(p.startMs), fontSize = 26.sp, color = if (past) Palette.TextDim else Palette.Text, modifier = Modifier.width(96.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 26.sp, color = if (past && !recordable) Palette.TextDim else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onAir) ProgressBar(EpgLogic.progress(p.startMs, p.endMs, now), Modifier.fillMaxWidth(0.7f).padding(top = 6.dp))
            }
            when {
                onAir -> Text("● Сейчас", fontSize = 20.sp, color = Palette.Record)
                past && recordable -> Text("▶ Запись", fontSize = 20.sp, color = Palette.Accent)
            }
        }
    }
}

@Composable
fun BoxScope.Banner(ch: Channel, now: Programme?, next: Programme?, nowMs: Long) {
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD0B0F17)).padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.number}", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
            Spacer(Modifier.width(24.dp))
            Text(ch.name, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            Text(ZalaTime.hhmm(nowMs), fontSize = 32.sp, color = Palette.TextDim)
        }
        val title = now?.title ?: ch.nowTitle.takeIf { nowMs < ch.nowEndMs }
        val start = now?.startMs ?: ch.nowStartMs
        val end = now?.endMs ?: ch.nowEndMs
        if (!title.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ZalaTime.hhmm(start)}–${ZalaTime.hhmm(end)}", fontSize = 26.sp, color = Palette.Accent)
                Spacer(Modifier.width(20.dp))
                Text(title, fontSize = 30.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ProgressBar(EpgLogic.progress(start, end, nowMs), Modifier.fillMaxWidth())
        }
        next?.let { Text("Далее: ${ZalaTime.hhmm(it.startMs)}  ${it.title}", fontSize = 24.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text("OK — каналы    → — программа передач", fontSize = 18.sp, color = Palette.TextDim)
    }
}

@UnstableApi
@Composable
fun BoxScope.ArchiveOverlay(a: ArchiveUi, paused: Boolean, vm: PlaybackViewModel) {
    val p = a.programme
    val now = vm.nowMs()
    // Always-visible mark so recordings are never confused with live TV.
    Row(
        Modifier.align(Alignment.TopStart).padding(32.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● ЗАПИСЬ", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Record)
        Text("  ·  ${ZalaTime.relativeLabel(p.startMs, now)}", fontSize = 24.sp, color = Palette.Text)
    }
    if (!a.controlsVisible && !paused) return
    val duration = (p.endMs - p.startMs).coerceAtLeast(1)
    val shownWall = a.seekTargetMs ?: (p.startMs + a.positionMs)
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD0B0F17)).padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(p.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ZalaTime.hhmm(shownWall), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
            if (a.seekTargetMs != null) Text("   ${formatDelta(a.seekDeltaMs)}", fontSize = 32.sp, color = Palette.Text)
            if (paused) Text("   ❚❚ Пауза", fontSize = 32.sp, color = Palette.Text)
        }
        ProgressBar(((shownWall - p.startMs).toFloat() / duration), Modifier.fillMaxWidth())
        Row {
            Text(ZalaTime.hhmm(p.startMs), fontSize = 22.sp, color = Palette.TextDim)
            Spacer(Modifier.weight(1f))
            Text(ZalaTime.hhmm(p.endMs), fontSize = 22.sp, color = Palette.TextDim)
        }
        Text("OK — пауза    ← → — 30 сек (держать — быстрее)    ↑ ↓ — 5 мин    Назад — в эфир", fontSize = 18.sp, color = Palette.TextDim)
    }
}

private fun formatDelta(ms: Long): String {
    val sign = if (ms < 0) "−" else "+"
    val s = kotlin.math.abs(ms) / 1000
    return when {
        s < 60 -> "$sign$s сек"
        s % 60 == 0L -> "$sign${s / 60} мин"
        else -> "$sign${s / 60} мин ${s % 60} сек"
    }
}

@Composable
fun BoxScope.StatusPill(phase: Phase) {
    val text = when (phase) {
        Phase.CONNECTING -> "Подключение…"
        Phase.RECONNECTING -> "Восстанавливаем показ…"
        Phase.NO_NETWORK -> "Нет интернета, ждём…"
        else -> return
    }
    Text(
        text,
        fontSize = 26.sp,
        color = Palette.Text,
        modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(horizontal = 32.dp, vertical = 18.dp),
    )
}

@Composable
fun BoxScope.Toast(text: String) {
    Text(
        text,
        fontSize = 26.sp,
        color = Palette.Text,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEE1B2533)).padding(horizontal = 32.dp, vertical = 16.dp),
    )
}
