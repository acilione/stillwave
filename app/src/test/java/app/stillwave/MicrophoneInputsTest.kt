package app.stillwave

import android.media.AudioDeviceInfo
import app.stillwave.audio.*
import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputsTest {
    @Test fun identifiedInputKeepsItsSelectionAcrossDeviceIdChangesAndEnumerationOrder() {
        val phone = MicrophoneInputDescriptor(4, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone", "bottom")
        val usb = MicrophoneInputDescriptor(8, AudioDeviceInfo.TYPE_USB_DEVICE, "USB mic", "card=2;device=0")
        val original = microphoneInputOptions(listOf(phone, usb), "session-a")
        val reconnected = microphoneInputOptions(listOf(usb.copy(id = 40), phone.copy(id = 22)), "session-b")
        assertEquals(original.map { it.key }, reconnected.map { it.key })
        assertEquals(22, microphoneDeviceId(reconnected, original.first().key))
        assertEquals(40, microphoneDeviceId(reconnected, original.last().key))
    }

    @Test fun devicesWithTheSameNameButDifferentAddressesHaveDistinctKeys() {
        val a = MicrophoneInputDescriptor(4, AudioDeviceInfo.TYPE_USB_DEVICE, "USB mic", "address-a")
        val b = a.copy(id = 5, address = "address-b")
        val options = microphoneInputOptions(listOf(a, b), "session")
        assertEquals(2, options.map { it.key }.distinct().size)
        assertEquals(4, microphoneDeviceId(options, options.first { it.deviceId == 4 }.key))
        assertEquals(5, microphoneDeviceId(options, options.first { it.deviceId == 5 }.key))
    }

    @Test fun ambiguousInputsRequireReselectionAfterRestartOrAnIdentityChange() {
        val input = MicrophoneInputDescriptor(4, AudioDeviceInfo.TYPE_USB_DEVICE, "Same name", "")
        val options = microphoneInputOptions(listOf(input, input.copy(id = 5)), "session-a")
        assertEquals(2, options.map { it.key }.distinct().size)
        assertEquals(4, microphoneDeviceId(options, options.first().key))
        val restarted = microphoneInputOptions(listOf(input, input.copy(id = 5)), "session-b")
        assertThrows(IllegalStateException::class.java) { microphoneDeviceId(restarted, options.first().key) }
        assertThrows(IllegalStateException::class.java) { microphoneDeviceId(microphoneInputOptions(listOf(input), "session-a"), options.first().key) }
    }

    @Test fun automaticIsExplicitAndAnUnavailableSelectionNeverFallsBack() {
        val options = microphoneInputOptions(listOf(MicrophoneInputDescriptor(4, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone", "bottom")), "session")
        assertNull(microphoneDeviceId(options, ""))
        val failure = assertThrows(IllegalStateException::class.java) { microphoneDeviceId(options, "disconnected-device") }
        assertTrue(failure.message!!.contains("selected microphone is unavailable"))
    }
}
