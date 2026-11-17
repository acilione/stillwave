package app.stillwave.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import android.os.SystemClock
import app.stillwave.data.Quality
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/** Probes the currently routed input, never the first device in an enumeration. */
class CaptureNegotiator(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val profiles = MicrophoneProfiles(context.applicationContext)
    data class Capture(val record: AudioRecord, val format: PcmFormat, val firstBytes: ByteArray,
        val device: AudioDeviceInfo?, val inputName: String, val details: String)
    private data class Probe(val record: AudioRecord, val format: PcmFormat, val bytes: ByteArray,
        val device: AudioDeviceInfo?, val deviceFormat: PcmFormat?) {
        fun close() { runCatching { record.stop() }; record.release() }
    }

    suspend fun open(quality: Quality, preferred: AudioDeviceInfo? = null): Capture {
        if (quality != Quality.BEST) {
            val probe = try { probe(quality.format, MediaRecorder.AudioSource.MIC, preferred) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { throw IllegalStateException("${quality.format.label} is unavailable on this input. Choose another quality or Best available. ${error.message.orEmpty()}", error) }
            val capture = result(probe, "Fixed ${quality.label} preset." + if (preferred != null) "\nSelected microphone route verified." else "", MediaRecorder.AudioSource.MIC)
            return if (quality.isAac) capture.copy(details = capture.details.replace("Saved WAV:", "Microphone capture PCM:") +
                "\nSave target: AAC-LC / M4A, ${quality.sampleRateLabel}, ${quality.bitRateLabel}.") else capture
        }
        var source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        var baseline: Probe? = null
        var lastError: Exception? = null
        // An unspecified rate lets Android select the initial rate for its active route.
        for (audioSource in listOf(source, MediaRecorder.AudioSource.MIC).distinct()) {
            for (format in listOf(null, PcmFormat(44100, 1))) {
                try { baseline = probe(format, audioSource, preferred); source = audioSource; break }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { lastError = error }
            }
            if (baseline != null) break
        }
        val initial = baseline ?: throw (lastError ?: IllegalStateException("Microphone unavailable"))
        var owned: Probe? = initial
        try {
            val device = initial.device
            val advertised = runCatching { device?.let { advertised(it, initial.deviceFormat ?: initial.format) }.orEmpty() }.getOrDefault(emptyList())
            val candidates = CaptureFormats.ranked(advertised, initial.deviceFormat, initial.format)
            val limited = advertised.isEmpty() || device == null || device.sampleRates.isEmpty() || device.encodings.isEmpty() || device.channelCounts.isEmpty()
            val notes = mutableListOf<String>()
            if (preferred != null) notes += "Selected microphone route verified."
            if (limited) notes += "Capability details unavailable; using a validated Android-reported format."
            val identity = runCatching { device?.let { identity(it, initial.record) } }.getOrNull()
            val signature = runCatching { device?.let { signature(it, source) } }.getOrNull()
            if (identity == null) notes += "This input has no distinct persistent identity; its format will be checked each time."
            val remembered = if (identity != null && signature != null) try { profiles.find(identity) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notes += "Remembered microphone settings could not be read."; null } else null
            suspend fun accept(probe: Probe, selection: String, reused: Boolean = false): Capture {
                var capture = result(probe, selection, source)
                if (identity != null && signature != null && !reused) {
                    try { profiles.remember(MicrophoneProfile(identity, signature, capture.inputName, capture.format, System.currentTimeMillis())) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { capture = capture.copy(details = capture.details + "\nCould not remember this microphone's format.") }
                }
                owned = null
                return capture
            }
            if (remembered != null && remembered.signature == signature && remembered.format in candidates) {
                try {
                    val cached = if (remembered.format == initial.format) initial else {
                        owned?.close(); owned = null
                        probe(remembered.format, source, device).also { owned = it }
                    }
                    check(device != null && cached.device?.id == device.id) { "Input changed while checking quality. Please start again." }
                    return accept(cached, (listOf("Remembered microphone format validated.") + notes).joinToString("\n"), reused = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (owned != null) throw error
                    try { profiles.forget(remembered.key) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Fresh selection can still proceed if preference storage fails. */ }
                    notes += "Remembered format unavailable; checking reported formats again."
                }
            } else if (remembered != null) notes += "Microphone capabilities or Android configuration changed; checking formats again."
            val deadline = SystemClock.elapsedRealtime() + 3000
            var attempts = 0
            for (format in candidates) {
                if (attempts >= 8 || SystemClock.elapsedRealtime() >= deadline) {
                    notes += "Probe limit reached; using the validated fallback."
                    break
                }
                if (format == initial.format && owned === initial) {
                    return accept(initial, notes.ifEmpty { listOf("Highest ranked reported format validated.") }.joinToString("\n"))
                }
                owned?.close(); owned = null
                attempts++
                try {
                    val candidate = probe(format, source, device)
                    owned = candidate
                    if (device != null && candidate.device?.id != device.id) error("Input changed while checking quality. Please start again.")
                    return accept(candidate, notes.ifEmpty { listOf("Highest ranked reported format validated.") }.joinToString("\n"))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (owned != null) throw error // Route changed: do not silently use another microphone's capabilities.
                    notes += "${format.label} unavailable; trying a lower format."
                }
            }
            owned?.close(); owned = null
            val fallback = probe(initial.format, source, device)
            owned = fallback
            check(device == null || fallback.device?.id == device.id) { "Input changed while checking quality. Please start again." }
            return accept(fallback, (notes + "Using the validated fallback.").joinToString("\n"))
        } finally { owned?.close() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun probe(requested: PcmFormat?, source: Int, preferred: AudioDeviceInfo? = null): Probe {
        val channels = requested?.channels ?: 1
        val encoding = requested?.encoding ?: PcmEncoding.PCM16
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val androidEncoding = encoding.androidEncoding()
        val minimum = requested?.let { AudioRecord.getMinBufferSize(it.rate, mask, androidEncoding) } ?: 4096
        check(minimum > 0) { "Unsupported microphone format" }
        val format = AudioFormat.Builder().setEncoding(androidEncoding).setChannelMask(mask)
        requested?.let { format.setSampleRate(it.rate) }
        val record = AudioRecord.Builder().setAudioSource(source).setAudioFormat(format.build())
            .setBufferSizeInBytes(maxOf(minimum * 2, (requested?.rate ?: 48000) * channels * encoding.bytes / 5)).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
            if (preferred != null) check(record.setPreferredDevice(preferred)) { "Input is no longer available" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
            val actual = checkNotNull(record.format.pcm()) { "Unsupported capture format" }
            check(requested == null || actual == requested) { "Android changed the requested format" }
            val bytes = ByteBuffer.allocateDirect(actual.rate / 50 * actual.frameSize)
            val deadline = SystemClock.elapsedRealtime() + 650
            while (SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val count = record.read(bytes, bytes.capacity(), AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed ($count)" }
                if (count > 0) {
                    check(count % actual.frameSize == 0) { "Incomplete microphone sample frame" }
                    val config = configuration(record)
                    check(Build.VERSION.SDK_INT < 29 || config?.isClientSilenced != true) { "Another app has silenced the microphone" }
                    val routed = record.routedDevice ?: config?.audioDevice
                    check(preferred == null || routed?.id == preferred.id) {
                        "Android could not use the selected microphone. Choose another input or Automatic in Settings."
                    }
                    val first = ByteArray(count); bytes.position(0); bytes.get(first)
                    return Probe(record, actual, first, routed, config?.format?.pcm())
                }
                delay(10)
            }
            error("Microphone did not return audio")
        } catch (error: Throwable) {
            runCatching { record.stop() }; record.release(); throw error
        }
    }

    private fun configuration(record: AudioRecord): AudioRecordingConfiguration? = runCatching {
        (if (Build.VERSION.SDK_INT >= 29) record.activeRecordingConfiguration else null)
            ?: manager.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
    }.getOrNull()

    private fun address(device: AudioDeviceInfo) = if (Build.VERSION.SDK_INT >= 28) device.address else ""
    private fun identity(device: AudioDeviceInfo, record: AudioRecord): String? {
        val deviceAddress = address(device)
        val product = device.productName.toString()
        val equivalentRoutes = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).count {
            it.type == device.type && it.productName.toString() == product && address(it) == deviceAddress
        }
        return MicrophoneIdentity.key(device.type, product, deviceAddress, microphoneNames(record),
            builtIn = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC, uniqueRoute = equivalentRoutes == 1)
    }
    private fun signature(device: AudioDeviceInfo, source: Int): String {
        fun IntArray.stable() = sorted().joinToString(",")
        val profiles = if (Build.VERSION.SDK_INT >= 31) device.audioProfiles.map {
            "${it.format}:${it.sampleRates.stable()}:${it.channelMasks.stable()}:${it.channelIndexMasks.stable()}"
        }.sorted() else emptyList()
        return MicrophoneIdentity.hash(listOf("selection-policy-1", Build.FINGERPRINT, source.toString(),
            device.sampleRates.stable(), device.encodings.stable(), device.channelMasks.stable(), device.channelIndexMasks.stable()) + profiles)
    }

    @SuppressLint("MissingPermission")
    private fun microphoneNames(record: AudioRecord): List<String> = if (Build.VERSION.SDK_INT >= 28) runCatching {
        record.activeMicrophones.map { it.description }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList()) else emptyList()

    private fun advertised(device: AudioDeviceInfo, observed: PcmFormat): List<PcmFormat> {
        fun combinations(rates: IntArray, counts: IntArray, encoding: PcmEncoding): List<PcmFormat> =
            rates.toList().ifEmpty { listOf(observed.rate) }.filter { it in 8000..192000 }.flatMap { rate ->
                counts.toList().ifEmpty { listOf(observed.channels) }.filter { it in 1..2 }.map { PcmFormat(rate, it, encoding) }
            }
        if (Build.VERSION.SDK_INT >= 31 && device.audioProfiles.isNotEmpty()) {
            return device.audioProfiles.flatMap { profile ->
                val encoding = pcmEncoding(profile.format) ?: return@flatMap emptyList()
                val counts = (profile.channelMasks.map(Integer::bitCount) + profile.channelIndexMasks.map(Integer::bitCount)).distinct().toIntArray()
                combinations(profile.sampleRates, counts, encoding)
            }
        }
        return device.encodings.toList().mapNotNull(::pcmEncoding).flatMap { combinations(device.sampleRates, device.channelCounts, it) }
    }

    @SuppressLint("MissingPermission")
    private fun result(probe: Probe, selection: String, source: Int): Capture {
        // Introspection is optional; a manufacturer's incomplete metadata must not discard working audio.
        val microphones = microphoneNames(probe.record)
        val type = when (probe.device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth microphone"
            else -> "Microphone"
        }
        val name = probe.device?.productName?.toString()?.takeIf { it.isNotBlank() }?.let { "$type · $it" } ?: "$type (identity unavailable)"
        val details = buildString {
            appendLine(selection)
            appendLine("Saved WAV: ${probe.format.label} (${probe.format.storageLabel}).")
            appendLine("Android-reported device format: ${probe.deviceFormat?.label ?: "unavailable"}.")
            if (probe.deviceFormat != null && probe.deviceFormat != probe.format) appendLine("Android converts between the device and saved formats.")
            if (microphones.isNotEmpty()) appendLine("Active microphone identifiers: ${microphones.joinToString()}.")
            appendLine(if (source == MediaRecorder.AudioSource.UNPROCESSED) "Unprocessed input requested (supported by Android)." else "Standard microphone input; device processing may apply.")
            append("Reported formats do not verify the microphone's physical bit depth or independent stereo channels. Automatic selection supports mono/stereo up to 192 kHz.")
        }
        return Capture(probe.record, probe.format, probe.bytes, probe.device, name, details)
    }
}

private fun AudioFormat.pcm(): PcmFormat? = pcmEncoding(encoding)?.let { runCatching { PcmFormat(sampleRate, channelCount, it) }.getOrNull() }
private fun pcmEncoding(encoding: Int): PcmEncoding? = when (encoding) {
    AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.PCM16
    AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT32
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> if (Build.VERSION.SDK_INT >= 31) PcmEncoding.PCM24 else null
    AudioFormat.ENCODING_PCM_32BIT -> if (Build.VERSION.SDK_INT >= 31) PcmEncoding.PCM32 else null
    else -> null
}
private fun PcmEncoding.androidEncoding(): Int = when (this) {
    PcmEncoding.PCM16 -> AudioFormat.ENCODING_PCM_16BIT
    PcmEncoding.FLOAT32 -> AudioFormat.ENCODING_PCM_FLOAT
    PcmEncoding.PCM24 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_24BIT_PACKED else error("24-bit capture requires Android 12")
    PcmEncoding.PCM32 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_32BIT else error("32-bit capture requires Android 12")
}
