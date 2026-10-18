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

enum class Quality { AUTO, MAX }

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
