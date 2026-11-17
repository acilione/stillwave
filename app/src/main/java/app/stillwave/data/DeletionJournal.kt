package app.stillwave.data

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Durable intent bridges the Room transaction and filesystem unlink after confirmation. */
object DeletionJournal {
    fun file(directory: File, id: String): File {
        require(id.isNotBlank() && '/' !in id && '\\' !in id && id != "." && id != "..")
        return File(directory, "$id.deleting")
    }
    fun audioFile(directory: File, name: String): File {
        val result = File(directory, name).canonicalFile
        require(result.parentFile == directory.canonicalFile && result.extension in setOf("wav", "m4a")) { "Invalid recording path" }
        return result
    }
    fun write(directory: File, recording: Recording): File {
        audioFile(directory, recording.fileName)
        val file = file(directory, recording.id)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(JSONObject().put("id", recording.id).put("fileName", recording.fileName).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
        return file
    }
    fun read(file: File): Pair<String, String> = AtomicFile(file).openRead().bufferedReader().use {
        val value = JSONObject(it.readText()); value.getString("id") to value.getString("fileName")
    }
    fun delete(file: File) = AtomicFile(file).delete()
}
