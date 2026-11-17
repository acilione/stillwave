package app.stillwave.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.UUID

data class InputOption(val key: String, val label: String, val deviceId: Int)

/** Selects only capture routing. Playback keeps the output route chosen by Android. */
object MicrophoneInputs {
    private val session = UUID.randomUUID().toString()

    /** Automatic is represented separately by the empty key in Settings. */
    fun options(context: Context): List<InputOption> = optionsFor(devices(context))

    fun resolve(context: Context, key: String): AudioDeviceInfo? {
        if (key.isEmpty()) return null
        val devices = devices(context)
        val selected = microphoneDeviceId(optionsFor(devices), key)
        return devices.firstOrNull { it.id == selected }
            ?: error("The selected microphone is unavailable. Reconnect it or choose another microphone in Settings.")
    }

    private fun devices(context: Context) = context.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource && it.type !in setOf(
            AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER) &&
            !(Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) }

    private fun optionsFor(devices: List<AudioDeviceInfo>) = microphoneInputOptions(devices.map {
        MicrophoneInputDescriptor(it.id, it.type, it.productName?.toString().orEmpty(),
            if (Build.VERSION.SDK_INT >= 28) runCatching { it.address }.getOrDefault("") else "")
    }, session)
}

internal data class MicrophoneInputDescriptor(val id: Int, val type: Int, val product: String, val address: String)

/** Device IDs change on reconnection; use route identity unless multiple inputs are indistinguishable. */
internal fun microphoneInputOptions(devices: List<MicrophoneInputDescriptor>, session: String): List<InputOption> {
    fun identity(input: MicrophoneInputDescriptor) = MicrophoneIdentity.hash(listOf(input.type.toString(), input.product, input.address))
    val identities = devices.groupBy(::identity)
    return devices.map { input ->
        val identity = identity(input)
        val ambiguous = identities.getValue(identity).size > 1
        val key = "input-v1:$identity" + if (ambiguous) ":$session:${input.id}" else ""
        val type = when (input.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth microphone"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "Line input"
            else -> "Audio input"
        }
        val label = type + input.product.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
            if (ambiguous) " (input ${input.id})" else ""
        InputOption(key, label, input.id)
    }.sortedWith(compareBy<InputOption> { if (it.label.startsWith("Built-in microphone")) 0 else 1 }.thenBy { it.label }.thenBy { it.deviceId })
}

internal fun microphoneDeviceId(options: List<InputOption>, key: String): Int? {
    if (key.isEmpty()) return null
    return options.singleOrNull { it.key == key }?.deviceId
        ?: error("The selected microphone is unavailable. Reconnect it or choose another microphone in Settings.")
}
