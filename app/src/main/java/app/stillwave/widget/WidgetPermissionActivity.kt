package app.stillwave.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.stillwave.audio.RecordingService
import app.stillwave.data.Quality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Only shown when the widget needs Android's runtime permission; never opens the library. */
class WidgetPermissionActivity : ComponentActivity() {
    private var requesting = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        if (hasMicrophone()) startRecording() else {
            android.app.AlertDialog.Builder(this).setTitle("Microphone access required")
                .setMessage("Allow microphone access in Android settings to record from the widget.")
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    finish()
                }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        }
        RecorderWidget.updateAll(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requesting = savedInstanceState?.getBoolean("requesting") ?: false
        if (hasMicrophone()) startRecording()
        else if (!requesting) {
            requesting = true
            permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("requesting", requesting); super.onSaveInstanceState(outState) }
    private fun hasMicrophone() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun startRecording() {
        // Start while this small permission activity is visible, then return to the launcher.
        lifecycleScope.launch {
            val quality = intent.getStringExtra(RecordingService.QUALITY)?.let(Quality::valueOf) ?: Quality.BEST
            RecordingService.command(this@WidgetPermissionActivity, RecordingService.START, quality)
            withTimeoutOrNull(10_000) { RecordingService.state.first { it.active } }
            finish()
        }
    }
}
