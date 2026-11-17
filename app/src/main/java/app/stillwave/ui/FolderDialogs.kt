@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.stillwave.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stillwave.data.*

@Composable fun FolderPicker(folders: List<RecordingFolder>, counts: Map<String?, Int>, current: String,
    moving: Boolean, dismiss: () -> Unit, choose: (String) -> Unit, create: () -> Unit,
    rename: (RecordingFolder) -> Unit, delete: (RecordingFolder) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(if (moving) "Move to folder" else "Folders", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        TextButton(create, Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.Outlined.CreateNewFolder, null); Text(" New folder")
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!moving) item(key = ALL_FOLDERS) {
                FolderChoice("All recordings", counts.values.sum(), current == ALL_FOLDERS, { choose(ALL_FOLDERS) })
            }
            item(key = UNFILED) {
                FolderChoice("Unfiled", counts[null] ?: 0, !moving && current == UNFILED, { choose(UNFILED) })
            }
            items(folders, key = { it.id }) { folder ->
                var menu by remember { mutableStateOf(false) }
                FolderChoice(folder.name, counts[folder.id] ?: 0, !moving && current == folder.id, { choose(folder.id) }) {
                    if (!moving) Box {
                        IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Folder actions: ${folder.name}") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename folder") }, onClick = { menu = false; rename(folder) })
                            DropdownMenuItem(text = { Text("Delete folder", color = Coral) }, onClick = { menu = false; delete(folder) })
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun FolderChoice(name: String, count: Int, selected: Boolean, click: () -> Unit, actions: @Composable () -> Unit = {}) {
    ListItem(headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("$count ${if (count == 1) "recording" else "recordings"}") },
        leadingContent = { Icon(if (selected) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null, tint = if (selected) Lime else Muted) },
        trailingContent = actions, modifier = Modifier.clickable(onClick = click).semantics { contentDescription = "Folder: $name"; this.selected = selected },
        colors = ListItemDefaults.colors(containerColor = Panel))
}

@Composable fun FolderNameDialog(folder: RecordingFolder?, folders: List<RecordingFolder>, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable(folder?.id) { mutableStateOf(folder?.name.orEmpty()) }
    val error = folderNameError(name, folders, folder?.id)
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (folder == null) "New folder" else "Rename folder") },
        text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Folder name") }, singleLine = true,
            isError = name.isNotEmpty() && error != null, supportingText = { if (name.isNotEmpty() && error != null) Text(error) }) },
        confirmButton = { TextButton({ save(name.trim()) }, enabled = error == null) { Text(if (folder == null) "Create" else "Save") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
