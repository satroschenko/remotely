package app.remotely.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Palette {
    val Background = Color(0xFF0B0F17)
    val Panel = Color(0xEE121A26)
    val PanelSolid = Color(0xFF121A26)
    val Row = Color(0xFF1B2533)
    val Focus = Color(0xFFFFD54F)
    val Text = Color(0xFFF5F7FA)
    val TextDim = Color(0xFFB0BAC8)
    val Accent = Color(0xFF4FC3F7)
    val Record = Color(0xFFE53935)
    val Progress = Color(0xFFFFD54F)
}

private val colors = darkColorScheme(
    primary = Palette.Focus,
    onPrimary = Color.Black,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Row,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Row,
    onSurfaceVariant = Palette.TextDim,
)

/** Scale applied on top of system font scale; LARGE is the default for parents. */
enum class FontSize(val scale: Float) { LARGE(1.0f), XLARGE(1.25f) }

@Composable
fun RemotelyTheme(fontSize: FontSize = FontSize.LARGE, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontSize.scale),
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

object FocusStyle {
    @Composable
    fun border() = ClickableSurfaceDefaults.border(
        focusedBorder = Border(BorderStroke(4.dp, Palette.Focus)),
    )

    @Composable
    fun scale() = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)

    @Composable
    fun colors(container: Color = Palette.Row) = ClickableSurfaceDefaults.colors(
        containerColor = container,
        contentColor = Palette.Text,
        focusedContainerColor = Color(0xFF2A3A50),
        focusedContentColor = Color.White,
        pressedContainerColor = Color(0xFF2A3A50),
    )
}
