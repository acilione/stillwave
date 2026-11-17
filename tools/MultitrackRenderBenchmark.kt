import app.stillwave.audio.MultitrackRenderer
import app.stillwave.audio.WavFile
import app.stillwave.data.MultitrackTrack
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.measureNanoTime

/** Standalone desktop JVM measurement; excludes AAC decoding and Android playback setup. */
fun main(args: Array<String>) {
    val seconds = args.firstOrNull()?.toInt() ?: 30
    val repeats = args.getOrNull(1)?.toInt() ?: 3
    require(seconds in 1..300 && repeats in 1..10)
    val directory = Files.createTempDirectory("stillwave-render-benchmark-").toFile()
    try {
        for (rate in listOf(44_100, 48_000)) {
            val source = File(directory, "source-$rate.wav")
            WavFile.header(source, rate, 2, 0)
            RandomAccessFile(source, "rw").use { file ->
                file.seek(WavFile.HEADER_SIZE)
                val buffer = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)
                var frame = 0
                while (frame < seconds * rate) {
                    buffer.clear()
                    val count = minOf(4096, seconds * rate - frame)
                    repeat(count) { i ->
                        buffer.putShort((sin(2 * PI * 440 * (frame + i) / rate) * 8_000).toInt().toShort())
                        buffer.putShort((sin(2 * PI * 660 * (frame + i) / rate) * 6_000).toInt().toShort())
                    }
                    file.write(buffer.array(), 0, count * 4)
                    frame += count
                }
            }
            WavFile.header(source, rate, 2, seconds * rate * 4L)
            val inputs = List(4) { index -> MultitrackRenderer.Input(MultitrackTrack(
                id = "clip-$index", recordingId = "source", title = "Track $index",
                sourceEndMs = seconds * 1000L, offsetMs = index * 137L, gain = 0.75 + index * 0.125,
                loop = index == 3
            ), source) }
            // Warm JIT separately from the measured full-project renders.
            repeat(2) { index ->
                val warmup = File(directory, "warm-$index.wav")
                MultitrackRenderer.render(inputs, warmup, 1000)
                warmup.delete()
            }
            val timings = mutableListOf<Double>()
            var checksum = ""
            repeat(repeats) { index ->
                val destination = File(directory, "output-$index.wav")
                timings += measureNanoTime {
                    MultitrackRenderer.render(inputs, destination, seconds * 1000L)
                } / 1_000_000.0
                checksum = MessageDigest.getInstance("SHA-256").digest(destination.readBytes())
                    .joinToString("") { "%02x".format(it) }
                destination.delete()
            }
            println("rate=$rate tracks=4 stereo seconds=$seconds ms=${timings.joinToString { "%.1f".format(it) }} median_ms=${"%.1f".format(timings.sorted()[timings.size / 2])} sha256=$checksum")
        }
    } finally { directory.deleteRecursively() }
}
