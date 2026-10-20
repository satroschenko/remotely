package app.remotely.data.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.remotely.ui.theme.FontSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripAndDefaults() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ds = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb").also { it.delete() } }
        val store = SettingsStore(ds)
        assertEquals(Settings(), store.settings.first())
        store.update { it.copy(favorites = listOf("a", "b"), fontSize = FontSize.XLARGE, autostart = true) }
        store.update { it.copy(lastChannel = "a") }
        val s = store.settings.first()
        assertEquals(listOf("a", "b"), s.favorites)
        assertEquals(FontSize.XLARGE, s.fontSize)
        assertEquals("a", s.lastChannel)
        scope.cancel()
    }

    @Test fun fontSizeDefaultAndCycle() {
        assertEquals(FontSize.LARGE, Settings().fontSize)
        assertEquals(listOf(FontSize.XLARGE, FontSize.MEDIUM, FontSize.LARGE), listOf(FontSize.LARGE.next(), FontSize.XLARGE.next(), FontSize.MEDIUM.next()))
    }
}
