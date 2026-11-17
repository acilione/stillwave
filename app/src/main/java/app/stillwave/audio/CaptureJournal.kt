package app.stillwave.audio

import android.util.AtomicFile
import app.stillwave.data.Checkpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic edits keep the previous journal recoverable if the process dies during a checkpoint. */
object CaptureJournal {
    fun write(file: File, id: String, createdAt: Long, rate: Int, channels: Int, points: List<Checkpoint>, encoding: PcmEncoding = PcmEncoding.PCM16, inputName: String? = null, details: String? = null, folderId: String? = null, requestedQuality: String? = null,
        projectId: String? = null, projectOffsetMs: Long = 0, projectLoop: Boolean = false) {
        val json = JSONObject().put("id", id).put("createdAt", createdAt).put("rate", rate).put("channels", channels)
            .put("encoding", encoding.name).put("inputName", inputName).put("captureDetails", details).put("folderId", folderId).put("requestedQuality", requestedQuality)
            .put("projectId", projectId).put("projectOffsetMs", projectOffsetMs).put("projectLoop", projectLoop)
            .put("checkpoints", JSONArray().apply {
                points.forEach { put(JSONObject().put("id", it.id).put("positionMs", it.positionMs).put("label", it.label)) }
            })
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    fun read(file: File): JSONObject = AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) }
    fun points(json: JSONObject, durationMs: Long): List<Checkpoint> {
        val values = json.optJSONArray("checkpoints") ?: return emptyList()
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            Checkpoint(id = value.getString("id"), recordingId = json.getString("id"), positionMs = value.getLong("positionMs"), label = value.getString("label"))
        }.filter { it.positionMs in 0..durationMs }
    }
    fun delete(file: File) = AtomicFile(file).delete()
}
