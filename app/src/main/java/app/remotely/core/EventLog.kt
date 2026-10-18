package app.remotely.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class LogEvent(val timeMs: Long, val text: String)

/** Ring buffer of recent events for the diagnostics screen. Never put secrets here. */
class EventLog(private val clock: Clock = Clock.System, private val capacity: Int = 50) {
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events

    fun add(text: String) {
        android.util.Log.i("Remotely", text)
        _events.update { (listOf(LogEvent(clock.nowMs(), text)) + it).take(capacity) }
    }
}
