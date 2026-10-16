package app.zalatv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.zalatv.ui.theme.FocusStyle
import app.zalatv.ui.theme.Palette

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
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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

/** Centered modal card with a message and buttons; the first button gets focus. */
@Composable
fun MessageDialog(title: String, message: String?, buttons: List<Pair<String, () -> Unit>>) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(Palette.PanelSolid).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text, textAlign = TextAlign.Center)
            if (message != null) Text(message, fontSize = 18.sp, color = Palette.TextDim, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                buttons.forEachIndexed { i, (label, action) ->
                    BigButton(label, action, focusRequester = if (i == 0) first else null)
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
