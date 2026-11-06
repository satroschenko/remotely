package app.remotely.data.repo

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.data.api.UserPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal

/**
 * The account's balance (shown in the menu) and its packages (Настройки → «Подписка»). Asked for only when shown;
 * the balance at most every [BALANCE_FRESH_MS], so opening the menu again and again costs nothing.
 */
class AccountRepository(
    private val loadBalance: suspend () -> BigDecimal?,
    private val loadPackages: suspend () -> List<UserPackage>,
    private val clock: Clock,
    private val log: EventLog? = null,
) {
    private val _balance = MutableStateFlow<BigDecimal?>(null)
    /** Roubles; null until known (or when the portal gives none): then the menu shows nothing. */
    val balance: StateFlow<BigDecimal?> = _balance.asStateFlow()
    private var balanceAtMs: Long? = null

    suspend fun refreshBalance(force: Boolean = false) {
        val at = balanceAtMs
        if (!force && at != null && clock.nowMs() - at < BALANCE_FRESH_MS) return
        runCatching { loadBalance() }
            .onSuccess { _balance.value = it; balanceAtMs = clock.nowMs() }
            .onFailure { log?.add("Баланс: ${it.javaClass.simpleName}") }
    }

    /** Null when the portal did not answer. */
    suspend fun packages(): List<UserPackage>? =
        runCatching { loadPackages() }.onFailure { log?.add("Пакеты: ${it.javaClass.simpleName}") }.getOrNull()

    /** Logged out: another account's money must not show. */
    fun clear() {
        _balance.value = null
        balanceAtMs = null
    }

    companion object {
        const val BALANCE_FRESH_MS = 5 * 60_000L

        /** «24,05 руб.»; in English «24.05 BYN». */
        fun roubles(amount: BigDecimal): String {
            val n = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
            return app.remotely.core.tr("${n.replace('.', ',')} руб.", "${n.replace('.', ',')} руб.", "$n BYN")
        }
    }
}
