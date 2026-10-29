package app.remotely.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.remotely.ui.theme.FontSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Video height cap; the steps Zala streams. MAX keeps its name: it is what older versions stored for 1080p. */
enum class Quality(val height: Int, val label: String) {
    AUTO(0, "Авто"), MAX(1080, "1080p"), P720(720, "720p"), P576(576, "576p"), P360(360, "360p");

    fun next(): Quality = entries[(ordinal + 1) % entries.size]
}

@Serializable
data class Settings(
    val favorites: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Custom order of channel codes; channels not listed follow by number. */
    val order: List<String> = emptyList(),
    val lastChannel: String? = null,
    val fontSize: FontSize = FontSize.LARGE,
    val quality: Quality = Quality.AUTO,
    val autostart: Boolean = false,
    /** Zala's subtitles where a channel has them (about a quarter of the channels). */
    val subtitles: Boolean = false,
    /** Look for a new version on start and once a day; off = only «Проверить сейчас». */
    val autoCheckUpdates: Boolean = true,
    /** The newest version already announced with a toast: each update is announced once. */
    val announcedUpdate: String? = null,
)

interface SettingsSource {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

class SettingsStore(private val dataStore: DataStore<Preferences>) : SettingsSource {
    private val key = stringPreferencesKey("settings")
    private val json = Json { ignoreUnknownKeys = true }

    override val settings: Flow<Settings> = dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            val cur = prefs[key]?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
            prefs[key] = json.encodeToString(Settings.serializer(), transform(cur))
        }
    }
}
