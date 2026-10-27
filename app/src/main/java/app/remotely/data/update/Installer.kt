package app.remotely.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

enum class InstallResult { STARTED, NEEDS_PERMISSION, FAILED }

/** Hands the downloaded APK to the system installer; the user confirms there. */
object Installer {
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, apk: File): InstallResult {
        // Android 8+: the user allows Remotely to install apps once.
        if (!canInstall(ctx)) {
            val ask = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (runCatching { ctx.startActivity(ask) }.isSuccess) InstallResult.NEEDS_PERMISSION else InstallResult.FAILED
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { ctx.startActivity(intent) }.isSuccess) InstallResult.STARTED else InstallResult.FAILED
    }
}
