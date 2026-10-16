package app.zalatv.data.session

import app.zalatv.core.Clock
import app.zalatv.core.EventLog
import app.zalatv.data.api.BadCredentialsException
import app.zalatv.data.api.Session
import app.zalatv.data.api.SessionLostException
import app.zalatv.data.store.Credentials
import app.zalatv.data.store.CredentialsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionState {
    data object LoggedOut : SessionState
    data object Connecting : SessionState
    data class Active(val expiresAtMs: Long) : SessionState
    data object BadCredentials : SessionState
}

/** Minimal surface of ZalaApi used here, so tests can fake it. */
fun interface LoginCall {
    suspend fun login(login: String, password: String): Session
}

class NotLoggedInException : Exception("no saved credentials")

/**
 * Owns the portal session: silent login with saved credentials, proactive refresh before token expiry,
 * one shared re-login for concurrent callers, retry-once on [SessionLostException].
 */
class SessionManager(
    private val loginCall: LoginCall,
    private val credentials: CredentialsSource,
    private val clock: Clock,
    private val log: EventLog,
    private val heartbeatCall: suspend (Session) -> Unit = {},
    private val refreshBeforeMs: Long = 3600_000,
) {
    private val mutex = Mutex()
    private var current: Session? = null
    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = _state

    /** Valid session, logging in (once, shared) when missing or close to expiry. */
    suspend fun session(): Session {
        current?.takeIf { fresh(it) }?.let { return it }
        return mutex.withLock {
            current?.takeIf { fresh(it) } ?: loginLocked()
        }
    }

    private fun fresh(s: Session) = s.expiresAtMs - clock.nowMs() > refreshBeforeMs

    private suspend fun loginLocked(): Session {
        val c = credentials.load() ?: run {
            _state.value = SessionState.LoggedOut
            throw NotLoggedInException()
        }
        return doLogin(c)
    }

    private suspend fun doLogin(c: Credentials): Session {
        _state.value = SessionState.Connecting
        try {
            val s = loginCall.login(c.login, c.password)
            current = s
            _state.value = SessionState.Active(s.expiresAtMs)
            log.add("Вход выполнен, токен до ${app.zalatv.core.ZalaTime.relativeLabel(s.expiresAtMs, clock.nowMs())}")
            return s
        } catch (e: BadCredentialsException) {
            current = null
            _state.value = SessionState.BadCredentials
            log.add("Неверный логин/пароль (${e.code})")
            throw e
        } catch (e: Exception) {
            if (_state.value == SessionState.Connecting) _state.value = SessionState.LoggedOut
            log.add("Ошибка входа: ${e.javaClass.simpleName}")
            throw e
        }
    }

    /** Explicit login from the login screen; saves credentials only on success. */
    suspend fun loginWith(login: String, password: String): Session = mutex.withLock {
        val c = Credentials(login.trim(), password)
        val s = doLogin(c)
        credentials.save(c)
        s
    }

    /** Runs [block] with a session; on lost session re-logins once and retries. */
    suspend fun <T> withSession(block: suspend (Session) -> T): T {
        val s = session()
        return try {
            block(s)
        } catch (e: SessionLostException) {
            log.add("Сессия потеряна, перевход")
            invalidate(s)
            block(session())
        }
    }

    private suspend fun invalidate(stale: Session) = mutex.withLock {
        if (current === stale) current = null
    }

    suspend fun logout() = mutex.withLock {
        current = null
        credentials.clear()
        _state.value = SessionState.LoggedOut
        log.add("Выход из аккаунта")
    }

    /** Keeps the portal session alive while the app is visible. */
    fun startHeartbeat(scope: CoroutineScope, periodMs: Long = 5 * 60_000): Job = scope.launch {
        while (isActive) {
            delay(periodMs)
            try {
                withSession { heartbeatCall(it) }
            } catch (e: Exception) {
                log.add("Heartbeat: ${e.javaClass.simpleName}")
            }
        }
    }
}
