package app.remotely.data.repo

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.data.api.Channel
import app.remotely.data.api.Programme
import app.remotely.data.api.Session
import app.remotely.data.api.ZalaApi
import app.remotely.data.session.SessionManager
import java.io.IOException

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
) : StreamSource {
    override suspend fun liveUrl(ch: Channel): String = session.withSession { api.liveUrl(it, ch) }

    /**
     * The recording when its manifest covers the programme; when it is cut short or missing, the same time
     * from the channel's timeshift stream (see [ArchiveManifest]). The manifest is already downloaded here,
     * so the player gets it as a data: URI and does not ask the server again.
     */
    override suspend fun archiveUrl(ch: Channel, p: Programme): String = session.withSession { s ->
        val toMs = minOf(p.endMs, clock.nowMs())
        val wantMs = toMs - p.startMs
        val tvodUrl = api.archiveUrl(s, ch, p.code)
        val tvod = try { api.manifest(tvodUrl) } catch (e: IOException) { null }
        val tvodMs = tvod?.let { ArchiveManifest.durationMs(it.second) } ?: 0L
        val recording = tvod?.let { (url, mpd) -> ArchiveManifest.withBase(mpd, url) }
        if (recording != null && tvodMs >= wantMs - SHORT_MS) return@withSession ArchiveManifest.dataUri(recording)

        log?.add("Запись «${p.title}» неполная (${tvodMs / 60_000} из ${wantMs / 60_000} мин) — беру из отложенного просмотра")
        val shifted = timeshift(s, ch, p.startMs, toMs)
        val shiftedMs = shifted?.let { ArchiveManifest.durationMs(it) } ?: 0L
        when {
            shifted != null && shiftedMs > tvodMs -> ArchiveManifest.dataUri(shifted)
            recording != null -> ArchiveManifest.dataUri(recording)
            else -> tvodUrl
        }
    }

    /** [fromMs]..[toMs] of the channel's timeshift stream, window after window; null when it gives nothing. */
    private suspend fun timeshift(s: Session, ch: Channel, fromMs: Long, toMs: Long): String? {
        val live = try { api.liveUrl(s, ch) } catch (e: IOException) { return null }
        val windows = mutableListOf<ArchiveManifest.Window>()
        var at = fromMs
        for (i in 0 until MAX_WINDOWS) {
            val url = ArchiveManifest.timeshiftUrl(live, at) ?: return null
            val (finalUrl, mpd) = try { api.manifest(url) } catch (e: IOException) { break }
            val end = ArchiveManifest.windowEndMs(mpd) ?: break
            windows += ArchiveManifest.Window(finalUrl, mpd)
            if (end >= toMs - SHORT_MS || end <= at) break
            at = end
        }
        return ArchiveManifest.fromTimeshift(windows, fromMs, toMs)
    }

    companion object {
        /** A recording this much shorter than the programme counts as complete (Zala's edges are not exact). */
        const val SHORT_MS = 60_000L
        /** A timeshift answer covers about 100 minutes: six of them are ten hours. */
        const val MAX_WINDOWS = 6
    }
}
