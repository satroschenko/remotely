package app.zalatv

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.util.UnstableApi
import app.zalatv.core.Clock
import app.zalatv.core.EventLog
import app.zalatv.core.NetworkMonitor
import app.zalatv.data.api.MemoryCookieJar
import app.zalatv.data.api.ZalaApi
import app.zalatv.data.api.zalaHttpClient
import app.zalatv.data.repo.ChannelRepository
import app.zalatv.data.repo.DiskCache
import app.zalatv.data.repo.EpgRepository
import app.zalatv.data.repo.PlaybackRepository
import app.zalatv.data.session.SessionManager
import app.zalatv.data.store.CredentialsStore
import app.zalatv.data.store.KeystoreCipherer
import app.zalatv.data.store.Quality
import app.zalatv.data.store.SettingsStore
import app.zalatv.player.ExoEngine
import app.zalatv.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Manual DI: one instance of everything for the whole app. */
@UnstableApi
class AppContainer(context: Context) {
    val appScope = MainScope()
    val clock = Clock.System
    val log = EventLog(clock)

    private val cookieJar = MemoryCookieJar()
    val httpClient = zalaHttpClient(cookieJar)
    val api = ZalaApi(httpClient, cookieJar)

    val settings = SettingsStore(PreferenceDataStoreFactory.create { File(context.filesDir, "datastore/settings.preferences_pb") })
    private val credentialsStore = CredentialsStore(
        PreferenceDataStoreFactory.create { File(context.filesDir, "datastore/credentials.preferences_pb") },
        KeystoreCipherer(),
    )
    val session = SessionManager(
        loginCall = { l, p -> api.login(l, p) },
        credentials = credentialsStore,
        clock = clock,
        log = log,
        heartbeatCall = { api.heartbeat(it) },
    )
    suspend fun hasCredentials() = credentialsStore.load() != null
    suspend fun currentLogin(): String? = credentialsStore.load()?.login

    private val cache = DiskCache(File(context.filesDir, "cache"))
    val channels = ChannelRepository(session, api, settings, cache, log)
    val epg = EpgRepository(session, api, clock)
    val playback = PlaybackRepository(session, api)

    val network = NetworkMonitor(context)
    val engine = ExoEngine(context, httpClient)
    val player = PlayerController(engine, playback, clock, appScope, log)

    private var heartbeat: Job? = null

    init {
        appScope.launch { network.available.collect { up -> player.onNetworkChanged(up); log.add(if (up) "Сеть: есть" else "Сеть: нет") } }
        appScope.launch {
            settings.settings.map { it.quality }.distinctUntilChanged().collect { player.setMaxQuality(it == Quality.MAX) }
        }
    }

    fun onAppStart() {
        heartbeat?.cancel()
        heartbeat = session.startHeartbeat(appScope)
        player.start()
    }

    fun onAppStop() {
        heartbeat?.cancel()
        player.stop()
    }
}
