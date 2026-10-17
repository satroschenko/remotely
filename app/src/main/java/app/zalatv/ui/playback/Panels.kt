package app.zalatv.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import app.zalatv.ui.common.AnimatedNullable
import app.zalatv.ui.common.Motion
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Layout
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

private val PanelShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)

/** Keeps the focused row at ~30% from the top and scrolls there with a short eased animation. */
@OptIn(ExperimentalFoundationApi::class)
private val PivotScroll = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(200, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize * 0.3f - size / 2).coerceAtLeast(0f)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmoothList(state: LazyListState, padding: PaddingValues, spacing: Dp, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides PivotScroll) {
        LazyColumn(modifier = modifier, state = state, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
private fun HintChip(arrow: String, label: String, arrowFirst: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF26324A)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (arrowFirst) Text("$arrow ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
        if (!arrowFirst) Text(" $arrow", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
    }
}

// TV canvas is 960x540 dp. Panels take the left side, the preview sits top-right.
const val PANEL_FRACTION = 0.52f
val PreviewWidth = 320.dp
val PreviewHeight = 180.dp
val PreviewTop = 24.dp
// Centred in the free area right of the panel: (960 * (1 - 0.52) - 320) / 2 ≈ 70.
val PreviewEnd = 70.dp
private val ChannelRowHeight = 72.dp
private val InfoTop = PreviewTop + PreviewHeight + 12.dp

@UnstableApi
@Composable
fun BoxScope.ChannelPanel(vm: PlaybackViewModel, ui: PlaybackUi, lists: ChannelLists, favorites: List<String>, dimmed: Boolean = false) {
    val mode = vm.currentMode()
    val list = vm.activeList()
    val startIndex = list.indexOfFirst { it.code == (ui.focused ?: ui.current)?.code }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }

    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(PANEL_FRACTION)
            .clip(PanelShape).background(Palette.Panel).padding(top = 20.dp)
            .alpha(if (dimmed) 0.5f else 1f),
    ) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mode == ListMode.FAVORITES) "★ Избранное" else "Все каналы", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            HintChip("◀", "Меню", arrowFirst = true)
            Spacer(Modifier.width(10.dp))
            HintChip("▶", "Программа", arrowFirst = false)
        }
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) {
            Text("Загрузка каналов…", fontSize = 26.sp, color = Palette.TextDim, modifier = Modifier.padding(32.dp))
        }
        SmoothList(state, PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 6.dp), 6.dp) {
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        // Fixed height: always the name line and the programme line, so every row looks the same.
        Row(Modifier.height(ChannelRowHeight).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.number}", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.width(44.dp))
            Box(Modifier.size(56.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF26324A)), contentAlignment = Alignment.Center) {
                if (ch.logoUrl != null) AsyncImage(ch.logoUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) Text("▶ ", fontSize = 20.sp, color = Palette.Focus)
                    Text(ch.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (favorite) Text("  ★", fontSize = 20.sp, color = Palette.Focus)
                }
                val known = ch.nowTitle.isNotBlank() && now < ch.nowEndMs
                Text(if (known) ch.nowTitle else "—", fontSize = 16.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(6.dp).fillMaxWidth(0.8f)) {
                    if (known) ProgressBar(EpgLogic.progress(ch.nowStartMs, ch.nowEndMs, now), Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun BoxScope.SideMenu(vm: PlaybackViewModel, onOpenSettings: () -> Unit, onExit: () -> Unit) {
    val first = remember { FocusRequester() }
    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().width(300.dp).clip(PanelShape).background(Palette.PanelSolid).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Меню", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        MenuItem("Все каналы", Modifier.focusRequester(first)) { vm.selectMode(ListMode.ALL) }
        MenuItem("★ Избранное") {
            if (vm.lists.value.favorites.isEmpty()) vm.toast("Избранное пусто: удерживайте OK на канале") else vm.selectMode(ListMode.FAVORITES)
        }
        MenuItem("⚙ Настройки", onClick = onOpenSettings)
        Spacer(Modifier.weight(1f))
        MenuItem("✕ Выход", onClick = onExit)
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
        Text(text, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
    }
}

/** Under the preview: the focused channel's programme — now on air, then as many next ones as fit. */
@UnstableApi
@Composable
fun BoxScope.PreviewInfo(ui: PlaybackUi, vm: PlaybackViewModel) {
    val ch = ui.focused ?: ui.current ?: return
    val now = vm.nowMs()
    val loaded = ui.focusedUpcoming.filter { it.endMs > now }
    Column(
        Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(top = InfoTop, end = PreviewEnd, bottom = 16.dp).width(PreviewWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${ch.number}. ${ch.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FitColumn(Modifier.weight(1f).fillMaxWidth(), spacing = 6.dp) {
            if (loaded.isNotEmpty()) {
                loaded.forEach { p -> UpcomingRow(p.startMs, p.endMs, p.title, now) }
            } else if (ch.nowTitle.isNotBlank() && now < ch.nowEndMs) {
                // Programme not loaded yet: what the channel list already knows.
                UpcomingRow(ch.nowStartMs, ch.nowEndMs, ch.nowTitle, now)
            }
        }
        Text("OK — смотреть · удерживать OK — избранное", fontSize = 14.sp, color = Palette.TextDim)
    }
}

@Composable
private fun UpcomingRow(startMs: Long, endMs: Long, title: String, now: Long) {
    val onAir = now >= startMs && now < endMs
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ZalaTime.hhmm(startMs), fontSize = 17.sp, color = if (onAir) Palette.Accent else Palette.TextDim,
                fontWeight = if (onAir) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(56.dp),
            )
            Text(
                title, fontSize = 17.sp, color = if (onAir) Palette.Text else Palette.TextDim,
                fontWeight = if (onAir) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onAir) {
            Spacer(Modifier.height(4.dp))
            ProgressBar(EpgLogic.progress(startMs, endMs, now), Modifier.padding(start = 56.dp).fillMaxWidth())
        }
    }
}

/** Column that places only the children that fit completely — no half-cut row at the bottom. */
@Composable
private fun FitColumn(modifier: Modifier, spacing: Dp, content: @Composable () -> Unit) {
    Layout(content, modifier.clipToBounds()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = mutableListOf<Placeable>()
        var used = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val needed = if (placeables.isEmpty()) p.height else gap + p.height
            if (used + needed > constraints.maxHeight) break
            placeables += p
            used += needed
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = 0
            placeables.forEach { it.placeRelative(0, y); y += it.height + gap }
        }
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
        Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(PANEL_FRACTION).clip(PanelShape).background(Palette.PanelSolid).padding(top = 20.dp),
    ) {
        Text("${e.channel.number}. ${e.channel.name} — программа", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◀", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (e.dayIndex > 0) Palette.Focus else Palette.TextDim)
            e.days.forEachIndexed { i, d ->
                val sel = i == e.dayIndex
                val bg by animateColorAsState(if (sel) Palette.Focus else Color.Transparent, tween(200), label = "tab")
                val fg by animateColorAsState(if (sel) Color.Black else Palette.TextDim, tween(200), label = "tabText")
                Text(
                    d.label,
                    fontSize = 18.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = fg,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text("▶", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (e.dayIndex < e.days.lastIndex) Palette.Focus else Palette.TextDim)
        }
        Spacer(Modifier.height(12.dp))
        when {
            e.loading -> Text("Загрузка…", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.padding(24.dp))
            e.items.isEmpty() -> Text("Нет программы на этот день", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.padding(24.dp))
        }
        val listAlpha by animateFloatAsState(if (e.loading) 0f else 1f, tween(if (e.loading) 90 else 220), label = "epgList")
        SmoothList(state, PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 6.dp), 6.dp, Modifier.alpha(listAlpha)) {
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
        Column(Modifier.align(Alignment.TopEnd).padding(top = InfoTop, end = PreviewEnd).width(PreviewWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ZalaTime.hhmm(p.startMs)}–${ZalaTime.hhmm(p.endMs)}", fontSize = 16.sp, color = Palette.Accent)
            Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.genre.isNotBlank()) Text(p.genre, fontSize = 15.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) Text(p.description, fontSize = 15.sp, color = Palette.TextDim, maxLines = 7, overflow = TextOverflow.Ellipsis)
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ZalaTime.hhmm(p.startMs), fontSize = 20.sp, color = if (past) Palette.TextDim else Palette.Text, modifier = Modifier.width(68.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 20.sp, color = if (past && !recordable) Palette.TextDim else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onAir) ProgressBar(EpgLogic.progress(p.startMs, p.endMs, now), Modifier.fillMaxWidth(0.7f).padding(top = 6.dp))
            }
            when {
                onAir -> Text("● Сейчас", fontSize = 15.sp, color = Palette.Record)
                past && recordable -> Text("▶ Запись", fontSize = 15.sp, color = Palette.Accent)
            }
        }
    }
}

@Composable
fun Banner(ch: Channel, now: Programme?, next: Programme?, nowMs: Long) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xDD0B0F17)).padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${ch.number}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
            Spacer(Modifier.width(16.dp))
            Text(ch.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Spacer(Modifier.weight(1f))
            Text(ZalaTime.hhmm(nowMs), fontSize = 24.sp, color = Palette.TextDim)
        }
        val title = now?.title ?: ch.nowTitle.takeIf { nowMs < ch.nowEndMs }
        val start = now?.startMs ?: ch.nowStartMs
        val end = now?.endMs ?: ch.nowEndMs
        if (!title.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ZalaTime.hhmm(start)}–${ZalaTime.hhmm(end)}", fontSize = 20.sp, color = Palette.Accent)
                Spacer(Modifier.width(14.dp))
                Text(title, fontSize = 24.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ProgressBar(EpgLogic.progress(start, end, nowMs), Modifier.fillMaxWidth())
        }
        next?.let { Text("Далее: ${ZalaTime.hhmm(it.startMs)}  ${it.title}", fontSize = 19.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text("OK — каналы    → — программа передач", fontSize = 14.sp, color = Palette.TextDim)
    }
}

@UnstableApi
@Composable
fun BoxScope.ArchiveOverlay(a: ArchiveUi, paused: Boolean, vm: PlaybackViewModel) {
    val p = a.programme
    val now = vm.nowMs()
    // Always-visible mark so recordings are never confused with live TV.
    Row(
        Modifier.align(Alignment.TopStart).padding(20.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● ЗАПИСЬ", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Record)
        Text("  ·  ${ZalaTime.relativeLabel(p.startMs, now)}", fontSize = 18.sp, color = Palette.Text)
    }
    val duration = (p.endMs - p.startMs).coerceAtLeast(1)
    val shownWall = a.seekTargetMs ?: (p.startMs + a.positionMs)
    AnimatedVisibility(
        visible = a.controlsVisible || paused,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = Motion.fromBottom, exit = Motion.toBottom,
    ) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xDD0B0F17)).padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(p.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ZalaTime.hhmm(shownWall), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
            if (a.seekTargetMs != null) Text("   ${formatDelta(a.seekDeltaMs)}", fontSize = 24.sp, color = Palette.Text)
            if (paused) Text("   ❚❚ Пауза", fontSize = 24.sp, color = Palette.Text)
        }
        ProgressBar(((shownWall - p.startMs).toFloat() / duration), Modifier.fillMaxWidth())
        Row {
            Text(ZalaTime.hhmm(p.startMs), fontSize = 16.sp, color = Palette.TextDim)
            Spacer(Modifier.weight(1f))
            Text(ZalaTime.hhmm(p.endMs), fontSize = 16.sp, color = Palette.TextDim)
        }
        Text("OK — пауза    ← → — 30 сек (держать — быстрее)    ↑ ↓ — 5 мин    Назад — в эфир", fontSize = 14.sp, color = Palette.TextDim)
    }
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
fun BoxScope.StatusPill(phase: Phase, overPreview: Boolean) {
    val text = when (phase) {
        Phase.CONNECTING -> "Подключение…"
        Phase.RECONNECTING -> "Восстанавливаем показ…"
        Phase.NO_NETWORK -> "Нет интернета, ждём…"
        else -> null
    }
    AnimatedNullable(
        text,
        modifier = if (overPreview) Modifier.align(Alignment.TopEnd).padding(top = PreviewTop + PreviewHeight / 2 - 20.dp, end = PreviewEnd + PreviewWidth / 2 - 90.dp)
        else Modifier.align(Alignment.Center),
        enter = Motion.fade, exit = Motion.unfade,
    ) {
        Text(
            it,
            fontSize = if (overPreview) 16.sp else 22.sp,
            color = Palette.Text,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(horizontal = 32.dp, vertical = 18.dp),
        )
    }
}

@Composable
fun Toast(text: String) {
    Text(
        text,
        fontSize = 20.sp,
        color = Palette.Text,
        modifier = Modifier.padding(top = 24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xEE1B2533)).padding(horizontal = 32.dp, vertical = 16.dp),
    )
}
