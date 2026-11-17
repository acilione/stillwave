package app.stillwave.audio

import app.stillwave.StillwaveApp
import app.stillwave.data.Recording
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** PCM view used by every edit path. Callers hold repository.withRecording while using it. */
object EditorAudioSource {
    private val decoding = Mutex()
    private val leases = mutableMapOf<String, Int>()
    private const val INACTIVE_BYTES = 64L * 1024 * 1024

    internal fun sourcePrefix(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } + "-"

    /** Called under the source repository lock; no decoder can still be using this source. */
    fun deleteForRecording(app: StillwaveApp, id: String) {
        val prefix = sourcePrefix(id)
        File(app.cacheDir, "decoded-audio").listFiles { file -> file.name.startsWith(prefix) }?.forEach {
            check(!it.exists() || it.delete()) { "Could not remove decoded audio cache" }
        }
    }

    suspend fun <T> withWav(app: StillwaveApp, recording: Recording, action: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val original = app.repository.file(recording)
        if (original.extension.equals("wav", ignoreCase = true)) return@withContext action(original)
        require(original.extension.equals("m4a", ignoreCase = true)) { "Unsupported recording format" }
        val directory = File(app.cacheDir, "decoded-audio").apply { check(isDirectory || mkdirs()) { "Could not create editing storage" } }
        val fingerprint = "${recording.id}:${original.length()}:${original.lastModified()}"
        val key = sourcePrefix(recording.id) + MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val decoded = File(directory, "$key.wav")
        decoding.withLock {
            prune(directory, key)
            if (!decoded.isFile) {
                val expected = ((recording.durationMs + 1000) / 1000.0 * recording.sampleRate * recording.channels * 2).toLong()
                require(expected <= WavFile.MAX_DATA_BYTES) { "This recording exceeds the WAV editing size limit" }
                check(directory.usableSpace >= expected + 10L * 1024 * 1024) { "Not enough free space to decode this recording for editing" }
                val temporary = File(directory, "$key.decoding")
                temporary.delete()
                try {
                    val coroutine = currentCoroutineContext()
                    AacFile.decode(original, temporary) { coroutine.ensureActive() }
                    coroutine.ensureActive()
                    check(temporary.renameTo(decoded)) { "Could not prepare audio for editing" }
                } finally { temporary.delete() }
            }
            decoded.setLastModified(System.currentTimeMillis())
            leases[key] = (leases[key] ?: 0) + 1
        }
        try { action(decoded) }
        finally {
            withContext(NonCancellable) {
                decoding.withLock {
                    val remaining = (leases[key] ?: 1) - 1
                    if (remaining == 0) leases.remove(key) else leases[key] = remaining
                    prune(directory)
                }
            }
        }
    }

    private fun prune(directory: File, candidate: String? = null) {
        // Only one decode runs at a time; abandoned partial files are never reused.
        directory.listFiles { file -> file.extension == "decoding" }?.forEach { it.delete() }
        var bytes = 0L
        directory.listFiles { file -> file.extension == "wav" && file.nameWithoutExtension != candidate && file.nameWithoutExtension !in leases }
            .orEmpty().sortedByDescending(File::lastModified).forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= 3 || bytes > INACTIVE_BYTES) file.delete()
            }
    }
}
