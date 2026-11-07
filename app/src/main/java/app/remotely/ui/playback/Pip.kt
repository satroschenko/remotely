package app.remotely.ui.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Picture in picture on phones and tablets: leaving the app while a channel or a recording plays shrinks the video
 * into a small window over other apps. Never on a TV. The activity asks [canEnter] and reports [active].
 */
object Pip {
    /** The app is in the small window: only the video is drawn. */
    var active by mutableStateOf(false)

    /** The watching screen is open and something plays (set by the screen). */
    var watching = false

    fun canEnter() = watching
}
