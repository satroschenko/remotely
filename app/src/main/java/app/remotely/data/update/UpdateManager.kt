package app.remotely.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class Release(val version: String, val apkUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    data class Downloaded(val release: Release, val apk: File) : UpdateState
    data class DownloadFailed(val release: Release) : UpdateState
}

object Versions {
    /** "v1.16" / "1.16" → [1, 16]; null when not a version. */
    fun parse(v: String): List<Int>? =
        v.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The latest-release JSON of the GitHub API → the release with its Remotely.apk asset. */
    fun parseRelease(body: String): Release? = runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        val tag = o["tag_name"]!!.jsonPrimitive.content
        val apk = o["assets"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content == "Remotely.apk" }
            ?.get("browser_download_url")?.jsonPrimitive?.content ?: return null
        Release(tag.removePrefix("v"), apk)
    }.getOrNull()
}

/** Asks GitHub for the latest release: on start, once a day and on demand. Never installs on its own. */
class UpdateManager(
    private val client: OkHttpClient,
    private val currentVersion: String,
    private val downloadDir: File,
    private val autoCheck: suspend () -> Boolean,
    private val latestUrl: String = "https://api.github.com/repos/satroschenko/remotely/releases/latest",
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var loop: Job? = null
    private var download: Job? = null
    private var lastCheckMs = 0L

    /** Called on every app start and when automatic checks are switched on: checks unless the last check is fresh, then once a day. */
    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) {
            scope.launch { if (autoCheck() && System.currentTimeMillis() - lastCheckMs > RECHECK_ON_START_MS) check(scope) }
            return
        }
        loop = scope.launch {
            while (isActive) {
                if (autoCheck()) check(scope)?.join()
                delay(DAY_MS)
            }
        }
    }

    /** Skipped while a download is running or already finished. */
    fun check(scope: CoroutineScope): Job? {
        when (_state.value) {
            UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Downloaded -> return null
            else -> {}
        }
        _state.value = UpdateState.Checking
        lastCheckMs = System.currentTimeMillis()
        return scope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder().url(latestUrl).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        Versions.parseRelease(r.body!!.string()) ?: error("no apk")
                    }
                }
            }
            _state.value = release.fold(
                onSuccess = { if (Versions.isNewer(it.version, currentVersion)) UpdateState.Available(it) else UpdateState.UpToDate },
                onFailure = { UpdateState.CheckFailed },
            )
        }
    }

    fun download(scope: CoroutineScope) {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.DownloadFailed -> s.release
            else -> return
        }
        if (download?.isActive == true) return
        _state.value = UpdateState.Downloading(release, 0)
        download = scope.launch {
            val apk = withContext(Dispatchers.IO) {
                runCatching {
                    downloadDir.mkdirs()
                    val part = File(downloadDir, "Remotely.apk.part")
                    val out = File(downloadDir, "Remotely.apk")
                    client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val body = r.body!!
                        val total = body.contentLength()
                        var done = 0L
                        body.byteStream().use { input ->
                            part.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        _state.update { s -> if (s is UpdateState.Downloading && s.percent != pct) s.copy(percent = pct) else s }
                                    }
                                }
                            }
                        }
                    }
                    if (!part.renameTo(out)) error("rename")
                    out
                }
            }
            _state.value = apk.fold(
                onSuccess = { UpdateState.Downloaded(release, it) },
                onFailure = { UpdateState.DownloadFailed(release) },
            )
        }
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val RECHECK_ON_START_MS = 60 * 60 * 1000L
    }
}
