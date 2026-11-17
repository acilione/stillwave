package app.stillwave.audio

import java.security.MessageDigest

/** Length-prefix components so names/addresses cannot create ambiguous concatenations. */
object MicrophoneIdentity {
    fun hash(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun key(type: Int, product: String, address: String, physicalNames: List<String>, builtIn: Boolean, uniqueRoute: Boolean): String? {
        val names = physicalNames.filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().sorted()
        // Unaddressed external devices cannot reliably be distinguished across reconnections.
        if (!uniqueRoute || (!builtIn && address.isBlank()) || (builtIn && product.isBlank() && names.isEmpty())) return null
        return hash(listOf(type.toString(), product, address) + names)
    }
}
