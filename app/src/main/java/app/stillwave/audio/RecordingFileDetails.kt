package app.stillwave.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** A snapshot of the real file, independent of editable library metadata. Call on an I/O thread. */
data class RecordingFileDetails(val sizeBytes: Long?, val audio: WavFile.Info?, val error: String? = null, val compressed: AacFile.Info? = null) {
    companion object {
        fun read(file: File): RecordingFileDetails {
            val size = try {
                if (!file.isFile) return RecordingFileDetails(null, null,
                    if (!file.exists()) "The audio file is missing from this device." else "The audio file could not be read.")
                RandomAccessFile(file, "r").use { it.length() }
            } catch (_: Exception) {
                return RecordingFileDetails(null, null, "The audio file could not be read.")
            }
            return try {
                if (file.extension.equals("m4a", ignoreCase = true)) RecordingFileDetails(size, null, compressed = AacFile.info(file))
                else RecordingFileDetails(size, WavFile.info(file))
            }
            catch (_: Exception) {
                RecordingFileDetails(size, null, "The audio format could not be read. The file may be incomplete or unsupported.")
            }
        }
    }
}

/** Decimal megabytes, including WAV headers/padding and other bytes on disk. */
fun fileSizeMb(bytes: Long, locale: Locale = Locale.getDefault()): String {
    require(bytes >= 0)
    return if (bytes in 1..9_999) "<${String.format(locale, "%.2f", 0.01)} MB"
        else String.format(locale, "%.2f MB", bytes / 1_000_000.0)
}

fun pcmBitRateLabel(info: WavFile.Info, locale: Locale = Locale.getDefault()): String {
    val bitRate = info.rate.toLong() * info.frameSize * 8
    return if (bitRate % 1000 == 0L) "${bitRate / 1000} kbps" else String.format(locale, "%.1f kbps", bitRate / 1000.0)
}
