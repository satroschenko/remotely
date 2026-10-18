package app.remotely.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import app.remotely.MainActivity
import app.remotely.RemotelyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Opens the app after the box boots when "Запускать при включении" is on. */
@UnstableApi
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as RemotelyApp
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (app.container.settings.settings.first().autostart) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
