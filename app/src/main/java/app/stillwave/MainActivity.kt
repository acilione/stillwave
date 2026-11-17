package app.stillwave

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import app.stillwave.audio.RecordingService
import app.stillwave.data.Recording
import app.stillwave.data.Quality
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stillwave.ui.StillwaveTheme
import app.stillwave.ui.isNightTheme
import app.stillwave.ui.StillwaveScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val quality = vm.pendingRecordingQuality
        val folder = vm.pendingRecordingFolder
        val project = vm.pendingRecordingProject
        vm.pendingRecordingQuality = null
        vm.pendingRecordingFolder = null
        vm.pendingRecordingProject = null
        if (result[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            quality?.let { startRecording(it, folder, project) }
        } else vm.notify("Allow microphone access in Android Settings to record audio")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(night = true)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val night = isNightTheme(settings.themeMode)
            LaunchedEffect(night) { applySystemBars(night) }
            StillwaveTheme(night) { StillwaveScreen(vm, { requestRecording(it) }, ::share, { quality, id -> requestRecording(quality, id) }) }
        }
    }
    /** Light bars need dark icons; transparent bars keep the edge-to-edge layout either way. */
    private fun applySystemBars(night: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (night) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    private fun requestRecording(quality: Quality, projectId: String? = null) {
        if (vm.pendingRecordingQuality != null) return
        val requested = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requested += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requested += Manifest.permission.POST_NOTIFICATIONS
        if (requested.isEmpty()) startRecording(quality, projectId = projectId) else {
            vm.pendingRecordingQuality = quality
            vm.pendingRecordingFolder = vm.recordingFolderId
            vm.pendingRecordingProject = projectId
            permissions.launch(requested.toTypedArray())
        }
    }
    private fun startRecording(quality: Quality, folderId: String? = vm.recordingFolderId, projectId: String? = null) {
        runCatching { RecordingService.command(this, RecordingService.START, quality, folderId, projectId) }.onFailure { vm.notify(it.message ?: "Could not start recording") }
    }
    private fun share(items: List<Recording>) { runCatching { startActivity(Intent.createChooser(vm.shareIntent(items), "Share recordings")) }.onFailure { vm.notify("No app available to share this audio") } }
}
