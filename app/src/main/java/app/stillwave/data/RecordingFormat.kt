package app.stillwave.data

/** Container format belongs to the stored file, independently of the microphone's PCM format. */
val Recording.isAac: Boolean get() = fileName.endsWith(".m4a", ignoreCase = true)
val Recording.fileExtension: String get() = if (isAac) "m4a" else "wav"
val Recording.mimeType: String get() = if (isAac) "audio/mp4" else "audio/wav"
val Recording.formatLabel: String get() = if (isAac)
    "$sampleRate Hz · ${if (channels == 1) "mono" else "stereo"} · AAC (M4A)"
else "${format().label} · WAV"
