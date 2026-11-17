package app.stillwave

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.stillwave.data.RecordingDatabase
import app.stillwave.data.RecordingFolder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MigrationTest {
    @Test fun versionOneRecordingsAndEditsSurviveUpgrade() = runBlocking { upgrade(1) }
    @Test fun versionTwoRecordingsAndNamedCutsSurviveUpgrade() = runBlocking { upgrade(2) }
    @Test fun versionThreeRecordingsSurviveFolderUpgrade() = runBlocking { upgrade(3) }
    @Test fun versionFourWindowsSurviveEffectsUpgrade() = runBlocking { upgrade(4) }
    private suspend fun upgrade(version: Int) {
        val app = ApplicationProvider.getApplicationContext<StillwaveApp>()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = app.getDatabasePath(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("app.stillwave.data.RecordingDatabase/$version.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) }
            }
            old.insertOrThrow("recordings", null, ContentValues().apply {
                put("id", "source"); put("title", "Existing recording"); put("createdAt", 123456L)
                put("durationMs", 8000L); put("fileName", "source.wav"); put("sampleRate", 16000)
                put("channels", 1); put("pinned", 1); put("backupState", "Local only")
            })
            old.execSQL("INSERT INTO cut_windows (id, recordingId, startMs, endMs, selected, ordinal) VALUES ('cut', 'source', 100, 7000, 1, 0)")
            old.execSQL("INSERT INTO checkpoints (id, recordingId, positionMs, label) VALUES ('point', 'source', 1000, 'Existing checkpoint')")
            if (version >= 2) old.execSQL("UPDATE cut_windows SET title = 'Saved cut name'")
            old.version = version
        }
        val upgraded = Room.databaseBuilder(app, RecordingDatabase::class.java, name).addMigrations(RecordingDatabase.MIGRATION_1_2, RecordingDatabase.MIGRATION_2_3, RecordingDatabase.MIGRATION_3_4, RecordingDatabase.MIGRATION_4_5).build()
        try {
            val dao = upgraded.recordings()
            assertEquals("Existing recording", dao.get("source")!!.title)
            assertEquals(123456L, dao.get("source")!!.createdAt)
            assertTrue(dao.get("source")!!.pinned)
            assertEquals("PCM16", dao.get("source")!!.pcmEncoding)
            assertNull(dao.get("source")!!.inputName)
            assertNull(dao.get("source")!!.captureDetails)
            assertNull(dao.get("source")!!.folderId)
            assertTrue(dao.folders().isEmpty())
            val folder = RecordingFolder(name = "Migrated")
            dao.insertFolder(folder); dao.move(listOf("source"), folder.id)
            assertEquals(folder.id, dao.get("source")!!.folderId)
            dao.deleteFolder(folder.id)
            assertNull(dao.get("source")!!.folderId)
            val cut = dao.windows("source").single()
            assertEquals(if (version == 1) "" else "Saved cut name", cut.title); assertEquals(100L, cut.startMs); assertEquals(7000L, cut.endMs)
            assertFalse(cut.reverse); assertEquals(1.0, cut.speed, 0.0); assertFalse(cut.preservePitch)
            dao.window(cut.copy(title = "Renamed cut", reverse = true, speed = 0.5, preservePitch = true))
            assertEquals("Renamed cut", dao.windows("source").single().title)
            assertTrue(dao.windows("source").single().reverse)
            assertEquals(0.5, dao.windows("source").single().speed, 0.0)
            assertTrue(dao.windows("source").single().preservePitch)
            assertEquals("Existing checkpoint", dao.checkpoints("source").single().label)
        } finally { upgraded.close(); app.deleteDatabase(name) }
    }
}
