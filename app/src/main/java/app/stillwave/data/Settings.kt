package app.stillwave.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("settings", produceMigrations = { listOf(RecordingSelectionMigration) })

enum class LongPressAction(val label: String, val detail: String) {
    MENU("Open recording menu", "Show the same actions as the recording's three-dot button."),
    MULTI_SELECT("Select multiple recordings", "Select the held recording, then tap others to add or remove them.")
}

enum class ThemeMode(val label: String, val detail: String) {
    SYSTEM("System", "Follows Android's light or dark setting."),
    LIGHT("Light", "Bright surfaces for daylight."),
    DARK("Night", "Dark surfaces for low light.")
}

data class AppSettings(val selectedRecordingSlot: Int = 0, val layout: LibraryLayout = LibraryLayout.LIST, val sort: SortOrder = SortOrder.NEWEST,
    val confirmDiscard: Boolean = true,
    val recordStyle: RecordControlStyle = RecordControlStyle.THREE_BUTTONS,
    val stopPlaybackWhenLocked: Boolean = false,
    val recordingSlots: List<Quality> = Quality.fixedPresets,
    val longPressAction: LongPressAction = LongPressAction.MENU,
    val allowPlaybackWhileRecording: Boolean = false,
    val selectedMicrophone: String = "",
    val selectedMicrophoneName: String = "",
    val showLoopButton: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM) {
    val selectedRecordingQuality: Quality
        get() = if (selectedRecordingSlot == Settings.AUTO_RECORDING_SLOT) Quality.BEST
        else recordingSlots.getOrElse(selectedRecordingSlot) { recordingSlots.first() }
}

/** Keep an existing visible choice on upgrade, but never retain an unassigned hidden quality. */
internal object RecordingSelectionMigration : DataMigration<Preferences> {
    private val legacyQuality = stringPreferencesKey("quality")
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[Settings.SELECTED_RECORDING_SLOT]?.let { it in Settings.AUTO_RECORDING_SLOT..2 } != true || currentData.contains(legacyQuality)

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        if (this[Settings.SELECTED_RECORDING_SLOT]?.let { it in Settings.AUTO_RECORDING_SLOT..2 } != true) {
            val slots = recordingQualitySlots(Settings.RECORDING_SLOTS.map { this[it] })
            val previous = this[legacyQuality]?.let { runCatching { Quality.valueOf(it) }.getOrNull() }
            this[Settings.SELECTED_RECORDING_SLOT] = if (previous == Quality.BEST) Settings.AUTO_RECORDING_SLOT
                else previous?.let(slots::indexOf)?.takeIf { it >= 0 } ?: 0
        }
        remove(legacyQuality)
    }

    override suspend fun cleanUp() = Unit
}

class Settings(private val context: Context) {
    @Volatile var current = AppSettings()
        private set
    companion object {
        const val AUTO_RECORDING_SLOT = -1
        val SELECTED_RECORDING_SLOT = intPreferencesKey("selected_recording_slot")
        val RECORD_STYLE = stringPreferencesKey("record_control_style")
        val RECORDING_SLOTS = (1..3).map { stringPreferencesKey("recording_quality_slot_$it") }
        val LAYOUT = stringPreferencesKey("layout")
        val SORT = stringPreferencesKey("sort")
        val CONFIRM = booleanPreferencesKey("confirm_discard")
        val STOP_PLAYBACK_WHEN_LOCKED = booleanPreferencesKey("stop_playback_when_locked")
        val LONG_PRESS_ACTION = stringPreferencesKey("long_press_action")
        val ALLOW_PLAYBACK_WHILE_RECORDING = booleanPreferencesKey("allow_playback_while_recording")
        val SELECTED_MICROPHONE = stringPreferencesKey("selected_microphone")
        val SELECTED_MICROPHONE_NAME = stringPreferencesKey("selected_microphone_name")
        val SHOW_LOOP_BUTTON = booleanPreferencesKey("show_loop_button")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
    val flow = context.settingsStore.data.map { p -> AppSettings(
        selectedRecordingSlot = p[SELECTED_RECORDING_SLOT]?.takeIf { it in AUTO_RECORDING_SLOT..2 } ?: 0,
        recordStyle = runCatching { RecordControlStyle.valueOf(p[RECORD_STYLE] ?: "THREE_BUTTONS") }.getOrDefault(RecordControlStyle.THREE_BUTTONS),
        layout = runCatching { LibraryLayout.valueOf(p[LAYOUT] ?: "LIST") }.getOrDefault(LibraryLayout.LIST),
        sort = runCatching { SortOrder.valueOf(p[SORT] ?: "NEWEST") }.getOrDefault(SortOrder.NEWEST),
        confirmDiscard = p[CONFIRM] ?: true,
        stopPlaybackWhenLocked = p[STOP_PLAYBACK_WHEN_LOCKED] ?: false,
        longPressAction = runCatching { LongPressAction.valueOf(p[LONG_PRESS_ACTION] ?: "MENU") }.getOrDefault(LongPressAction.MENU),
        allowPlaybackWhileRecording = p[ALLOW_PLAYBACK_WHILE_RECORDING] ?: false,
        selectedMicrophone = p[SELECTED_MICROPHONE] ?: "",
        selectedMicrophoneName = p[SELECTED_MICROPHONE_NAME] ?: "",
        showLoopButton = p[SHOW_LOOP_BUTTON] ?: true,
        themeMode = runCatching { ThemeMode.valueOf(p[THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        recordingSlots = recordingQualitySlots(RECORDING_SLOTS.map { p[it] })
    ).also { current = it } }
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { context.settingsStore.edit { it[key] = value } }
    suspend fun selectMicrophone(key: String, name: String) { context.settingsStore.edit {
        it[SELECTED_MICROPHONE] = key; it[SELECTED_MICROPHONE_NAME] = name
    } }
}
