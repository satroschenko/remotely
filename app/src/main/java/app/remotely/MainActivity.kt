package app.remotely

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import app.remotely.data.session.SessionState
import app.remotely.data.store.Settings
import app.remotely.ui.login.LoginScreen
import app.remotely.ui.playback.PlaybackScreen
import app.remotely.ui.settings.SettingsScreen
import app.remotely.ui.theme.Palette
import app.remotely.ui.theme.RemotelyTheme

enum class Screen { LOADING, LOGIN, PLAYBACK, SETTINGS }

@UnstableApi
class MainActivity : ComponentActivity() {
    private val container get() = (application as RemotelyApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBarsOnPhone()
        setContent {
            val settings by container.settings.settings.collectAsState(Settings())
            RemotelyTheme(settings.fontSize) { AppRoot(container) }
        }
    }

    // Back from a dialog, the keyboard or another app the bars may show again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBarsOnPhone()
    }

    /**
     * Phones and tablets: fullscreen like a video player, otherwise Android 15 draws the app under the
     * navigation bar and its edge is cut off; a swipe from the edge shows the bars for a moment.
     * TV boxes have no bars, nothing changes there.
     */
    private fun hideSystemBarsOnPhone() {
        val tv = getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (tv) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        container.onAppStart()
    }

    override fun onStop() {
        container.onAppStop()
        super.onStop()
    }
}

@UnstableApi
@Composable
private fun AppRoot(c: AppContainer) {
    var screen by remember { mutableStateOf(Screen.LOADING) }
    val sessionState by c.session.state.collectAsState()

    LaunchedEffect(Unit) { screen = if (c.hasCredentials()) Screen.PLAYBACK else Screen.LOGIN }
    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.LoggedOut && screen != Screen.LOADING && !c.hasCredentials()) screen = Screen.LOGIN
    }

    // Leaving the login screen: detach the on-screen keyboard, otherwise Gboard for TV
    // can keep swallowing D-pad keys even though it is hidden.
    val view = LocalView.current
    LaunchedEffect(screen) {
        if (screen != Screen.LOGIN) {
            val imm = view.context.getSystemService(InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            imm.restartInput(view)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        AnimatedContent(
            targetState = screen,
            // The camera cutout of a phone stays free of text and buttons.
            modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.LOADING -> Box(Modifier.fillMaxSize())
                Screen.LOGIN -> LoginScreen(c.session) { screen = Screen.PLAYBACK }
                Screen.PLAYBACK -> PlaybackScreen(c, onOpenSettings = { screen = Screen.SETTINGS }, onNeedLogin = { screen = Screen.LOGIN })
                Screen.SETTINGS -> SettingsScreen(c, onBack = { screen = Screen.PLAYBACK }, onLoggedOut = { screen = Screen.LOGIN })
            }
        }
    }
}
