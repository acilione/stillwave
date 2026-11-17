package app.stillwave.audio

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.microphoneStore by preferencesDataStore("microphone_profiles")

data class MicrophoneProfile(val key: String, val signature: String, val inputName: String,
    val format: PcmFormat, val validatedAt: Long)

/** Local device preferences, separate from audio metadata. No raw device address is stored. */
class MicrophoneProfiles(private val context: Context) {
    private val entries = stringPreferencesKey("validated_formats")
    val flow = context.microphoneStore.data.map { decode(it[entries]) }
    suspend fun find(key: String) = flow.first().firstOrNull { it.key == key }
    suspend fun remember(profile: MicrophoneProfile) {
        context.microphoneStore.edit { values ->
            val profiles = (decode(values[entries]).filterNot { it.key == profile.key } + profile)
                .sortedByDescending { it.validatedAt }.take(64)
            values[entries] = encode(profiles)
        }
    }
    suspend fun forget(key: String) {
        context.microphoneStore.edit { values -> values[entries] = encode(decode(values[entries]).filterNot { it.key == key }) }
    }
    private fun decode(value: String?): List<MicrophoneProfile> = runCatching {
        val array = JSONArray(value ?: "[]")
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            MicrophoneProfile(item.getString("key"), item.getString("signature"), item.getString("name"),
                PcmFormat(item.getInt("rate"), item.getInt("channels"), PcmEncoding.valueOf(item.getString("encoding"))), item.getLong("validatedAt"))
        }.getOrNull() }
    }.getOrDefault(emptyList())
    private fun encode(profiles: List<MicrophoneProfile>) = JSONArray().apply {
        profiles.forEach { p -> put(JSONObject().put("key", p.key).put("signature", p.signature).put("name", p.inputName)
            .put("rate", p.format.rate).put("channels", p.format.channels).put("encoding", p.format.encoding.name).put("validatedAt", p.validatedAt)) }
    }.toString()
}
