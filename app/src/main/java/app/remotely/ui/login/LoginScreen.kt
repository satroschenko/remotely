package app.remotely.ui.login

import app.remotely.core.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.remotely.data.api.BadCredentialsException
import app.remotely.data.session.SessionManager
import app.remotely.ui.common.BigButton
import app.remotely.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(session: SessionManager, onLoggedIn: () -> Unit) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loginFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun submit() {
        if (busy) return
        if (login.isBlank() || password.isEmpty()) {
            error = tr("Введите логин и пароль", "Увядзіце лагін і пароль", "Enter your login and password")
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                session.loginWith(login, password)
                keyboard?.hide()
                focusManager.clearFocus(force = true)
                onLoggedIn()
            } catch (e: BadCredentialsException) {
                error = tr("Неверный логин или пароль", "Няправільны лагін або пароль", "Wrong login or password")
            } catch (e: Exception) {
                error = tr("Нет связи с Zala. Проверьте интернет и попробуйте ещё раз.", "Няма сувязі з Zala. Праверце інтэрнэт і паспрабуйце яшчэ раз.", "No connection to Zala. Check the internet connection and try again.")
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(tr("Remotely — вход", "Remotely — уваход", "Remotely — sign in"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text(tr("Логин и пароль от SMART ZALA", "Лагін і пароль ад SMART ZALA", "Your SMART ZALA login and password"), fontSize = 18.sp, color = Palette.TextDim)
            Field(tr("Логин", "Лагін", "Login"), login, { login = it }, false, Modifier.focusRequester(loginFocus))
            Field(tr("Пароль", "Пароль", "Password"), password, { password = it }, true, Modifier, onDone = ::submit)
            BigButton(if (busy) tr("Вход…", "Уваход…", "Signing in…") else tr("Войти", "Увайсці", "Sign in"), ::submit)
            error?.let { Text(it, fontSize = 18.sp, color = Palette.Record) }
        }
    }
    LaunchedEffect(Unit) { runCatching { loginFocus.requestFocus() } }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean,
    modifier: Modifier,
    onDone: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 16.sp, color = Palette.TextDim)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 22.sp),
            cursorBrush = SolidColor(Palette.Focus),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = modifier
                .width(420.dp)
                .onFocusChanged { focused = it.isFocused }
                .border(3.dp, if (focused) Palette.Focus else Palette.TextDim, RoundedCornerShape(10.dp))
                .background(Palette.Row, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
