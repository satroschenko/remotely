package app.remotely.ui.playback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import app.remotely.ui.common.AnimatedNullable
import app.remotely.ui.common.Motion
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.remotely.core.ZalaTime
import app.remotely.data.api.Channel
import app.remotely.data.api.Programme
import app.remotely.data.repo.ChannelGroup
import app.remotely.data.repo.ChannelLists
import app.remotely.data.repo.EpgLogic
import app.remotely.player.Phase
import app.remotely.ui.common.ProgressBar
import app.remotely.ui.common.pointerClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import app.remotely.ui.theme.FocusStyle
import app.remotely.ui.theme.Palette
import coil.compose.AsyncImage
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PanelShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)

/** A fully visible row needs no scroll; a cut or hidden one is scrolled just up to the edge, so the focus stays pinned there. */
private fun edgeDelta(offset: Float, size: Float, containerSize: Float): Float = when {
    offset < 0 -> offset
    offset + size > containerSize -> offset + size - containerSize
    else -> 0f
}

/** Scrolls only as far as needed, with a short eased animation. */
@OptIn(ExperimentalFoundationApi::class)
private val EdgeScroll = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(150, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = edgeDelta(offset, size, containerSize)
}

/** True when moving focus to the row needs a scroll first. */
private fun LazyListState.needsScroll(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return true
    return edgeDelta((item.offset - info.viewportStartOffset).toFloat(), item.size.toFloat(), (info.viewportEndOffset - info.viewportStartOffset).toFloat()) != 0f
}

/** Same placement as [EdgeScroll], but at once. A row just past an edge is estimated from its neighbour (rows share one height). */
private suspend fun LazyListState.reveal(index: Int) {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val first = visible.firstOrNull() ?: return scrollToItem(index)
    val last = visible.last()
    val step = first.size + info.mainAxisItemSpacing
    val offset = when {
        index < first.index - 1 || index > last.index + 1 -> return scrollToItem(index) // far jump (wrap): the list clamps at its end
        index < first.index -> first.offset - step
        index > last.index -> last.offset + step
        else -> visible.first { it.index == index }.offset
    }
    val delta = edgeDelta((offset - info.viewportStartOffset).toFloat(), first.size.toFloat(), (info.viewportEndOffset - info.viewportStartOffset).toFloat())
    if (delta == 0f) return
    scrollBy(delta)
    // The scroll lays the list out at once; rows can differ in height, so correct the estimate with the real row.
    val now = layoutInfo
    val row = now.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val fix = edgeDelta((row.offset - now.viewportStartOffset).toFloat(), row.size.toFloat(), (now.viewportEndOffset - now.viewportStartOffset).toFloat())
    if (fix != 0f) scrollBy(fix)
}

/**
 * Up/Down in a list of rows. A row fully on screen gets the normal focus move; one cut by the edge or off screen
 * is handled by [jumpTo] (scroll and focus in one frame), so the focus stays pinned to the edge and never runs
 * ahead of the scroll. Held keys are throttled to a pace the list can follow.
 */
private fun listKey(
    ev: KeyEvent, state: LazyListState, focusIndex: Int, lastIndex: Int, lastMove: LongArray, wrap: Boolean, jumpTo: (Int) -> Unit,
): Boolean {
    val up = ev.key == Key.DirectionUp || ev.key == Key.ChannelUp
    val down = ev.key == Key.DirectionDown || ev.key == Key.ChannelDown
    if ((!up && !down) || ev.type != KeyEventType.KeyDown || lastIndex < 0) return false
    val repeat = ev.nativeKeyEvent.repeatCount > 0
    val t = SystemClock.uptimeMillis()
    if (repeat && t - lastMove[0] < LIST_REPEAT_MS) return true
    lastMove[0] = t
    val target = if (up) focusIndex - 1 else focusIndex + 1
    return when {
        // Past either end: wrap around (on a fresh press only, holding stops at the end).
        target > lastIndex -> { if (wrap && !repeat) jumpTo(0); true }
        target < 0 -> { if (wrap && !repeat) jumpTo(lastIndex); true }
        ev.key == Key.ChannelUp || ev.key == Key.ChannelDown || state.needsScroll(target) -> { jumpTo(target); true }
        else -> false
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmoothList(state: LazyListState, padding: PaddingValues, spacing: Dp, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides EdgeScroll) {
        LazyColumn(modifier = modifier, state = state, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
private fun HintChip(arrow: String, label: String, arrowFirst: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.pointerClick(onClick = onClick).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26324A)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (arrowFirst) Text("$arrow ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
        if (!arrowFirst) Text(" $arrow", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
    }
}

// TV canvas is 960x540 dp. Panels take the left side, the preview sits top-right.
const val CHANNEL_PANEL_FRACTION = 0.45f
val PreviewWidth = 416.dp
val PreviewHeight = 234.dp
val PreviewTop = 24.dp
// Centred in the free area right of the channel and programme panels: (960 * (1 - 0.45) - 416) / 2 = 56.
val PreviewEnd = 56.dp
private val InfoSide = 24.dp
private val ChannelRowHeight = 72.dp
private val InfoTop = PreviewTop + PreviewHeight + 12.dp

@UnstableApi
@Composable
fun BoxScope.ChannelPanel(
    vm: PlaybackViewModel,
    ui: PlaybackUi,
    lists: ChannelLists,
    favorites: List<String>,
    dimmed: Boolean = false,
    dialogOpen: Boolean = false,
) {
    val mode = vm.currentMode() to vm.currentGroup()
    val list = vm.activeList()
    // Row that owns focus; picked once per list so scrolling does not rebuild the rows around it.
    var focusIndex by remember(mode) {
        mutableIntStateOf(list.indexOfFirst { it.code == (ui.focused ?: ui.current)?.code }.coerceAtLeast(0))
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 3).coerceAtLeast(0))
    // One requester per row: focus can then move in the same frame the list scrolls, with no in-between frame.
    val requesters = remember(mode) { HashMap<String, FocusRequester>() }
    fun requester(index: Int) = requesters.getOrPut(list[index].code) { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Progress bars only need a fresh clock now and then; reading it on every recomposition redraws every row.
    val now by produceState(vm.nowMs()) { while (true) { delay(30_000); value = vm.nowMs() } }
    val lastMove = remember { longArrayOf(0L) }

    fun jumpTo(index: Int) {
        focusIndex = index
        scope.launch {
            // The scroll re-lays the list out at once, so the row is in place before the next frame is drawn.
            state.reveal(index)
            if (runCatching { requester(index).requestFocus() }.isFailure) {
                withFrameNanos { }
                runCatching { requester(index).requestFocus() }
            }
        }
    }

    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(CHANNEL_PANEL_FRACTION)
            .clip(PanelShape).background(Palette.Panel).padding(top = 20.dp)
            .alpha(if (dimmed) 0.5f else 1f),
    ) {
        Text(
            vm.listTitle(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(10.dp))
        // Narrow panel: hints get their own line under the title.
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            HintChip("◀", "Меню", arrowFirst = true, onClick = vm::openMenu)
            Spacer(Modifier.weight(1f))
            HintChip("▶", "Программа", arrowFirst = false) { (ui.focused ?: ui.current)?.let(vm::openEpg) }
        }
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) {
            Text("Загрузка каналов…", fontSize = 26.sp, color = Palette.TextDim, modifier = Modifier.padding(32.dp))
        }
        SmoothList(
            state, PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 6.dp), 6.dp,
            Modifier.onPreviewKeyEvent { ev -> listKey(ev, state, focusIndex, list.lastIndex, lastMove, wrap = true, ::jumpTo) },
        ) {
            itemsIndexed(list, key = { _, ch -> ch.code }) { i, ch ->
                ChannelRow(
                    ch = ch,
                    number = lists.numberOf(ch),
                    playing = ch.code == ui.current?.code,
                    favorite = ch.code in favorites,
                    now = now,
                    modifier = Modifier.focusRequester(requesters.getOrPut(ch.code) { FocusRequester() }),
                    onFocus = { focusIndex = i; vm.onListFocus(ch) },
                    onClick = { vm.onListSelect(ch) },
                    onLongClick = { vm.askFavorite(ch) },
                    enabled = !dimmed && !dialogOpen,
                )
            }
        }
    }
    LaunchedEffect(dimmed, dialogOpen, list.size, mode) {
        if (!dimmed && !dialogOpen && list.isNotEmpty()) {
            focusIndex = focusIndex.coerceIn(0, list.lastIndex)
            val visible = state.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == focusIndex }) state.scrollToItem((focusIndex - 3).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { requester(focusIndex).requestFocus() }
        }
    }
}

private const val LIST_REPEAT_MS = 90L

@Composable
private fun ChannelRow(
    ch: Channel,
    number: Int,
    playing: Boolean,
    favorite: Boolean,
    now: Long,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .then(if (enabled) Modifier.pointerClick(onLongClick, onClick) else Modifier),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = FocusStyle.colors(),
        border = FocusStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        // Fixed height: always the name line and the programme line, so every row looks the same.
        Row(Modifier.height(ChannelRowHeight).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.width(44.dp))
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
                ScrollingLine(if (known) ch.nowTitle else "—", focused, fontSize = 16.sp, color = Palette.TextDim)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(6.dp).fillMaxWidth(0.8f)) {
                    if (known) ProgressBar(EpgLogic.progress(ch.nowStartMs, ch.nowEndMs, now), Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
fun BoxScope.SideMenu(
    vm: PlaybackViewModel, groups: List<ChannelGroup>, groupsOpen: Boolean, active: Boolean, dialogOpen: Boolean,
    onOpenSettings: () -> Unit, onExit: () -> Unit,
) {
    val mode = vm.currentMode()
    val group = vm.currentGroup()
    // Focus starts on the list being shown, so OK alone returns to it; back from the categories page, on «Категории».
    val current = remember { FocusRequester() }
    var fromGroups by remember { mutableStateOf(false) }
    LaunchedEffect(groupsOpen) { if (groupsOpen) fromGroups = true }
    fun Modifier.currentIf(yes: Boolean) = if (yes) focusRequester(current) else this
    // The main page wraps around like the channel list: ▲ on the top item reaches «Выход», ▼ on «Выход» the top.
    val top = remember { FocusRequester() }
    val exitItem = remember { FocusRequester() }
    fun Modifier.wrapTo(key: Key, target: FocusRequester) = onPreviewKeyEvent { ev ->
        if (ev.key != key) false
        else { if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) runCatching { target.requestFocus() }; true }
    }
    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().width(300.dp).clip(PanelShape).background(Palette.PanelSolid)
            // The channel list lies under the menu: without this ▲ ▼ can land on a hidden row between the items.
            // Only the arrows are held back: ▶ hands the focus to the list.
            .focusProperties { exit = { if (it == FocusDirection.Up || it == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default } }
            .focusGroup()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (groupsOpen) {
            Text("‹ Категории", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.pointerClick(onClick = vm::closeGroups))
            // Longer than the screen: the page scrolls.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEachIndexed { i, g ->
                    val here = if (group != null) g.code == group else i == 0
                    MenuItem(g.name, Modifier.currentIf(here).onFocusChanged { if (it.isFocused) vm.menuFocus = g.code }, selected = g.code == group) {
                        vm.selectGroup(g.code)
                    }
                }
            }
        } else {
            Text("Меню", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            val onCategories = groups.isNotEmpty() && (mode == ListMode.GROUP || fromGroups)
            MenuItem("Все каналы", Modifier.currentIf(mode == ListMode.ALL && !onCategories).focusRequester(top).wrapTo(Key.DirectionUp, exitItem), selected = mode == ListMode.ALL) { vm.selectMode(ListMode.ALL) }
            MenuItem("★ Избранное", Modifier.currentIf(mode == ListMode.FAVORITES && !onCategories), selected = mode == ListMode.FAVORITES) {
                if (vm.lists.value.favorites.isEmpty()) vm.toast("Избранное пусто: удерживайте OK на канале") else vm.selectMode(ListMode.FAVORITES)
            }
            if (groups.isNotEmpty()) {
                // The chosen category is named here, so a short channel list is never a surprise.
                MenuItem("☰ Категории", Modifier.currentIf(onCategories).onFocusChanged { vm.menuFocus = if (it.isFocused) MENU_CATEGORIES else null }, selected = mode == ListMode.GROUP, hint = vm.listTitle().takeIf { mode == ListMode.GROUP }, more = true, onClick = vm::openGroups)
            }
            Spacer(Modifier.weight(1f))
            val update by vm.updatePending.collectAsState()
            MenuItem("⚙ Настройки", hint = "есть обновление".takeIf { update }, onClick = onOpenSettings)
            MenuItem("✕ Выход", Modifier.focusRequester(exitItem).wrapTo(Key.DirectionDown, top), onClick = onExit)
        }
    }
    // Also after the "exit?" dialog is cancelled: focus would otherwise stay on the removed button.
    // Not while the menu slides away: the channel list has the focus by then.
    LaunchedEffect(dialogOpen, groupsOpen, active) {
        if (active && !dialogOpen) {
            withFrameNanos { }
            runCatching { current.requestFocus() }
        }
    }
}

const val MENU_CATEGORIES = "categories"

@Composable
private fun MenuItem(
    text: String, modifier: Modifier = Modifier, selected: Boolean = false, hint: String? = null, more: Boolean = false, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().pointerClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = FocusStyle.colors(),
        border = FocusStyle.border(),
        scale = FocusStyle.scale(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            // The list on screen stays visible after the focus moves on: marker and accent colour, as in the channel list.
            if (selected) Text("▶ ", fontSize = 18.sp, color = Palette.Focus)
            Column(Modifier.weight(1f)) {
                Text(
                    text, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Palette.Focus else Color.Unspecified,
                )
                if (hint != null) Text(hint, fontSize = 15.sp, color = Palette.Focus, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (more) Text("›", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
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
        Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(1f - CHANNEL_PANEL_FRACTION)
            .padding(top = InfoTop, start = InfoSide, end = InfoSide, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${vm.lists.value.numberOf(ch)}. ${ch.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    // Same scrolling as the channel list: per-row requesters, focus pinned to the edge.
    var focusIndex by remember(e.items) { mutableIntStateOf(startIndex) }
    val requesters = remember(e.items) { HashMap<Int, FocusRequester>() }
    fun requester(index: Int) = requesters.getOrPut(index) { FocusRequester() }
    val scope = rememberCoroutineScope()
    val lastMove = remember { longArrayOf(0L) }

    fun jumpTo(index: Int) {
        focusIndex = index
        scope.launch {
            state.reveal(index)
            if (runCatching { requester(index).requestFocus() }.isFailure) {
                withFrameNanos { }
                runCatching { requester(index).requestFocus() }
            }
        }
    }

    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(CHANNEL_PANEL_FRACTION).clip(PanelShape).background(Palette.PanelSolid).padding(top = 20.dp),
    ) {
        Text("${vm.lists.value.numberOf(e.channel)}. ${e.channel.name} — программа", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◀", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (e.dayIndex > 0) Palette.Focus else Palette.TextDim,
                modifier = Modifier.pointerClick { vm.epgDay(-1) })
            // The panel fits three day tabs: the selected day and its neighbours.
            val from = (e.dayIndex - 1).coerceAtMost(e.days.size - 3).coerceAtLeast(0)
            e.days.forEachIndexed { i, d ->
                if (i !in from until from + 3) return@forEachIndexed
                val sel = i == e.dayIndex
                val bg by animateColorAsState(if (sel) Palette.Focus else Color.Transparent, tween(200), label = "tab")
                val fg by animateColorAsState(if (sel) Color.Black else Palette.TextDim, tween(200), label = "tabText")
                Text(
                    d.label,
                    fontSize = 18.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = fg,
                    modifier = Modifier.pointerClick { vm.epgDay(i - e.dayIndex) }
                        .clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text("▶", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (e.dayIndex < e.days.lastIndex) Palette.Focus else Palette.TextDim,
                modifier = Modifier.pointerClick { vm.epgDay(+1) })
        }
        Spacer(Modifier.height(12.dp))
        when {
            e.loading -> Text("Загрузка…", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.padding(24.dp))
            e.items.isEmpty() -> Text("Нет программы на этот день", fontSize = 20.sp, color = Palette.TextDim, modifier = Modifier.padding(24.dp))
        }
        val listAlpha by animateFloatAsState(if (e.loading) 0f else 1f, tween(if (e.loading) 90 else 220), label = "epgList")
        SmoothList(
            state, PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 6.dp), 6.dp,
            Modifier.alpha(listAlpha).onPreviewKeyEvent { ev -> listKey(ev, state, focusIndex, e.items.lastIndex, lastMove, wrap = false, ::jumpTo) },
        ) {
            itemsIndexed(e.items, key = { _, p -> p.code + p.startMs }) { i, p ->
                ProgrammeRow(
                    p, now, vm.canRecord(e.channel, p),
                    Modifier.focusRequester(requester(i)),
                    onFocus = { focusIndex = i; vm.onEpgFocus(p) },
                    onClick = { vm.onEpgSelect(p) },
                )
            }
        }
    }
    // Description of the focused programme under the preview.
    e.focused?.let { p ->
        Column(Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(top = InfoTop, end = PreviewEnd).width(PreviewWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ZalaTime.hhmm(p.startMs)}–${ZalaTime.hhmm(p.endMs)}", fontSize = 16.sp, color = Palette.Accent)
            Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.genre.isNotBlank()) Text(p.genre, fontSize = 15.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) AutoScrollText(p.description, Modifier.weight(1f, fill = false).padding(bottom = 16.dp))
        }
    }
    LaunchedEffect(e.items) {
        if (e.items.isNotEmpty()) {
            state.scrollToItem((startIndex - 2).coerceAtLeast(0))
            runCatching { requester(startIndex).requestFocus() }
        }
    }
}

@Composable
private fun ProgrammeRow(p: Programme, now: Long, recordable: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val onAir = now >= p.startMs && now < p.endMs
    val past = p.endMs <= now
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }.pointerClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = FocusStyle.colors(if (onAir) Color(0xFF243B55) else Palette.Row),
        border = FocusStyle.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ZalaTime.hhmm(p.startMs), fontSize = 20.sp, color = if (past) Palette.TextDim else Palette.Text, modifier = Modifier.width(68.dp))
            Column(Modifier.weight(1f)) {
                ScrollingLine(p.title, focused, fontSize = 20.sp, color = if (past && !recordable) Palette.TextDim else Palette.Text)
                if (onAir) ProgressBar(EpgLogic.progress(p.startMs, p.endMs, now), Modifier.fillMaxWidth(0.7f).padding(top = 6.dp))
            }
            when {
                onAir -> Text("● Сейчас", fontSize = 15.sp, color = Palette.Record)
                past && recordable -> Text("▶ Архив", fontSize = 15.sp, color = Palette.Accent)
            }
        }
    }
}

@Composable
fun Banner(ch: Channel, number: Int, now: Programme?, next: Programme?, nowMs: Long, detailed: Boolean) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xDD0B0F17)).padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$number", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Focus)
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
        // At most a third of the screen; the full text is in the programme list (→).
        val description = now?.description?.takeIf { detailed && it.isNotBlank() }
        if (description != null) Text(description, fontSize = 17.sp, color = Palette.Text, maxLines = 5, overflow = TextOverflow.Ellipsis)
        next?.let { Text("Далее: ${ZalaTime.hhmm(it.startMs)}  ${it.title}", fontSize = 19.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(if (detailed) "OK — каналы    → — программа передач" else "OK — описание    ← — каналы    → — программа передач", fontSize = 14.sp, color = Palette.TextDim)
    }
}

@UnstableApi
@Composable
fun BoxScope.ArchiveOverlay(a: ArchiveUi, paused: Boolean, vm: PlaybackViewModel) {
    val p = a.programme
    val now = vm.nowMs()
    val duration = (p.endMs - p.startMs).coerceAtLeast(1)
    val shownWall = a.seekTargetMs ?: (p.startMs + a.positionMs)
    // Always-visible mark so recordings are never confused with live TV; shows the moment being watched.
    Row(
        Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(bottomEnd = 8.dp)).background(Color(0x80000000)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("● АРХИВ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Record)
        Text("  ·  ${ZalaTime.relativeLabel(shownWall, now)}", fontSize = 14.sp, color = Palette.Text)
    }
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
        // Touch: tap or drag the bar to jump within the programme.
        Box(
            Modifier.fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var x = down.position.x
                        while (true) {
                            vm.seekToFraction(x / size.width, commit = false)
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            x = change.position.x
                            if (!change.pressed) break
                        }
                        vm.seekToFraction(x / size.width, commit = true)
                    }
                }
                .padding(vertical = 12.dp),
        ) { ProgressBar(((shownWall - p.startMs).toFloat() / duration), Modifier.fillMaxWidth()) }
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

private const val SLOW_CONNECT_MS = 3_000L

/** [target]: the stream being opened; a new channel starts the wait over. */
@Composable
fun BoxScope.StatusPill(phase: Phase, target: Any?, overPreview: Boolean, blackoutUntilMs: Long? = null) {
    // A normal channel switch shows only the banner; «Подключение…» only when it takes long.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(phase, target) {
        slow = false
        if (phase == Phase.CONNECTING) { delay(SLOW_CONNECT_MS); slow = true }
    }
    val text = when (phase) {
        Phase.CONNECTING -> "Подключение…".takeIf { slow }
        Phase.RECONNECTING -> "Восстанавливаем показ…"
        Phase.NO_NETWORK -> "Нет интернета, ждём…"
        Phase.BLACKOUT -> "Перерыв в вещании" + (blackoutUntilMs?.let { " до ${ZalaTime.hhmm(it)}" } ?: "")
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

/** One line; under the cursor a title too long for the row scrolls after a short pause. */
@Composable
fun ScrollingLine(text: String, scrolling: Boolean, fontSize: TextUnit, color: Color) {
    Text(
        text, fontSize = fontSize, color = color, maxLines = 1,
        overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (scrolling) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200, repeatDelayMillis = 2000) else Modifier,
    )
}

/** Programme description: when it doesn't fit, it slowly scrolls down, rests at the end and starts over. */
@Composable
private fun AutoScrollText(text: String, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        scroll.scrollTo(0)
        while (true) {
            delay(4000)
            if (scroll.maxValue == 0) continue
            scroll.animateScrollTo(scroll.maxValue, tween(durationMillis = scroll.maxValue * 40, easing = LinearEasing))
            delay(4000)
            scroll.animateScrollTo(0, tween(600))
        }
    }
    Text(text, fontSize = 15.sp, color = Palette.TextDim, modifier = modifier.verticalScroll(scroll, enabled = false))
}
