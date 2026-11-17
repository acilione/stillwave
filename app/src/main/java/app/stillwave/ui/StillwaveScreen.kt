@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.stillwave.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*
import app.stillwave.AppViewModel
import app.stillwave.audio.RecordingService
import app.stillwave.data.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.*

@Composable fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    IconButton(onClick) { Icon(icon, label, tint = tint) }
}

@Composable fun StillwaveScreen(vm: AppViewModel, record: (Quality) -> Unit, share: (List<Recording>) -> Unit,
    recordProject: (Quality, String) -> Unit = { quality, _ -> record(quality) }) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val selectedFolder by vm.selectedFolder.collectAsStateWithLifecycle()
    val folderNames = remember(folders) { folders.associate { it.id to it.name } }
    val folderCounts = remember(all) { all.groupingBy { it.folderId }.eachCount() }
    val editorId by vm.editorId.collectAsStateWithLifecycle()
    val studioOpen by vm.studio.open.collectAsStateWithLifecycle()
    val exportId by vm.exportJob.collectAsStateWithLifecycle()
    val exportStarting by vm.exportStarting.collectAsStateWithLifecycle()
    // The minimal recorder displays whole seconds, so audio-meter ticks need not redraw the library.
    val capture by remember {
        RecordingService.state.map { it.copy(amplitude = 0f, elapsedMs = it.elapsedMs / 1000 * 1000) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = remember { RecordingService.state.value })
    val playback by vm.playback.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val savedRecordingId by RecordingService.lastSavedRecordingId.collectAsStateWithLifecycle()
    val libraryState = rememberLazyGridState()
    var libraryExpanded by rememberSaveable { mutableStateOf(false) }
    var handledSavedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRevealId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var menuRecording by remember { mutableStateOf<Recording?>(null) }
    var renameRecording by remember { mutableStateOf<Recording?>(null) }
    var detailsRecording by remember { mutableStateOf<Recording?>(null) }
    var deleteIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var foldersOpen by rememberSaveable { mutableStateOf(false) }
    var moveIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<RecordingFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<RecordingFolder?>(null) }
    var discard by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // A restored or externally applied query must never silently hide an active filter.
    val showSearch = searchOpen || filter.search.isNotEmpty()
    val closeSearch = {
        pendingRevealId = null
        vm.filter.value = filter.copy(search = "")
        searchOpen = false
        focusManager.clearFocus()
        keyboard?.hide()
        Unit
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(savedRecordingId, capture.active) {
        if (capture.active || savedRecordingId == null) { pendingRevealId = null; return@LaunchedEffect }
        val id = savedRecordingId ?: return@LaunchedEffect
        if (id == handledSavedId) return@LaunchedEffect
        val saved = vm.dao.get(id)
        handledSavedId = id
        if (saved != null) {
            selected = emptyList()
            // Reveal this file even if the user browsed elsewhere or applied a filter during capture.
            if (selectedFolder != ALL_FOLDERS && selectedFolder != (saved.folderId ?: UNFILED)) vm.selectFolder(saved.folderId ?: UNFILED)
            val (from, until) = periodBounds(filter.from, filter.through)
            if (organizeRecordings(listOf(saved), settings.sort, from, until, filter.search).isEmpty()) vm.filter.value = app.stillwave.LibraryFilter()
            pendingRevealId = id
        }
    }
    LaunchedEffect(pendingRevealId, recordings, settingsOpen, editorId, studioOpen) {
        val id = pendingRevealId ?: return@LaunchedEffect
        if (settingsOpen || editorId != null || studioOpen) return@LaunchedEffect
        val index = recordings.indexOfFirst { it.id == id }
        if (index >= 0) {
            // Wait for the newly inserted item to reach the grid before jumping to it.
            snapshotFlow { libraryState.layoutInfo.totalItemsCount }.first { it == recordings.size }
            libraryState.scrollToItem(index)
            pendingRevealId = null
        } else if (vm.dao.get(id) == null) pendingRevealId = null
    }
    LaunchedEffect(Unit) { vm.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(capture.error) { capture.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(exportId) {
        exportId?.let { id -> WorkManager.getInstance(vm.app).getWorkInfoByIdFlow(UUID.fromString(id)).filterNotNull().first { it.state.isFinished }.let { work ->
            vm.notify(if (work.state == WorkInfo.State.SUCCEEDED) "${work.outputData.getInt("count", 0)} recordings saved to your library" else work.outputData.getString("error") ?: "Export cancelled")
            vm.clearExport()
        } }
    }
    val exportWork by remember(exportId) {
        exportId?.let { WorkManager.getInstance(vm.app).getWorkInfoByIdFlow(UUID.fromString(it)) } ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val exporting = exportStarting || exportId != null && exportWork?.state?.isFinished != true
    BackHandler(editorId != null || settingsOpen || studioOpen || selected.isNotEmpty() || showSearch || libraryExpanded || selectedFolder != ALL_FOLDERS) {
        when { settingsOpen -> settingsOpen = false; studioOpen -> vm.studio.back(); editorId != null -> vm.openEditor(null); selected.isNotEmpty() -> selected = emptyList(); showSearch -> closeSearch(); libraryExpanded -> libraryExpanded = false; else -> vm.selectFolder(ALL_FOLDERS) }
    }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (settingsOpen) {
                SettingsScreen(settings, vm) { settingsOpen = false }
            } else if (studioOpen) {
                StudioScreen(vm, recordProject)
            } else if (editorId != null) {
                EditorScreen(vm, editorId!!, exporting, { vm.openEditor(null) }, { deleteIds = listOf(it.id) })
            } else {
                if (!libraryExpanded) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = Lime, modifier = Modifier.size(28.dp))
                    Text(" STILLWAVE", style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                    ActionIcon(Icons.Outlined.QueueMusic, "Multitrack and loop station", { vm.pausePlayback(); vm.studio.show() })
                    val night = LocalPalette.current.dark
                    ActionIcon(if (night) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (night) "Switch to light mode" else "Switch to night mode",
                        { vm.setting(Settings.THEME_MODE, (if (night) ThemeMode.LIGHT else ThemeMode.DARK).name) })
                    ActionIcon(Icons.Outlined.Settings, "Settings", { settingsOpen = true })
                }
                CapturePanel(capture, settings.selectedRecordingSlot, settings.recordStyle, { vm.setting(Settings.SELECTED_RECORDING_SLOT, it) }, record, { action ->
                    if (action == RecordingService.DISCARD && settings.confirmDiscard) discard = true
                    else RecordingService.command(vm.app, action)
                }, presets = settings.recordingSlots)
                } else if (capture.active) {
                    TextButton({ libraryExpanded = false }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Mic, null, tint = Coral)
                        Text(" ${if (capture.paused) "Paused" else if (capture.preparing) "Starting" else "Recording"} · ${durationLabel(capture.elapsedMs)} · Show recorder")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (libraryExpanded) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ foldersOpen = true }, Modifier.weight(1f).semantics { contentDescription = "Choose folder" },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(when (selectedFolder) { ALL_FOLDERS -> "All recordings"; UNFILED -> "Unfiled"; else -> folderNames[selectedFolder] ?: "Folder" },
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${recordings.size} ${if (recordings.size == 1) "recording" else "recordings"}",
                                color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
                    }
                    ActionIcon(Icons.Outlined.Search, "Search recordings", { searchOpen = true; if (showSearch) { searchFocus.requestFocus(); keyboard?.show() } },
                        if (showSearch) Lime else Muted)
                    ActionIcon(if (libraryExpanded) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        if (libraryExpanded) "Exit full-screen library" else "Expand library", { libraryExpanded = !libraryExpanded })
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        ActionIcon(Icons.Outlined.Tune, "Layout and ordering", { menu = true }, if (filter.from != null || filter.through != null) Lime else Muted)
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Multitrack and loop station") }, onClick = { menu = false; vm.pausePlayback(); vm.studio.show() })
                            DropdownMenuItem(text = { Text("Filter by recording period") }, leadingIcon = { Icon(Icons.Outlined.DateRange, null) },
                                onClick = { menu = false; filterOpen = true })
                            HorizontalDivider()
                            Text("LAYOUT", color = Muted, modifier = Modifier.padding(16.dp))
                            LibraryLayout.entries.forEach { layout -> DropdownMenuItem(text = { Text("${if (settings.layout == layout) "✓  " else ""}${layout.label}") }, onClick = { vm.setting(Settings.LAYOUT, layout.name); menu = false }) }
                            HorizontalDivider()
                            Text("ORDER BY", color = Muted, modifier = Modifier.padding(16.dp))
                            SortOrder.entries.forEach { order -> DropdownMenuItem(text = { Text("${if (settings.sort == order) "✓  " else ""}${order.label}") }, onClick = { vm.setting(Settings.SORT, order.name); menu = false }) }
                        }
                    }
                }
                if (showSearch) {
                    OutlinedTextField(filter.search, { pendingRevealId = null; vm.filter.value = filter.copy(search = it) }, placeholder = { Text("Search recordings") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { ActionIcon(Icons.Outlined.Close, "Close search", closeSearch) },
                        singleLine = true, shape = RoundedCornerShape(18.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp).focusRequester(searchFocus).testTag("recordingSearch"))
                    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }
                }
                if (filter.from != null || filter.through != null) {
                    Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${filter.from ?: "Beginning"} → ${filter.through ?: "Today"}", color = Lime, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton({ pendingRevealId = null; vm.filter.value = filter.copy(from = null, through = null) }) { Text("Clear") }
                    }
                }
                if (selected.isNotEmpty()) {
                    val picked = all.filter { it.id in selected }
                    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(Icons.Outlined.Close, "Clear selection", { selected = emptyList() })
                        Text(if (deleting) "…" else "${selected.size}", color = Lime)
                        Spacer(Modifier.weight(1f))
                        ActionIcon(Icons.Outlined.QueueMusic, "Create multitrack from selection", {
                            vm.pausePlayback(); vm.studio.create("Multitrack ${defaultTitle(System.currentTimeMillis())}", false, picked); selected = emptyList()
                        })
                        if (picked.size == 1) ActionIcon(Icons.Outlined.Info, "Selected recording details", { detailsRecording = picked.single() })
                        ActionIcon(Icons.Outlined.SelectAll, "Select all visible recordings", { selected = recordings.map { it.id } })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionIcon(Icons.Outlined.PushPin, "Pin selected recordings", { vm.pin(selected, !picked.all { it.pinned }) })
                        ActionIcon(Icons.Outlined.Share, "Share selected recordings", { share(picked) })
                        ActionIcon(Icons.Outlined.DriveFileMove, "Move selected recordings", { moveIds = selected.toList() })
                        ActionIcon(Icons.Outlined.DeleteOutline, "Delete selected recordings", { if (!deleting) deleteIds = selected.toList() }, Coral)
                    }
                    }
                }
                if (recordings.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(40.dp), tint = Muted)
                        Text(if (all.isEmpty()) "No recordings" else "No recordings in this view", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                        Text(if (all.isEmpty()) "Tap Record to make your first recording." else if (selectedFolder != ALL_FOLDERS) "Record here or move recordings into this folder." else "Try another search or recording period.", color = Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    LazyVerticalGrid(columns = if (settings.layout == LibraryLayout.GRID) GridCells.Adaptive(165.dp) else GridCells.Fixed(1), state = libraryState,
                        modifier = Modifier.weight(1f).testTag("recordingLibrary"), contentPadding = PaddingValues(24.dp, 4.dp, 24.dp, 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recordings, key = { it.id }) { item ->
                            RecordingCard(item, settings.layout, item.id in selected, playback.recordingId == item.id && playback.windowId == null && playback.playing,
                                onClick = { if (selected.isNotEmpty()) selected = if (item.id in selected) selected - item.id else selected + item.id else vm.openEditor(item.id) },
                                onLongClick = {
                                    if (selected.isNotEmpty() || settings.longPressAction == LongPressAction.MULTI_SELECT)
                                        selected = if (item.id in selected) selected - item.id else selected + item.id
                                    else menuRecording = item
                                }, onPlay = { vm.play(item) }, onMenu = { menuRecording = item },
                                showLoop = settings.showLoopButton, looping = playback.recordingId == item.id && playback.windowId == null && playback.looping,
                                onLoop = { vm.toggleLoop(item) },
                                folderName = if (selectedFolder == ALL_FOLDERS) folderNames[item.folderId] else null)
                        }
                    }
                }
            }
        }
    }
    if (foldersOpen || moveIds.isNotEmpty()) {
        FolderPicker(folders, folderCounts, selectedFolder, moveIds.isNotEmpty(),
            dismiss = { foldersOpen = false; moveIds = emptyList() }, choose = { target ->
                if (moveIds.isEmpty()) { pendingRevealId = null; vm.selectFolder(target); selected = emptyList(); foldersOpen = false }
                else {
                    val moved = moveIds.toList()
                    vm.moveRecordings(moved, target.takeUnless { it == UNFILED }) {
                        selected = selected.filterNot { it in moved }; moveIds = emptyList(); foldersOpen = false
                    }
                }
            }, create = { newFolder = true }, rename = { renameFolder = it }, delete = { deleteFolder = it })
    }
    if (newFolder) FolderNameDialog(null, folders, { newFolder = false }) { name ->
        val moved = moveIds.toList()
        vm.createFolder(name, moved) { folder ->
            newFolder = false; foldersOpen = false; moveIds = emptyList(); selected = emptyList()
            pendingRevealId = null
            vm.selectFolder(folder.id)
        }
    }
    renameFolder?.let { folder -> FolderNameDialog(folder, folders, { renameFolder = null }) { name ->
        vm.renameFolder(folder, name) { renameFolder = null }
    } }
    deleteFolder?.let { folder ->
        AlertDialog(onDismissRequest = { deleteFolder = null }, title = { Text("Delete folder?") },
            text = { Text("Remove “${folder.name}”? Its recordings will move to Unfiled. No audio will be deleted.") },
            confirmButton = { TextButton({ vm.deleteFolder(folder); deleteFolder = null }) { Text("Delete folder", color = Coral) } },
            dismissButton = { TextButton({ deleteFolder = null }) { Text("Cancel") } })
    }
    if (deleteIds.isNotEmpty()) {
        val single = all.firstOrNull { it.id == deleteIds.singleOrNull() }
        AlertDialog(onDismissRequest = { deleteIds = emptyList() },
            title = { Text(if (deleteIds.size == 1) "Delete recording?" else "Delete ${deleteIds.size} recordings?") },
            text = { Text((single?.let { "“${it.title}”\n\n" } ?: "") + "Permanently delete the selected audio, checkpoints, and cut windows from this device? Saved cuts are kept.") },
            confirmButton = { TextButton({
                val confirmed = deleteIds; deleteIds = emptyList(); selected = selected.filterNot { it in confirmed }; vm.deleteRecordings(confirmed)
            }, enabled = !deleting) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton({ deleteIds = emptyList() }) { Text("Cancel") } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this recording?") }, text = { Text("The audio you just recorded will be permanently discarded. You can change this confirmation in Settings.") },
        confirmButton = { TextButton({ discard = false; RecordingService.command(vm.app, RecordingService.DISCARD) }) { Text("Discard", color = Coral) } }, dismissButton = { TextButton({ discard = false }) { Text("Keep recording") } })
    if (filterOpen) PeriodDialog(filter.from, filter.through, { filterOpen = false }) { from, through -> pendingRevealId = null; vm.filter.value = filter.copy(from = from, through = through); filterOpen = false }
    renameRecording?.let { recording -> RenameDialog(recording.title, { renameRecording = null }) { vm.rename(recording, it); renameRecording = null } }
    detailsRecording?.let { recording -> RecordingDetailsDialog(recording, vm.app.repository.file(recording), folderNames[recording.folderId]) { detailsRecording = null } }
    menuRecording?.let { recording ->
        ModalBottomSheet(onDismissRequest = { menuRecording = null }, containerColor = Panel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(recording.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            fun close(action: () -> Unit) { menuRecording = null; action() }
            MenuAction(Icons.Outlined.Info, "File details") { close { detailsRecording = recording } }
            val looping = playback.recordingId == recording.id && playback.windowId == null && playback.looping
            MenuAction(Icons.Outlined.Repeat, if (looping) "Turn off loop" else "Loop recording") { close { vm.toggleLoop(recording) } }
            MenuAction(Icons.Outlined.PushPin, if (recording.pinned) "Unpin recording" else "Pin recording") { close { vm.pin(listOf(recording.id), !recording.pinned) } }
            MenuAction(Icons.Outlined.Share, "Share audio") { close { share(listOf(recording)) } }
            MenuAction(Icons.Outlined.ContentCut, "Edit & create cuts") { close { vm.openEditor(recording.id) } }
            MenuAction(Icons.Outlined.QueueMusic, "Open in multitrack") { close {
                vm.pausePlayback(); vm.studio.create(recording.title.take(160), false, listOf(recording))
            } }
            MenuAction(Icons.Outlined.Edit, "Rename") { close { renameRecording = recording } }
            MenuAction(Icons.Outlined.Checklist, "Select multiple") { close { selected = (selected + recording.id).distinct() } }
            MenuAction(Icons.Outlined.DriveFileMove, "Move to folder") { close { moveIds = listOf(recording.id) } }
            MenuAction(Icons.Outlined.DeleteOutline, "Delete recording", Coral) { close { if (!deleting) deleteIds = listOf(recording.id) } }
            Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable private fun MenuAction(icon: ImageVector, title: String, tint: Color = Lime, action: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, leadingContent = { Icon(icon, null, tint = tint) }, modifier = Modifier.clickable(onClick = action), colors = ListItemDefaults.colors(containerColor = Panel))
}


@Composable fun Waveform(peaks: List<Float>, modifier: Modifier, progress: Float = 1f) {
    val played = Lime
    val muted = Muted
    Canvas(modifier) {
        if (peaks.isEmpty()) drawLine(muted.copy(alpha = .3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
        else peaks.forEachIndexed { i, peak ->
            val x = size.width * (i + .5f) / peaks.size
            val height = (peak.coerceIn(0f, 1f) * size.height).coerceAtLeast(3f)
            drawLine(if (i.toFloat() / peaks.size <= progress) played else muted.copy(alpha = .4f), Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), (size.width / peaks.size * .55f).coerceAtLeast(1f), StrokeCap.Round)
        }
    }
}

@Composable private fun RecordingCard(recording: Recording, layout: LibraryLayout, selected: Boolean, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onPlay: () -> Unit, onMenu: () -> Unit,
    showLoop: Boolean, looping: Boolean, onLoop: () -> Unit, folderName: String? = null) {
    val grid = layout == LibraryLayout.GRID
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else Panel, shape = RoundedCornerShape(18.dp), border = if (selected) BorderStroke(1.dp, Lime) else null,
        modifier = Modifier.fillMaxWidth().testTag("recording-${recording.id}").semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(if (layout == LibraryLayout.COMPACT) 8.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!grid) {
                    FilledTonalIconButton(onPlay, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Tonal, contentColor = Lime)) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause playback" else "Play recording") }
                    if (showLoop) RecordingLoopButton(recording.id, looping, onLoop)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (recording.pinned) { Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(13.dp), tint = Lime); Spacer(Modifier.width(4.dp)) }
                        Text(recording.title, maxLines = if (grid) 2 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    }
                    Text("${durationLabel(recording.durationMs)}  ·  ${recording.sampleRate / 1000f} kHz", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
                    folderName?.let { Text(it, color = Lime, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                ActionIcon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.MoreVert, "Recording actions", onMenu)
            }
            if (grid) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(onPlay) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "Play or pause recording") }
                if (showLoop) RecordingLoopButton(recording.id, looping, onLoop)
            }
        }
    }
}

@Composable private fun RecordingLoopButton(id: String, looping: Boolean, toggle: () -> Unit) {
    IconToggleButton(looping, { toggle() }, Modifier.testTag("loop-recording-$id"),
        colors = IconButtonDefaults.iconToggleButtonColors(contentColor = Muted, checkedContentColor = Lime,
            checkedContainerColor = Lime.copy(alpha = 0.12f))) {
        Icon(Icons.Outlined.Repeat, if (looping) "Turn off recording loop" else "Loop recording")
    }
}

@Composable fun RenameDialog(initial: String, dismiss: () -> Unit, dialogTitle: String = "Rename recording", save: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(dialogTitle) }, text = { OutlinedTextField(text, { if (it.length <= 160) text = it }, label = { Text("Recording title") }, singleLine = true) },
        confirmButton = { TextButton({ save(text) }, enabled = text.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@Composable private fun PeriodDialog(initialFrom: LocalDate?, initialThrough: LocalDate?, dismiss: () -> Unit, apply: (LocalDate?, LocalDate?) -> Unit) {
    var from by remember { mutableStateOf(initialFrom?.toString() ?: "") }
    var through by remember { mutableStateOf(initialThrough?.toString() ?: "") }
    val parsedFrom = runCatching { LocalDate.parse(from) }.getOrNull()
    val parsedThrough = runCatching { LocalDate.parse(through) }.getOrNull()
    val valid = (from.isBlank() || parsedFrom != null) && (through.isBlank() || parsedThrough != null) && (parsedFrom == null || parsedThrough == null || parsedFrom <= parsedThrough)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Recording period") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Uses the date recorded, even after renaming. Both dates are inclusive.", color = Muted)
            OutlinedTextField(from, { from = it }, label = { Text("From · YYYY-MM-DD") }, singleLine = true, isError = from.isNotBlank() && parsedFrom == null)
            OutlinedTextField(through, { through = it }, label = { Text("Through · YYYY-MM-DD") }, singleLine = true, isError = through.isNotBlank() && parsedThrough == null)
            Row { TextButton({ from = LocalDate.now().toString(); through = from }) { Text("Today") }; TextButton({ from = LocalDate.now().minusDays(6).toString(); through = LocalDate.now().toString() }) { Text("Last 7 days") } }
            if (!valid) Text("Enter valid dates in chronological order.", color = Coral)
        }
    }, confirmButton = { TextButton({ apply(parsedFrom, parsedThrough) }, enabled = valid) { Text("Apply") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}


@Composable fun CaptureDetails(inputName: String?, details: String?, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    if (compact) {
        IconButton({ open = true }, enabled = details != null) {
            Icon(Icons.Outlined.Info, "Recording format details", Modifier.size(20.dp))
        }
    } else if (details != null) {
        TextButton({ open = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(inputName ?: "Recording details", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.Info, "Recording format details", Modifier.padding(start = 6.dp).size(16.dp))
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(inputName ?: "Recording details") },
        text = { Text(details.orEmpty(), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ open = false }) { Text("Close") } })
}
