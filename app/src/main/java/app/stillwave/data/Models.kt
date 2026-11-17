package app.stillwave.data

import androidx.room.*
import app.stillwave.audio.PcmEncoding
import app.stillwave.audio.PcmFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class Quality private constructor(val name: String, val label: String, val sampleRate: Int, val channels: Int,
    val encoding: PcmEncoding = PcmEncoding.PCM16, val aacBitRate: Int? = null) {
    companion object {
        // Keep the original persisted names and WAV formats stable across upgrades.
        val VOICE = Quality("VOICE", "Voice", 16000, 1)
        val BALANCED = Quality("BALANCED", "Balanced", 44100, 1)
        val STUDIO = Quality("STUDIO", "Studio", 48000, 2)
        val BEST = Quality("BEST", "Best available", 0, 0)
        val COMPACT = Quality("COMPACT", "Compact", 8000, 1)
        val SPEECH = Quality("SPEECH", "Speech", 24000, 1)
        val MONO_32 = Quality("MONO_32", "Mono 32", 32000, 1)
        val MONO_48 = Quality("MONO_48", "Mono 48", 48000, 1)
        val STEREO_44 = Quality("STEREO_44", "Stereo 44", 44100, 2)
        val MONO_96 = Quality("MONO_96", "Mono 96", 96000, 1)
        val STEREO_96 = Quality("STEREO_96", "Stereo 96", 96000, 2)
        val MONO_24BIT = Quality("MONO_24BIT", "24-bit M", 48000, 1, PcmEncoding.PCM24)
        val STEREO_24BIT = Quality("STEREO_24BIT", "24-bit S", 48000, 2, PcmEncoding.PCM24)
        val MONO_32BIT = Quality("MONO_32BIT", "32-bit M", 48000, 1, PcmEncoding.PCM32)
        val STEREO_32BIT = Quality("STEREO_32BIT", "32-bit S", 48000, 2, PcmEncoding.PCM32)
        val fixedPresets = listOf(VOICE, BALANCED, STUDIO)
        val entries = listOf(VOICE, BALANCED, STUDIO, BEST, COMPACT, SPEECH, MONO_32, MONO_48, STEREO_44,
            MONO_96, STEREO_96, MONO_24BIT, STEREO_24BIT, MONO_32BIT, STEREO_32BIT)
        val catalog = entries.filter { it != BEST }.sortedWith(compareBy<Quality> { it.sampleRate }.thenBy { it.channels }.thenBy { it.encoding.bits })
        val aacRates = listOf(16000, 24000, 32000, 44100, 48000)
        val aacBitRates = listOf(32000, 48000, 64000, 96000, 128000, 192000, 256000, 320000)
        fun aac(rate: Int, bitRate: Int, channels: Int): Quality {
            require(rate in aacRates && bitRate in aacBitRates && channels in 1..2) { "Invalid AAC quality" }
            return Quality("AAC_${rate}_${bitRate}_$channels", "AAC ${bitRate / 1000}", rate, channels, aacBitRate = bitRate)
        }
        fun valueOf(name: String): Quality = entries.firstOrNull { it.name == name } ?: run {
            val parts = name.split('_')
            require(parts.size == 4 && parts[0] == "AAC") { "Unknown recording quality" }
            aac(parts[1].toInt(), parts[2].toInt(), parts[3].toInt()).also { require(it.name == name) }
        }
    }

    val isAac: Boolean get() = aacBitRate != null
    val containerLabel: String get() = if (isAac) "AAC" else "WAV"
    val format: PcmFormat get() { require(this != BEST); return PcmFormat(sampleRate, channels, encoding) }
    val sampleRateLabel: String get() = if (this == BEST) "Auto" else "$sampleRate Hz"
    val detail: String get() = when {
        this == BEST -> "Check the active microphone and validate its highest reported format."
        isAac -> "AAC-LC · ${if (channels == 1) "mono" else "stereo"} · ${String.format(Locale.ROOT, "%.1f MB/min", bitRate * 60 / 8_000_000.0)}"
        else -> "${format.label} · ${format.storageLabel}"
    }
    val bitRate: Int get() = aacBitRate ?: (sampleRate * channels * encoding.bits)
    val bitRateLabel: String get() = if (this == BEST) "Auto" else if (bitRate % 1000 == 0) "${bitRate / 1000} kbps"
        else String.format(Locale.ROOT, "%.1f kbps", bitRate / 1000.0)
}

/** Exactly three persisted slots; stale or automatic values fall back independently. */
fun recordingQualitySlots(names: List<String?>): List<Quality> = Quality.fixedPresets.mapIndexed { index, fallback ->
    runCatching { Quality.valueOf(names.getOrNull(index).orEmpty()) }.getOrNull()?.takeUnless { it == Quality.BEST } ?: fallback
}
enum class RecordControlStyle(val label: String, val detail: String) {
    THREE_BUTTONS("Three record buttons", "Tap an assigned quality to start immediately."),
    SINGLE_BUTTON("One button with quality selector", "Choose a preset, then tap Record.")
}
enum class LibraryLayout(val label: String) { LIST("List"), GRID("Grid"), COMPACT("Compact") }
enum class SortOrder(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), LONGEST("Longest first"), SHORTEST("Shortest first") }

const val ALL_FOLDERS = ""
const val UNFILED = "unfiled"

@Entity(tableName = "folders", indices = [Index(value = ["nameKey"], unique = true)])
data class RecordingFolder(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String,
    val nameKey: String = name.trim().lowercase(Locale.ROOT), val createdAt: Long = System.currentTimeMillis())

fun folderNameError(name: String, folders: List<RecordingFolder>, excludingId: String? = null): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Enter a folder name"
        trimmed.length > 80 -> "Use 80 characters or fewer"
        trimmed.lowercase(Locale.ROOT) in listOf("all recordings", "unfiled") -> "Choose a different folder name"
        folders.any { it.id != excludingId && it.nameKey == trimmed.lowercase(Locale.ROOT) } -> "A folder with this name already exists"
        else -> null
    }
}

@Entity(tableName = "recordings", indices = [Index("createdAt"), Index("durationMs"), Index("folderId")],
    foreignKeys = [ForeignKey(entity = RecordingFolder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL)])
data class Recording(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val fileName: String,
    val sampleRate: Int,
    val channels: Int,
    val pinned: Boolean = false,
    val sourceId: String? = null,
    val sourceCreatedAt: Long? = null,
    // Legacy columns, kept so existing databases need no migration.
    val backupState: String = "Local only",
    val driveFileId: String? = null,
    val driveMetadataId: String? = null,
    val backedUpAt: Long? = null,
    @ColumnInfo(defaultValue = "'PCM16'") val pcmEncoding: String = PcmEncoding.PCM16.name,
    @ColumnInfo(defaultValue = "NULL") val inputName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val captureDetails: String? = null,
    @ColumnInfo(defaultValue = "NULL") val folderId: String? = null
) {
    fun format() = PcmFormat(sampleRate, channels, PcmEncoding.valueOf(pcmEncoding))
}

@Entity(tableName = "checkpoints", foreignKeys = [ForeignKey(entity = Recording::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recordingId")])
data class Checkpoint(@PrimaryKey val id: String = UUID.randomUUID().toString(), val recordingId: String, val positionMs: Long, val label: String)

@Entity(tableName = "cut_windows", foreignKeys = [ForeignKey(entity = Recording::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recordingId")])
data class CutWindow(@PrimaryKey val id: String = UUID.randomUUID().toString(), val recordingId: String, val startMs: Long, val endMs: Long, val selected: Boolean = true, val ordinal: Int = 0,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "0") val reverse: Boolean = false,
    @ColumnInfo(defaultValue = "1.0") val speed: Double = 1.0,
    @ColumnInfo(defaultValue = "0") val preservePitch: Boolean = false) {
    fun validFor(durationMs: Long) = startMs >= 0 && endMs > startMs && endMs <= durationMs && speed.isFinite() && speed in 0.25..4.0
    val hasEffects get() = reverse || speed != 1.0
    fun exportTitle(originalTitle: String) = title.trim().ifEmpty { "$originalTitle · Cut ${ordinal + 1}" }
}

fun defaultTitle(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("dd/MM/yy HH:mm:ss", Locale.ROOT).format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun timestamp(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", safe / 3_600_000, safe / 60_000 % 60, safe / 1000 % 60, safe % 1000)
}
fun durationLabel(ms: Long): String = if (ms >= 3_600_000) timestamp(ms).substringBefore('.') else
    String.format(Locale.ROOT, "%02d:%02d", ms / 60_000, ms / 1000 % 60)

/** Accept seconds, mm:ss[.SSS], or hh:mm:ss[.SSS]. No floating-point rounding. */
fun parseTimestamp(text: String): Long? = runCatching {
    val parts = text.trim().split(':')
    require(parts.size in 1..3)
    val secondsParts = parts.last().split('.')
    require(secondsParts.size in 1..2 && secondsParts[0].isNotEmpty())
    require(parts.all { it.matches(Regex("[0-9]+(\\.[0-9]{1,3})?")) })
    val seconds = secondsParts[0].toLong()
    require(parts.size == 1 || seconds < 60)
    val minutes = if (parts.size > 1) parts[parts.lastIndex - 1].toLong() else 0
    require(parts.size < 3 || minutes < 60)
    val hours = if (parts.size == 3) parts[0].toLong() else 0
    val fraction = secondsParts.getOrNull(1)?.padEnd(3, '0')?.toLong() ?: 0
    Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact(hours, 3600), Math.addExact(Math.multiplyExact(minutes, 60), seconds)), 1000), fraction)
}.getOrNull()

fun periodBounds(from: LocalDate?, through: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): Pair<Long?, Long?> =
    from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() to through?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

fun organizeRecordings(items: List<Recording>, sort: SortOrder, from: Long? = null, until: Long? = null, search: String = ""): List<Recording> {
    val order = when (sort) {
        SortOrder.NEWEST -> compareByDescending<Recording> { it.createdAt }
        SortOrder.OLDEST -> compareBy { it.createdAt }
        SortOrder.LONGEST -> compareByDescending { it.durationMs }
        SortOrder.SHORTEST -> compareBy { it.durationMs }
    }
    return items.asSequence().filter { (from == null || it.createdAt >= from) && (until == null || it.createdAt < until) && it.title.contains(search, ignoreCase = true) }
        .sortedWith(compareByDescending<Recording> { it.pinned }.then(order).thenBy { it.id }).toList()
}
