package app.remotely.data.session

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.data.api.BadCredentialsException
import app.remotely.data.api.Session
import app.remotely.data.api.SessionLostException
import app.remotely.data.store.Credentials
import app.remotely.data.store.CredentialsSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeCreds(var c: Credentials? = Credentials("u", "p")) : CredentialsSource {
    override suspend fun load() = c
    override suspend fun save(c: Credentials) { this.c = c }
    override suspend fun clear() { c = null }
}

class SessionManagerTest {
    private var now = 1_000_000_000_000L
    private val clock = Clock { now }
    private var logins = 0
    private var attempts = 0
    private var failWith: Exception? = null

    private fun manager(creds: CredentialsSource = FakeCreds()) = SessionManager(
        loginCall = { _, _ ->
            delay(10)
            attempts++
            failWith?.let { throw it }
            logins++
            Session("e", "f", "T$logins", now + 24 * 3600_000L)
        },
        credentials = creds,
        clock = clock,
        log = EventLog(clock),
    )

    @Test fun refusedPasswordIsNotTriedAgainAutomatically() = runTest {
        val creds = FakeCreds()
        val m = manager(creds)
        failWith = BadCredentialsException("70116206")
        repeat(4) {
            val e = runCatching { m.withSession { it.userToken } }.exceptionOrNull()
            assertEquals("70116206", (e as BadCredentialsException).code)
        }
        assertEquals(1, attempts)
        assertEquals(SessionState.BadCredentials, m.state.value)
        assertEquals(Credentials("u", "p"), creds.c)
    }

    @Test fun networkFailureIsStillRetried() = runTest {
        val m = manager()
        failWith = java.io.IOException("no route")
        repeat(3) { runCatching { m.session() } }
        assertEquals(3, attempts)
    }

    @Test fun newPasswordFromTheLoginScreenEndsTheStop() = runTest {
        val m = manager()
        failWith = BadCredentialsException("70116206")
        runCatching { m.session() }
        failWith = null
        assertEquals("T1", m.loginWith("u", "new").userToken)
        now += 24 * 3600_000L
        assertEquals("T2", m.session().userToken)
    }

    @Test fun refusedNewPasswordOnTheLoginScreenIsReported() = runTest {
        val m = manager()
        failWith = BadCredentialsException("70116206")
        runCatching { m.loginWith("u", "bad") }
        runCatching { m.loginWith("u", "bad2") }
        assertEquals(2, attempts)
    }

    @Test fun logoutEndsTheStop() = runTest {
        val creds = FakeCreds()
        val m = manager(creds)
        failWith = BadCredentialsException("70116206")
        runCatching { m.session() }
        m.logout()
        creds.c = Credentials("u2", "p2")
        failWith = null
        assertEquals("T1", m.session().userToken)
    }

    @Test fun concurrentCallersShareOneLogin() = runTest {
        val m = manager()
        val tokens = (1..5).map { async { m.session().userToken } }.awaitAll()
        assertEquals(1, logins)
        assertEquals(setOf("T1"), tokens.toSet())
    }

    @Test fun proactiveRefreshBeforeExpiry() = runTest {
        val m = manager()
        m.session()
        now += 22 * 3600_000L // 2h left: still fresh
        assertEquals("T1", m.session().userToken)
        now += 1 * 3600_000L + 1 // <1h left
        assertEquals("T2", m.session().userToken)
    }

    @Test fun sessionLostRelogsInOnceAndRetries() = runTest {
        val m = manager()
        var calls = 0
        val r = m.withSession { s ->
            calls++
            if (s.userToken == "T1") throw SessionLostException("x")
            s.userToken
        }
        assertEquals("T2", r)
        assertEquals(2, calls)
    }

    @Test fun badCredentialsSetsStateAndThrows() = runTest {
        val m = manager()
        failWith = BadCredentialsException("70116206")
        assertThrows(BadCredentialsException::class.java) { kotlinx.coroutines.runBlocking { m.session() } }
        assertEquals(SessionState.BadCredentials, m.state.value)
    }

    @Test fun noCredentials() = runTest {
        val m = manager(FakeCreds(null))
        assertThrows(NotLoggedInException::class.java) { kotlinx.coroutines.runBlocking { m.session() } }
    }

    @Test fun loginWithSavesOnlyOnSuccess() = runTest {
        val creds = FakeCreds(null)
        val m = manager(creds)
        failWith = BadCredentialsException("70116206")
        runCatching { m.loginWith("a", "b") }
        assertNull(creds.c)
        failWith = null
        m.loginWith(" a ", "b")
        assertEquals(Credentials("a", "b"), creds.c)
        m.logout()
        assertNull(creds.c)
    }
}
