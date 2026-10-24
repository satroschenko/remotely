package app.remotely.data.repo

import app.remotely.core.EventLog
import app.remotely.data.api.Channel
import app.remotely.data.api.ZalaApi
import app.remotely.data.session.SessionManager
import app.remotely.data.store.Settings
import app.remotely.data.store.SettingsSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable

/** A portal category with the codes of its channels. */
@Serializable
data class GroupSnapshot(val code: String, val name: String, val channels: List<String>)

@Serializable
data class ChannelSnapshot(val channels: List<Channel>, val subscribed: Set<Int>, val groups: List<GroupSnapshot> = emptyList())

/** A category as shown in the menu: its channels in the user's order, hidden ones left out. */
data class ChannelGroup(val code: String, val name: String, val channels: List<Channel>)

data class ChannelLists(
    /** Subscribed, not hidden, in user order. */
    val all: List<Channel>,
    /** Favorites in the order they were added. */
    val favorites: List<Channel>,
    /** Everything subscribed incl. hidden (settings screen). */
    val manageable: List<Channel>,
    /** Portal categories that have at least one visible channel. */
    val groups: List<ChannelGroup> = emptyList(),
) {
    /** Numbers follow the user order (1, 2, 3...), like in the official app; the portal number is kept for the API. */
    private val numbers: Map<String, Int> = all.withIndex().associate { it.value.code to it.index + 1 }

    fun numberOf(ch: Channel): Int = numbers[ch.code] ?: ch.number
}

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
            groups = snapshot.groups.mapNotNull { g ->
                val codes = g.channels.toSet()
                visible.filter { it.code in codes }.takeIf { it.isNotEmpty() }?.let { ChannelGroup(g.code, g.name, it) }
            },
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
        val snap = ChannelSnapshot(channels, subscribed, snapshot.value.groups)
        snapshot.value = snap
        cache.write(FILE, ChannelSnapshot.serializer(), snap)
        log.add("Каналы обновлены: ${channels.size}")
    }

    /** Categories change rarely and cost a request each, so they are fetched apart from the channel list. */
    suspend fun refreshGroups() {
        val groups = session.withSession { s ->
            coroutineScope {
                api.columns(s).map { col -> async { GroupSnapshot(col.code, col.name, api.channels(s, col.code).map { it.code }) } }.awaitAll()
            }
        }.filter { it.channels.isNotEmpty() }
        if (groups.isEmpty()) return
        val snap = snapshot.value.copy(groups = groups)
        snapshot.value = snap
        if (snap.channels.isNotEmpty()) cache.write(FILE, ChannelSnapshot.serializer(), snap)
        log.add("Категории обновлены: ${groups.size}")
    }

    private companion object {
        const val FILE = "channels.json"
    }
}
