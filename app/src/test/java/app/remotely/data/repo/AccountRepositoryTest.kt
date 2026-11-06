package app.remotely.data.repo

import app.remotely.core.Clock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

class AccountRepositoryTest {
    @Test fun balanceIsAskedAtMostEveryFewMinutes() = runTest {
        var now = 0L
        var asked = 0
        val r = AccountRepository({ asked++; BigDecimal("24.05") }, { emptyList() }, Clock { now })
        r.refreshBalance()
        r.refreshBalance()
        assertEquals(1, asked)
        assertEquals(BigDecimal("24.05"), r.balance.value)
        now += AccountRepository.BALANCE_FRESH_MS
        r.refreshBalance()
        assertEquals(2, asked)
        r.refreshBalance(force = true)
        assertEquals(3, asked)
    }

    @Test fun failureKeepsWhatWasKnownAndLogoutForgetsIt() = runTest {
        var fail = false
        var now = 0L
        val r = AccountRepository({ if (fail) throw IOException("net") else BigDecimal("3") }, { throw IOException("net") }, Clock { now })
        r.refreshBalance()
        fail = true
        now += AccountRepository.BALANCE_FRESH_MS
        r.refreshBalance()
        assertEquals(BigDecimal("3"), r.balance.value)
        assertNull(r.packages())
        r.clear()
        assertNull(r.balance.value)
    }

    @Test fun roublesWithComma() = assertEquals("24,05 руб.", AccountRepository.roubles(BigDecimal("24.05")))
}
