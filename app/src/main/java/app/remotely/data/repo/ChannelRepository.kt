package app.remotely.data.repo

import app.remotely.core.EventLog
import app.remotely.data.api.Channel
import app.remotely.data.api.ZalaApi
import app.remotely.data.session.SessionManager
import app.remotely.data.store.Settings
import app.remotely.data.store.SettingsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable

@Serializable
data class ChannelSnapshot(val channels: List<Channel>, val subscribed: Set<Int>)

data class ChannelLists(
    /** Subscribed, not hidden, in user order. */
    val all: List<Channel>,
    /** Favorites in the order they were added. */
    val favorites: List<Channel>,
    /** Everything subscribed incl. hidden (settings screen). */
    val manageable: List<Channel>,
)

object ChannelArranger {
    fun arrange(snapshot: ChannelSnapshot, s: Settings): ChannelLists {
        val subscribed = snapshot.channels.filter { snapshot.subscribed.isEmpty() || it.number in snapshot.subscribed }
        val pos = s.order.withIndex().associate { it.value to it.index }
        val ordered = subscribed.sortedWith(compareBy<Channel> { pos[it.code] ?: Int.MAX_VALUE }.thenBy { it.number })
        val visible = ordered.filter { it.code !in s.hidden }
        val byCode = visible.associateBy { it.code }
        return ChannelLists(
            all = visible,
            favorites = s.favorites.mapNotNull { byCode[it] },
            manageable = ordered,
        )
    }
}

class ChannelRepository(
    private val session: SessionManager,
    private val api: ZalaApi,
    private val settings: SettingsSource,
    private val cache: DiskCache,
    private val log: EventLog,
) {
    private val snapshot = MutableStateFlow(ChannelSnapshot(emptyList(), emptySet()))
    val loaded: StateFlow<ChannelSnapshot> = snapshot

    val lists: Flow<ChannelLists> = combine(snapshot, settings.settings) { snap, s -> ChannelArranger.arrange(snap, s) }

    suspend fun loadCached() {
        cache.read(FILE, ChannelSnapshot.serializer())?.let { if (snapshot.value.channels.isEmpty()) snapshot.value = it }
    }

    suspend fun refresh() {
        val (channels, subscribed) = session.withSession { s -> api.channels(s) to runCatching { api.subscribedNumbers(s) }.getOrDefault(emptySet()) }
        if (channels.isEmpty()) return
        val snap = ChannelSnapshot(channels, subscribed)
        snapshot.value = snap
        cache.write(FILE, ChannelSnapshot.serializer(), snap)
        log.add("Каналы обновлены: ${channels.size}")
    }

    private companion object {
        const val FILE = "channels.json"
    }
}
