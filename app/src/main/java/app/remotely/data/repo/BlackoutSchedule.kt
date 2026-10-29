package app.remotely.data.repo

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.data.api.BlackoutWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object BlackoutLogic {
    private const val DAY_MS = 24 * 3600_000L

    /** When the break [nowMs] falls into is over, or null outside the breaks. A window may run past midnight. */
    fun until(windows: List<BlackoutWindow>, nowMs: Long): Long? {
        val sinceMidnight = Math.floorMod(nowMs, DAY_MS)
        val sec = (sinceMidnight / 1000).toInt()
        val midnight = nowMs - sinceMidnight
        for (w in windows) {
            val inside = if (w.beginSec <= w.endSec) sec in w.beginSec..w.endSec else sec >= w.beginSec || sec <= w.endSec
            if (!inside) continue
            val end = midnight + (w.endSec + 1) * 1000L
            return if (end > nowMs) end else end + DAY_MS
        }
        return null
    }
}

/**
 * Zala's broadcast breaks (e.g. adult channels in the daytime): the official client does not show
 * a channel during them although the stream is served, the app does the same. Asked at start, then hourly.
 */
class BlackoutSchedule(
    private val load: suspend () -> Map<String, List<BlackoutWindow>>,
    private val clock: Clock,
    private val log: EventLog,
    /** The last schedule, so the first channel after a start is checked before the server answers. */
    private val file: File? = null,
    private val onLoaded: () -> Unit = {},
) {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(BlackoutWindow.serializer()))
    @Volatile private var windows: Map<String, List<BlackoutWindow>> =
        file?.let { f -> runCatching { Json.decodeFromString(serializer, f.readText()) }.getOrNull() }.orEmpty()
    private var job: Job? = null

    fun until(channelCode: String): Long? = windows[channelCode]?.let { BlackoutLogic.until(it, clock.nowMs()) }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val ok = runCatching { windows = load() }.isSuccess
                if (ok) withContext(Dispatchers.IO) { runCatching { file?.apply { parentFile?.mkdirs() }?.writeText(Json.encodeToString(serializer, windows)) } }
                if (ok) onLoaded() else log.add("Расписание перерывов вещания не загрузилось")
                delay(if (ok) REFRESH_MS else RETRY_MS)
            }
        }
    }

    companion object {
        const val REFRESH_MS = 3600_000L
        const val RETRY_MS = 5 * 60_000L
    }
}
