package app.stillwave.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.stillwave.R
import app.stillwave.StillwaveApp
import app.stillwave.audio.CaptureState
import app.stillwave.audio.RecordingService
import app.stillwave.data.durationLabel
import app.stillwave.data.Quality
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Native launcher views with direct service PendingIntents preserve the microphone widget exemption. */
class RecorderWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext as StillwaveApp
        app.appScope.launch {
            try {
                val slots = app.settings.flow.first().recordingSlots
                val views = views(context, RecordingService.state.value, slots)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    companion object {
        fun updateAll(context: Context, state: CaptureState = RecordingService.state.value,
            presets: List<Quality> = (context.applicationContext as StillwaveApp).settings.current.recordingSlots) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecorderWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, state, presets))
        }

        fun action(context: Context, action: String, sessionId: String, quality: Quality? = null): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // Extras do not participate in PendingIntent identity: include the preset in the URI.
            val uri = "stillwave://capture/${sessionId.ifEmpty { "idle" }}/$action/${quality?.name ?: "default"}".toUri()
            if (action == RecordingService.START && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return PendingIntent.getActivity(context, 0, Intent(context, WidgetPermissionActivity::class.java)
                    .setData(uri).putExtra(RecordingService.QUALITY, quality?.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
            }
            // Session-specific identities prevent a delayed Save/Discard from touching a later recording.
            val intent = Intent(context, RecordingService::class.java).setAction(action)
                .setData(uri).putExtra(RecordingService.QUALITY, quality?.name)
            if (action != RecordingService.START) intent.putExtra(RecordingService.SESSION, sessionId)
            return if (action == RecordingService.START) PendingIntent.getForegroundService(context, 0, intent, flags)
                else PendingIntent.getService(context, 0, intent, flags)
        }

        fun views(context: Context, state: CaptureState,
            presets: List<Quality> = (context.applicationContext as StillwaveApp).settings.current.recordingSlots): RemoteViews = RemoteViews(context.packageName, R.layout.recorder_widget).apply {
            val canControl = state.active && !state.finishing && !state.preparing
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            setTextViewText(R.id.widget_status, when {
                state.finishing -> if (state.quality.isAac) "Saving AAC…" else "Stopping…"
                state.preparing -> "Starting recording…"
                state.active -> "${if (state.quality.isAac) "AAC · ${state.quality.sampleRateLabel} · ${state.quality.bitRateLabel}" else state.format?.label ?: state.quality.label}\n${if (state.paused) "Paused" else "Recording"} · ${state.checkpointCount} checkpoints"
                state.error != null -> state.error
                !permission -> "Tap a preset to allow microphone access"
                else -> "Tap a preset or Best available to record"
            })
            val ticking = state.active && !state.paused && !state.finishing && !state.preparing
            // A stopped Chronometer still recalculates from its old base when a host reapplies views.
            // Render paused/idle time as text so it remains exact after navigating away and back.
            setViewVisibility(R.id.widget_live_timer, if (ticking) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_timer, if (ticking) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_timer, durationLabel(state.elapsedMs))
            setChronometer(R.id.widget_live_timer, SystemClock.elapsedRealtime() - state.elapsedMs, null, ticking)
            val recordAction = if (state.paused) RecordingService.RESUME else RecordingService.PAUSE
            setTextViewText(R.id.widget_record, if (state.preparing) "Starting recording…" else if (state.paused) "▶  Resume recording" else "Ⅱ  Pause recording")
            fun button(id: Int, command: String, enabled: Boolean, preset: Quality? = null) {
                setBoolean(id, "setEnabled", enabled)
                setFloat(id, "setAlpha", if (enabled) 1f else 0.4f)
                setOnClickPendingIntent(id, action(context, command, state.sessionId, preset))
            }
            setViewVisibility(R.id.widget_presets, if (state.active) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_record, if (state.active && state.projectId == null) View.VISIBLE else View.GONE)
            (listOf(R.id.widget_voice, R.id.widget_balanced, R.id.widget_studio).zip(presets) + (R.id.widget_best to Quality.BEST)).forEach { (id, preset) ->
                setTextViewText(id, if (preset == Quality.BEST) "● Best available · Auto" else "● ${preset.label}\n${preset.sampleRateLabel}\n${preset.bitRateLabel}")
                setContentDescription(id, "Record ${preset.label}, ${preset.sampleRateLabel}, ${preset.bitRateLabel}")
                button(id, RecordingService.START, !state.active && !state.finishing, preset)
            }
            button(R.id.widget_record, recordAction, canControl)
            button(R.id.widget_checkpoint, RecordingService.CHECKPOINT, canControl)
            button(R.id.widget_save, RecordingService.SAVE, canControl)
            button(R.id.widget_discard, RecordingService.REQUEST_DISCARD, state.active && !state.finishing)
            button(R.id.widget_confirm, RecordingService.CONFIRM_DISCARD, canControl)
            button(R.id.widget_cancel, RecordingService.CANCEL_DISCARD, canControl)
            setViewVisibility(R.id.widget_controls, if (state.discardConfirmation) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.widget_confirmation, if (state.discardConfirmation) View.VISIBLE else View.GONE)
        }
    }
}
