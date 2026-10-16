package app.zalatv.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** One place for all UI motion so transitions feel consistent. */
object Motion {
    private const val IN = 220
    private const val OUT = 170
    private val ease = FastOutSlowInEasing

    /** Panels coming from / going to the left (channel list, menu). */
    val fromLeft: EnterTransition = fadeIn(tween(IN)) + slideInHorizontally(tween(IN, easing = ease)) { -it / 6 }
    val toLeft: ExitTransition = fadeOut(tween(OUT)) + slideOutHorizontally(tween(OUT, easing = ease)) { -it / 8 }

    /** Panels coming from / going to the right (programme guide: "→" moves forward). */
    val fromRight: EnterTransition = fadeIn(tween(IN)) + slideInHorizontally(tween(IN, easing = ease)) { it / 6 }
    val toRight: ExitTransition = fadeOut(tween(OUT)) + slideOutHorizontally(tween(OUT, easing = ease)) { it / 8 }

    /** Bars at the bottom (channel banner, recording controls). */
    val fromBottom: EnterTransition = fadeIn(tween(IN)) + slideInVertically(tween(IN, easing = ease)) { it / 3 }
    val toBottom: ExitTransition = fadeOut(tween(OUT)) + slideOutVertically(tween(OUT, easing = ease)) { it / 3 }

    val fromTop: EnterTransition = fadeIn(tween(IN)) + slideInVertically(tween(IN, easing = ease)) { -it / 2 }
    val toTop: ExitTransition = fadeOut(tween(OUT)) + slideOutVertically(tween(OUT, easing = ease)) { -it / 2 }

    /** Dialogs and pills. */
    val pop: EnterTransition = fadeIn(tween(IN)) + scaleIn(tween(IN, easing = ease), initialScale = 0.92f)
    val unpop: ExitTransition = fadeOut(tween(OUT)) + scaleOut(tween(OUT), targetScale = 0.96f)

    val fade: EnterTransition = fadeIn(tween(IN))
    val unfade: ExitTransition = fadeOut(tween(OUT))
}

/**
 * AnimatedVisibility for nullable content: keeps showing the last non-null value while animating out.
 */
@Composable
fun <T : Any> AnimatedNullable(
    value: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = Motion.pop,
    exit: ExitTransition = Motion.unpop,
    content: @Composable (T) -> Unit,
) {
    var last by remember { mutableStateOf(value) }
    if (value != null) last = value
    AnimatedVisibility(visible = value != null, modifier = modifier, enter = enter, exit = exit) {
        last?.let { content(it) }
    }
}
