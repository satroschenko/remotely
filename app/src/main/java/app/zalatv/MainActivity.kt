package app.zalatv

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.util.UnstableApi
import app.zalatv.data.session.SessionState
import app.zalatv.data.store.Settings
import app.zalatv.ui.login.LoginScreen
import app.zalatv.ui.playback.PlaybackScreen
import app.zalatv.ui.settings.SettingsScreen
import app.zalatv.ui.theme.Palette
import app.zalatv.ui.theme.ZalaTheme

enum class Screen { LOADING, LOGIN, PLAYBACK, SETTINGS }

@UnstableApi
class MainActivity : ComponentActivity() {
    private val container get() = (application as ZalaApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by container.settings.settings.collectAsState(Settings())
            ZalaTheme(settings.fontSize) { AppRoot(container) }
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
        when (screen) {
            Screen.LOADING -> Unit
            Screen.LOGIN -> LoginScreen(c.session) { screen = Screen.PLAYBACK }
            Screen.PLAYBACK -> PlaybackScreen(c, onOpenSettings = { screen = Screen.SETTINGS }, onNeedLogin = { screen = Screen.LOGIN })
            Screen.SETTINGS -> SettingsScreen(c, onBack = { screen = Screen.PLAYBACK }, onLoggedOut = { screen = Screen.LOGIN })
        }
    }
}
