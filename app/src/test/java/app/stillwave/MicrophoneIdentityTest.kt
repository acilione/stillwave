package app.stillwave

import app.stillwave.audio.MicrophoneIdentity
import org.junit.Assert.*
import org.junit.Test

class MicrophoneIdentityTest {
    @Test fun stableIdentityIgnoresEnumerationOrderAndTemporaryNumericIds() {
        val a = MicrophoneIdentity.key(15, "Phone", "bottom", listOf("Mic B", "Mic A", "8"), true, true)
        val b = MicrophoneIdentity.key(15, "Phone", "bottom", listOf("19", "Mic A", "Mic B", "Mic A"), true, true)
        assertNotNull(a); assertEquals(a, b)
    }
    @Test fun differentAddressesAndPhysicalInputsStaySeparate() {
        val bottom = MicrophoneIdentity.key(15, "Phone", "bottom", listOf("A"), true, true)
        assertNotEquals(bottom, MicrophoneIdentity.key(15, "Phone", "back", listOf("A"), true, true))
        assertNotEquals(bottom, MicrophoneIdentity.key(15, "Phone", "bottom", listOf("B"), true, true))
        assertNotEquals(MicrophoneIdentity.key(7, "Headset", "address-a", emptyList(), false, true),
            MicrophoneIdentity.key(7, "Headset", "address-b", emptyList(), false, true))
    }
    @Test fun ambiguousAndUnidentifiedExternalInputsAreNotPersisted() {
        assertNull(MicrophoneIdentity.key(3, "Headset", "", emptyList(), false, true))
        assertNull(MicrophoneIdentity.key(15, "Phone", "", listOf("Mic"), true, false))
        assertNotNull(MicrophoneIdentity.key(15, "Phone", "", emptyList(), true, true))
    }
    @Test fun fingerprintSeparatesComponentsAndConfigurationChanges() {
        assertNotEquals(MicrophoneIdentity.hash(listOf("ab", "c")), MicrophoneIdentity.hash(listOf("a", "bc")))
        assertNotEquals(MicrophoneIdentity.hash(listOf("firmware-1", "48000")), MicrophoneIdentity.hash(listOf("firmware-2", "48000")))
    }
}
