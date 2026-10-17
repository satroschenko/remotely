package app.zalatv.data.repo

import app.zalatv.core.Clock
import app.zalatv.core.ZalaTime
import app.zalatv.data.api.Channel
import app.zalatv.data.api.Programme
import app.zalatv.data.api.ZalaApi
import app.zalatv.data.session.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class EpgDay(val date: LocalDate, val label: String)

object EpgLogic {
    const val DEFAULT_DEPTH_MS = 48 * 3600_000L

    /** Позавчера..Завтра, limited to what the archive can cover. */
    fun days(nowMs: Long, depthMs: Long): List<EpgDay> {
        val today = ZalaTime.localDate(nowMs)
        val oldest = ZalaTime.localDate(nowMs - depthMs)
        return (-2L..1L).map { today.plusDays(it) }
            .filter { !it.isBefore(oldest) }
            .map { EpgDay(it, ZalaTime.dayLabel(it, today)) }
    }

    fun current(list: List<Programme>, nowMs: Long): Programme? = list.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }

    fun next(list: List<Programme>, nowMs: Long): Programme? = list.firstOrNull { it.startMs > nowMs }

    /** The programme on air now followed by the next ones, in time order, without duplicates. */
    fun upcoming(list: List<Programme>, nowMs: Long, limit: Int = 12): List<Programme> =
        list.filter { it.endMs > nowMs }.distinctBy { it.code + it.startMs }.sortedBy { it.startMs }.take(limit)

    /** A recording can be played if the channel has archive, it already started and is inside the archive window. */
    fun canPlayRecording(p: Programme, ch: Channel, nowMs: Long, depthMs: Long): Boolean =
        ch.hasArchive && p.startMs < nowMs && p.startMs >= nowMs - depthMs

    fun progress(startMs: Long, endMs: Long, nowMs: Long): Float =
        if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/** EPG per channel and Minsk day; past days are cached for good, today/tomorrow refresh after 30 min. */
class EpgRepository(
    private val session: SessionManager,
    private val api: ZalaApi,
    private val clock: Clock,
) {
    private data class Entry(val list: List<Programme>, val fetchedAt: Long)

    private val mem = HashMap<Pair<String, LocalDate>, Entry>()
    private val lock = Mutex()
    @Volatile var depthMs: Long = EpgLogic.DEFAULT_DEPTH_MS
        private set

    suspend fun refreshDepth() {
        depthMs = runCatching { session.withSession { api.archiveDepthMs(it) } }.getOrDefault(depthMs)
    }

    fun cachedDay(channelCode: String, day: LocalDate): List<Programme>? = mem[channelCode to day]?.list

    suspend fun day(channelCode: String, day: LocalDate): List<Programme> {
        val key = channelCode to day
        val now = clock.nowMs()
        val today = ZalaTime.localDate(now)
        lock.withLock {
            mem[key]?.let { e ->
                if (day.isBefore(today) || now - e.fetchedAt < 30 * 60_000) return e.list
            }
        }
        val range = ZalaTime.dayRange(day)
        val list = session.withSession { api.programmes(it, channelCode, range.first, range.last + 1) }
            .filter { it.endMs > range.first && it.startMs <= range.last }
        lock.withLock {
            mem[key] = Entry(list, now)
            if (mem.size > 300) mem.keys.filter { it.second.isBefore(today.minusDays(3)) }.forEach { mem.remove(it) }
        }
        return list
    }

    /** Programmes around [atMs]: the Minsk day containing it plus neighbours if needed. */
    suspend fun around(channelCode: String, atMs: Long): List<Programme> {
        val d = ZalaTime.localDate(atMs)
        return (listOf(d.minusDays(1), d, d.plusDays(1)).flatMap { runCatching { day(channelCode, it) }.getOrDefault(emptyList()) })
            .distinctBy { it.code + it.startMs }.sortedBy { it.startMs }
    }
}
