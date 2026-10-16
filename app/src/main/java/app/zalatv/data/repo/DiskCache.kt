package app.zalatv.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Tiny JSON-file cache for instant start. Failures are ignored (cache is optional). */
class DiskCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(serializer, File(dir, name).readText()) }.getOrNull()
    }

    suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            tmp.renameTo(File(dir, name))
        }
        Unit
    }
}
