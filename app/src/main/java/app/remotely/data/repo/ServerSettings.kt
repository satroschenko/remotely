package app.remotely.data.repo

import app.remotely.core.Clock
import app.remotely.core.EventLog
import app.remotely.data.store.SettingsBackup
import app.remotely.data.store.SettingsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Saves and loads the settings in the user's data cell on the Zala server — only when asked from the menu. */
class ServerSettings(
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
    private val settings: SettingsSource,
    private val appVersion: String,
    private val clock: Clock,
    private val log: EventLog,
) {
    sealed interface Result {
        data object Saved : Result
        data class Loaded(val savedAtMs: Long?) : Result
        /** Nothing saved yet. */
        data object Empty : Result
        /** The cell holds something that is not our settings. */
        data object Unreadable : Result
        data object Failed : Result
    }

    suspend fun save(): Result = guarded("сохранение") {
        val json = SettingsBackup.encode(settings.settings.first(), appVersion, clock.nowMs())
        if (json.length > SettingsBackup.MAX_CHARS) return@guarded Result.Failed
        write(SettingsBackup.wrap(json))
        Result.Saved
    }

    suspend fun load(): Result = guarded("загрузка") {
        val raw = read() ?: return@guarded Result.Empty
        val text = SettingsBackup.unwrap(raw) ?: return@guarded Result.Unreadable
        var savedAt: Long? = null
        var ok = false
        settings.update { cur ->
            val loaded = SettingsBackup.decode(text, cur) ?: return@update cur
            ok = true
            savedAt = loaded.savedAtMs
            loaded.settings
        }
        if (ok) Result.Loaded(savedAt) else Result.Unreadable
    }

    private suspend fun guarded(what: String, block: suspend () -> Result): Result = try {
        block().also { log.add("Настройки на сервере, $what: ${it.javaClass.simpleName}") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.add("Настройки на сервере, $what: ${e.javaClass.simpleName}")
        Result.Failed
    }

    companion object {
        /** Free cell (0–20 were empty except the official client's 8). */
        const val CELL = 17
    }
}
