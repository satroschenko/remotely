package app.remotely.data.repo

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.data.api.Channel
import app.remotely.data.api.Programme
import app.remotely.data.api.Session
import app.remotely.data.api.ZalaApi
import app.remotely.data.session.SessionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/** Where past programmes are played from; switched here in code, not in the settings. */
enum class ArchiveSource {
    /** Zala's recording of the programme; the timeshift stream only when the recording is cut short or missing. */
    RECORDING_FIRST,
    /** The channel's timeshift stream («отложенный просмотр»), as the official client plays it; the recording only when that fails. */
    TIMESHIFT,
}

/** Source of fresh (single-use) stream URLs. */
interface StreamSource {
    suspend fun liveUrl(ch: Channel): String
    suspend fun archiveUrl(ch: Channel, p: Programme): String
}

class PlaybackRepository(
    private val session: SessionManager,
    private val api: ZalaApi,
    private val clock: Clock = Clock.System,
    private val log: EventLog? = null,
    private val source: ArchiveSource = ARCHIVE_SOURCE,
) : StreamSource {
    override suspend fun liveUrl(ch: Channel): String = session.withSession { api.liveUrl(it, ch) }

    /**
     * A past programme from [source] (see [ArchiveManifest]). The manifest is already downloaded here,
     * so the player gets it as a data: URI and does not ask the server again.
     */
    override suspend fun archiveUrl(ch: Channel, p: Programme): String = session.withSession { s ->
        val toMs = minOf(p.endMs, clock.nowMs())
        when (source) {
            ArchiveSource.TIMESHIFT -> timeshift(s, ch, p.startMs, toMs)?.let(ArchiveManifest::dataUri)
                ?: recording(s, ch, p, toMs, thenTimeshift = false).also { log?.add("Отложенный просмотр не ответил — беру запись «${p.title}»") }
            ArchiveSource.RECORDING_FIRST -> recording(s, ch, p, toMs, thenTimeshift = true)
        }
    }

    /** The recording when its manifest covers the programme; otherwise, with [thenTimeshift], the timeshift stream. */
    private suspend fun recording(s: Session, ch: Channel, p: Programme, toMs: Long, thenTimeshift: Boolean): String {
        val wantMs = toMs - p.startMs
        val tvodUrl = api.archiveUrl(s, ch, p.code)
        val tvod = try { api.manifest(tvodUrl) } catch (e: IOException) { null }
        val tvodMs = tvod?.let { ArchiveManifest.durationMs(it.second) } ?: 0L
        val recording = tvod?.let { (url, mpd) -> ArchiveManifest.withBase(mpd, url) }
        if (recording != null && (tvodMs >= wantMs - SHORT_MS || !thenTimeshift)) return ArchiveManifest.dataUri(recording)
        if (!thenTimeshift) return tvodUrl

        log?.add("Запись «${p.title}» неполная (${tvodMs / 60_000} из ${wantMs / 60_000} мин) — беру из отложенного просмотра")
        val shifted = timeshift(s, ch, p.startMs, toMs)
        val shiftedMs = shifted?.let { ArchiveManifest.durationMs(it) } ?: 0L
        return when {
            shifted != null && shiftedMs > tvodMs -> ArchiveManifest.dataUri(shifted)
            recording != null -> ArchiveManifest.dataUri(recording)
            else -> tvodUrl
        }
    }

    /**
     * [fromMs]..[toMs] of the channel's timeshift stream. All windows are asked for at once, a little apart so
     * they overlap (the overlap is dropped when they are put together); null when the stream gives nothing.
     */
    private suspend fun timeshift(s: Session, ch: Channel, fromMs: Long, toMs: Long): String? = coroutineScope {
        val live = try { api.liveUrl(s, ch) } catch (e: IOException) { return@coroutineScope null }
        val count = ((toMs - fromMs + WINDOW_STEP_MS - 1) / WINDOW_STEP_MS).toInt().coerceIn(1, MAX_WINDOWS)
        val answers = (0 until count).map { i ->
            async {
                val url = ArchiveManifest.timeshiftUrl(live, fromMs + i * WINDOW_STEP_MS) ?: return@async null
                try { api.manifest(url).let { (finalUrl, mpd) -> ArchiveManifest.Window(finalUrl, mpd) } } catch (e: IOException) { null }
            }
        }.awaitAll()
        // Up to the first window that failed: a gap in the middle would stall the player.
        ArchiveManifest.fromTimeshift(answers.takeWhile { it != null }.filterNotNull(), fromMs, toMs)
    }

    companion object {
        /** A recording this much shorter than the programme counts as complete (Zala's edges are not exact). */
        const val SHORT_MS = 60_000L
        /** A timeshift answer covers about 100 minutes; asking every 95 leaves an overlap. Six are ten hours. */
        const val WINDOW_STEP_MS = 95 * 60_000L
        const val MAX_WINDOWS = 6
        /** Released with TIMESHIFT; RECORDING_FIRST opens a little faster where Zala's recordings are whole. */
        val ARCHIVE_SOURCE = ArchiveSource.TIMESHIFT
    }
}
