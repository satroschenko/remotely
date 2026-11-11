package app.remotely.ui.playback

import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.remotely.core.tr
import app.remotely.ui.common.pointerClick
import app.remotely.ui.theme.Palette

/** A phone or tablet: a screen to tap. TV boxes have none, they get no buttons. */
@Composable
fun hasTouchScreen(): Boolean {
    val ctx = LocalContext.current
    return remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
}

/**
 * ⏸/▶ and the sound, for a phone: there is no remote to pause live TV or turn the sound off with.
 * Drawn shapes, not symbols: some devices draw symbols black.
 */
@Composable
fun PlayerButtons(paused: Boolean, muted: Boolean, onPlayPause: () -> Unit, onMute: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(if (paused) tr("Смотреть", "Глядзець", "Play") else tr("Пауза", "Паўза", "Pause"), onPlayPause) { c ->
            if (paused) playShape(c) else pauseShape(c)
        }
        RoundButton(if (muted) tr("Включить звук", "Уключыць гук", "Sound on") else tr("Выключить звук", "Выключыць гук", "Sound off"), onMute, active = muted) { c ->
            speakerShape(c, muted)
        }
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit, active: Boolean = false, draw: DrawScope.(Color) -> Unit) {
    val colour = if (active) Palette.Record else Palette.Focus
    Canvas(
        Modifier.size(44.dp).clip(CircleShape).border(2.dp, colour, CircleShape)
            .pointerClick(onClick = onClick).semantics { contentDescription = label },
    ) { draw(colour) }
}

private fun DrawScope.playShape(c: Color) {
    val w = size.width
    val path = Path().apply {
        moveTo(w * 0.40f, w * 0.30f)
        lineTo(w * 0.72f, w * 0.50f)
        lineTo(w * 0.40f, w * 0.70f)
        close()
    }
    drawPath(path, c)
}

private fun DrawScope.pauseShape(c: Color) {
    val w = size.width
    val bar = Size(w * 0.11f, w * 0.38f)
    drawRoundRect(c, Offset(w * 0.35f, w * 0.31f), bar, CornerRadius(w * 0.03f))
    drawRoundRect(c, Offset(w * 0.54f, w * 0.31f), bar, CornerRadius(w * 0.03f))
}

private fun DrawScope.speakerShape(c: Color, muted: Boolean) {
    val w = size.width
    val body = Path().apply {
        moveTo(w * 0.25f, w * 0.42f)
        lineTo(w * 0.35f, w * 0.42f)
        lineTo(w * 0.50f, w * 0.29f)
        lineTo(w * 0.50f, w * 0.71f)
        lineTo(w * 0.35f, w * 0.58f)
        lineTo(w * 0.25f, w * 0.58f)
        close()
    }
    drawPath(body, c)
    val stroke = w * 0.06f
    if (muted) {
        drawLine(c, Offset(w * 0.58f, w * 0.40f), Offset(w * 0.76f, w * 0.60f), stroke, StrokeCap.Round)
        drawLine(c, Offset(w * 0.76f, w * 0.40f), Offset(w * 0.58f, w * 0.60f), stroke, StrokeCap.Round)
    } else {
        drawArc(c, -45f, 90f, false, Offset(w * 0.40f, w * 0.38f), Size(w * 0.24f, w * 0.24f), style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(c, -45f, 90f, false, Offset(w * 0.31f, w * 0.29f), Size(w * 0.42f, w * 0.42f), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
