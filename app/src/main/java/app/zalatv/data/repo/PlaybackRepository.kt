package app.zalatv.data.repo

import app.zalatv.data.api.Channel
import app.zalatv.data.api.Programme
import app.zalatv.data.api.ZalaApi
import app.zalatv.data.session.SessionManager

/** Source of fresh (single-use) stream URLs. */
interface StreamSource {
    suspend fun liveUrl(ch: Channel): String
    suspend fun archiveUrl(ch: Channel, p: Programme): String
}

class PlaybackRepository(private val session: SessionManager, private val api: ZalaApi) : StreamSource {
    override suspend fun liveUrl(ch: Channel): String = session.withSession { api.liveUrl(it, ch) }
    override suspend fun archiveUrl(ch: Channel, p: Programme): String = session.withSession { api.archiveUrl(it, ch, p.code) }
}
