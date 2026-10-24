package app.remotely.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.remotely.ui.theme.FocusStyle
import app.remotely.ui.theme.Palette

/**
 * tv-material3 surfaces react only to the remote. This adds a mouse click / finger tap (and a long press)
 * for TV boxes with a mouse and for phones.
 */
fun Modifier.pointerClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    pointerInput(Unit) {
        detectTapGestures(onLongPress = { longClick?.invoke() ?: click() }, onTap = { click() })
    }
}

/** Big focusable button with the yellow focus frame. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).pointerClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = FocusStyle.colors(),
        border = FocusStyle.border(),
        scale = FocusStyle.scale(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Centered modal card with a message and buttons; the first button gets focus and cannot leave the card. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MessageDialog(title: String, message: String?, buttons: List<Pair<String, () -> Unit>>) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(Palette.PanelSolid).padding(28.dp)
                // The screen behind stays composed: without this the arrows walk out to its buttons.
                .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text, textAlign = TextAlign.Center)
            if (message != null) Text(message, fontSize = 18.sp, color = Palette.TextDim, textAlign = TextAlign.Center)
            if (buttons.size <= 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    buttons.forEachIndexed { i, (label, action) ->
                        BigButton(label, action, focusRequester = if (i == 0) first else null)
                    }
                }
            } else {
                // More buttons do not fit in a row: stack them, one action per line.
                Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    buttons.forEachIndexed { i, (label, action) ->
                        BigButton(label, action, Modifier.fillMaxWidth(), focusRequester = if (i == 0) first else null)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Progress) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(color))
    }
}

val PanelPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp)
