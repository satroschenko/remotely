package app.remotely.data.repo

import app.remotely.core.Clock
import app.remotely.data.api.OperatorMessage
import app.remotely.data.api.ZalaApi
import app.remotely.data.session.SessionManager
import app.remotely.data.store.SettingsSource
import kotlinx.coroutines.flow.first

/**
 * When the operator's announcement is shown: once per start, at most [MAX_SHOWS] starts per message (a new text starts over),
 * and not when the operator published it more than [MAX_AGE_MS] ago. Настройки → «Сообщения оператора» turns it off.
 */
object OperatorMessageLogic {
    const val MAX_SHOWS = 5
    const val MAX_AGE_MS = 7 * 24 * 3600_000L

    /** [key]: which message the [shows] count belongs to. */
    data class Seen(val key: String?, val shows: Int)

    fun key(m: OperatorMessage): String = m.text.hashCode().toString()

    fun shouldShow(m: OperatorMessage, nowMs: Long, seen: Seen, enabled: Boolean): Boolean {
        if (!enabled) return false
        if (m.publishedAtMs != null && nowMs - m.publishedAtMs > MAX_AGE_MS) return false
        return seen.key != key(m) || seen.shows < MAX_SHOWS
    }

    fun afterShown(m: OperatorMessage, seen: Seen): Seen =
        if (seen.key == key(m)) seen.copy(shows = seen.shows + 1) else Seen(key(m), 1)
}

class OperatorMessageRepository(
    private val session: SessionManager,
    private val api: ZalaApi,
    private val settings: SettingsSource,
    private val clock: Clock,
) {
    /** The message to show at this start, or null. */
    suspend fun forStart(): OperatorMessage? {
        val s = settings.settings.first()
        if (!s.operatorMessages) return null
        val m = session.withSession { api.operatorMessage(it) } ?: return null
        val seen = OperatorMessageLogic.Seen(s.operatorMessageKey, s.operatorMessageShows)
        return m.takeIf { OperatorMessageLogic.shouldShow(it, clock.nowMs(), seen, enabled = true) }
    }

    /** Counted once it has crossed the screen, not when it was fetched: a start that never showed it does not use up a turn. */
    suspend fun shown(m: OperatorMessage) = settings.update {
        val seen = OperatorMessageLogic.afterShown(m, OperatorMessageLogic.Seen(it.operatorMessageKey, it.operatorMessageShows))
        it.copy(operatorMessageKey = seen.key, operatorMessageShows = seen.shows)
    }
}
