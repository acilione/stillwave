package app.stillwave.audio

import java.util.Locale
import kotlin.math.abs

enum class PcmEncoding(val bits: Int, val wavTag: Int, val precision: Int, val label: String) {
    PCM16(16, 1, 16, "16-bit"), PCM24(24, 1, 24, "24-bit"),
    PCM32(32, 1, 32, "32-bit"), FLOAT32(32, 3, 24, "32-bit float");
    val bytes get() = bits / 8
}

data class PcmFormat(val rate: Int, val channels: Int, val encoding: PcmEncoding = PcmEncoding.PCM16) {
    init { require(rate in 8000..192000 && channels in 1..2) }
    val frameSize get() = channels * encoding.bytes
    val label get() = "${(rate / 1000.0).toString().removeSuffix(".0")} kHz · ${encoding.label} · ${if (channels == 1) "mono" else "stereo"}"
    val storageLabel get() = String.format(Locale.ROOT, "%.1f MB/min", rate * frameSize * 60 / 1_000_000.0)
}

/** Only rank advertised combinations. Empty platform capability arrays are not evidence of high fidelity. */
object CaptureFormats {
    fun ranked(advertised: List<PcmFormat>, observed: PcmFormat?, baseline: PcmFormat): List<PcmFormat> =
        (advertised + listOfNotNull(observed)).distinct()
            .sortedWith(compareByDescending<PcmFormat> { it.encoding.precision }
                .thenByDescending { it.rate }.thenByDescending { it.channels }
                .thenByDescending { it == observed })
            .let { (it + baseline).distinct() }
}

/** Decode for metering only; capture and edits preserve the original sample bytes. */
fun pcmPeak(bytes: ByteArray, count: Int, encoding: PcmEncoding): Float {
    var peak = 0.0
    for (i in 0 until count - encoding.bytes + 1 step encoding.bytes) {
        val lo = bytes[i].toInt() and 255
        val sample = when (encoding) {
            PcmEncoding.PCM16 -> ((lo or (bytes[i + 1].toInt() shl 8)).toShort().toDouble()) / 32768.0
            PcmEncoding.PCM24 -> (lo or ((bytes[i + 1].toInt() and 255) shl 8) or (bytes[i + 2].toInt() shl 16)).toDouble() / 8388608.0
            PcmEncoding.PCM32, PcmEncoding.FLOAT32 -> {
                val value = lo or ((bytes[i + 1].toInt() and 255) shl 8) or ((bytes[i + 2].toInt() and 255) shl 16) or (bytes[i + 3].toInt() shl 24)
                if (encoding == PcmEncoding.FLOAT32) Float.fromBits(value).toDouble() else value.toDouble() / 2147483648.0
            }
        }
        if (sample.isFinite()) peak = maxOf(peak, abs(sample))
    }
    return peak.coerceAtMost(1.0).toFloat()
}
