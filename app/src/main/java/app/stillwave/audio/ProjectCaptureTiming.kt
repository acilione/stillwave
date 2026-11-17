package app.stillwave.audio

/** A paused playhead is a placement choice; only advancing playback contains the captured first buffer. */
internal fun projectCaptureOffset(positionMs: Long?, playing: Boolean, firstBufferMs: Long, durationMs: Long, loop: Boolean): Long {
    if (positionMs == null) return 0
    val start = positionMs - if (playing) firstBufferMs else 0
    return if (loop) Math.floorMod(start, durationMs) else start.coerceAtLeast(0)
}

/** Round up to a complete sample so WAV's millisecond duration never falls below the loop duration. */
internal fun projectCaptureFrames(durationMs: Long, sampleRate: Int): Long {
    require(durationMs > 0 && sampleRate > 0)
    return Math.addExact(Math.multiplyExact(durationMs, sampleRate.toLong()), 999) / 1000
}
