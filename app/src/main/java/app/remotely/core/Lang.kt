package app.remotely.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/** Interface language; [portal] is what the Zala portal takes as `lan` (it translates categories and some programmes). */
@Serializable
enum class AppLanguage(val portal: String, val label: String) {
    RU("ru", "Русский"), BE("be", "Беларуская"), EN("en", "English");

    fun next(): AppLanguage = entries[(ordinal + 1) % entries.size]
}

/**
 * The language on screen. Compose state, so everything drawn follows a change at once; code outside the UI reads it
 * when it builds a text. Set from the settings at start and when the viewer changes it.
 */
object Lang {
    var current: AppLanguage by mutableStateOf(AppLanguage.RU)
}

/** The text in the current language. The translations sit next to the Russian, so a text is changed in one place. */
fun tr(ru: String, be: String, en: String): String = when (Lang.current) {
    AppLanguage.RU -> ru
    AppLanguage.BE -> be
    AppLanguage.EN -> en
}
