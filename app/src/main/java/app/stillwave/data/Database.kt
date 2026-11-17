package app.stillwave.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM folders ORDER BY nameKey, id") fun observeFolders(): Flow<List<RecordingFolder>>
    @Query("SELECT * FROM folders ORDER BY nameKey, id") suspend fun folders(): List<RecordingFolder>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): RecordingFolder?
    @Insert suspend fun insertFolder(folder: RecordingFolder)
    @Query("UPDATE folders SET name = :name, nameKey = :nameKey WHERE id = :id") suspend fun renameFolder(id: String, name: String, nameKey: String)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: String)
    @Query("UPDATE recordings SET folderId = :folderId WHERE id IN (:ids)") suspend fun move(ids: List<String>, folderId: String?)
    @Query("SELECT * FROM recordings ORDER BY pinned DESC, createdAt DESC") fun observeAll(): Flow<List<Recording>>
    @Query("SELECT * FROM recordings WHERE id = :id") fun observe(id: String): Flow<Recording?>
    @Query("SELECT * FROM recordings WHERE id = :id") suspend fun get(id: String): Recording?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(recording: Recording)
    @Query("DELETE FROM recordings WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE recordings SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)
    @Query("UPDATE recordings SET pinned = :pinned WHERE id IN (:ids)") suspend fun pin(ids: List<String>, pinned: Boolean)
    @Query("SELECT * FROM checkpoints WHERE recordingId = :id ORDER BY positionMs") fun observeCheckpoints(id: String): Flow<List<Checkpoint>>
    @Query("SELECT * FROM checkpoints WHERE recordingId = :id ORDER BY positionMs") suspend fun checkpoints(id: String): List<Checkpoint>
    @Upsert suspend fun checkpoint(checkpoint: Checkpoint)
    @Delete suspend fun deleteCheckpoint(checkpoint: Checkpoint)
    @Query("SELECT * FROM cut_windows WHERE recordingId = :id ORDER BY ordinal") fun observeWindows(id: String): Flow<List<CutWindow>>
    @Query("SELECT * FROM cut_windows WHERE recordingId = :id ORDER BY ordinal") suspend fun windows(id: String): List<CutWindow>
    @Upsert suspend fun window(window: CutWindow)
    @Delete suspend fun deleteWindow(window: CutWindow)
}

@Database(entities = [Recording::class, Checkpoint::class, CutWindow::class, RecordingFolder::class], version = 5, exportSchema = true)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cut_windows ADD COLUMN reverse INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cut_windows ADD COLUMN speed REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE cut_windows ADD COLUMN preservePitch INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS folders (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, nameKey TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_folders_nameKey ON folders (nameKey)")
                db.execSQL("ALTER TABLE recordings ADD COLUMN folderId TEXT DEFAULT NULL REFERENCES folders(id) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recordings_folderId ON recordings (folderId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN pcmEncoding TEXT NOT NULL DEFAULT 'PCM16'")
                db.execSQL("ALTER TABLE recordings ADD COLUMN inputName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE recordings ADD COLUMN captureDetails TEXT DEFAULT NULL")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cut_windows ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
