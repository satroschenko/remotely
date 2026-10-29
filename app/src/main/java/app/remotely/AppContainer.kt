package app.remotely

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.util.UnstableApi
import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.core.NetworkMonitor
import app.remotely.data.api.MemoryCookieJar
import app.remotely.data.api.ZalaApi
import app.remotely.data.api.zalaHttpClient
import app.remotely.data.repo.BlackoutSchedule
import app.remotely.data.repo.ChannelRepository
import app.remotely.data.repo.DiskCache
import app.remotely.data.repo.EpgRepository
import app.remotely.data.repo.PlaybackRepository
import app.remotely.data.session.SessionManager
import app.remotely.data.store.CredentialsStore
import app.remotely.data.store.KeystoreCipherer
import app.remotely.data.store.SettingsStore
import app.remotely.data.update.UpdateManager
import app.remotely.player.ExoEngine
import app.remotely.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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
    val blackouts: BlackoutSchedule = BlackoutSchedule({ session.withSession { api.blackouts(it) } }, clock, log, File(context.filesDir, "cache/blackouts.json"), onLoaded = { player.recheckBlackout() })
    val player: PlayerController = PlayerController(engine, playback, clock, appScope, log, blackoutUntil = { blackouts.until(it.code) })

    val updates = UpdateManager(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), BuildConfig.VERSION_NAME, File(context.cacheDir, "updates"), autoCheck = { settings.settings.first().autoCheckUpdates })

    private var heartbeat: Job? = null

    init {
        appScope.launch { network.available.collect { up -> player.onNetworkChanged(up); log.add(if (up) "Сеть: есть" else "Сеть: нет") } }
        appScope.launch {
            settings.settings.map { it.quality }.distinctUntilChanged().collect { player.setMaxHeight(it.height) }
        }
        appScope.launch {
            settings.settings.map { it.subtitles }.distinctUntilChanged().collect { player.setSubtitles(it) }
        }
    }

    fun onAppStart() {
        heartbeat?.cancel()
        heartbeat = session.startHeartbeat(appScope)
        blackouts.start(appScope)
        player.start()
        updates.start(appScope)
    }

    fun onAppStop() {
        heartbeat?.cancel()
        player.stop()
    }
}
