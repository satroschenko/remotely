package app.remotely

import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.flow.first

@UnstableApi
class RemotelyApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before the first screen, so it does not flash in Russian.
        val language = kotlinx.coroutines.runBlocking { container.settings.settings.first().language }
        app.remotely.core.Lang.current = language
        container.api.language = language.portal
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.httpClient)
        .crossfade(false)
        .build()
}
