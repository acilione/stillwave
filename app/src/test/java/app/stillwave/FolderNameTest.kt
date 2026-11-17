package app.stillwave

import app.stillwave.data.*
import org.junit.Assert.*
import org.junit.Test

class FolderNameTest {
    @Test fun namesRejectBlanksReservedAndCaseInsensitiveDuplicates() {
        val folder = RecordingFolder(name = "Meetings")
        val existing = listOf(folder)
        listOf(" ", "all RECORDINGS", "Unfiled", " meetings ", "x".repeat(81)).forEach {
            assertNotNull(it, folderNameError(it, existing))
        }
        assertNull(folderNameError("Meetings", existing, folder.id))
        assertNull(folderNameError("Field notes", existing))
    }
}
