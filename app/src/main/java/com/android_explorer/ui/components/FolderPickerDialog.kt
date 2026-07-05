package com.android_explorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** In-app directory chooser. Navigates real folders (all-files access) and returns a File. */
@Composable
fun FolderPickerDialog(
    startDir: File,
    rootDir: File,
    listFolders: (File) -> List<File>,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
) {
    var current by remember { mutableStateOf(startDir) }
    val folders = remember(current.absolutePath) { listFolders(current) }
    val atRoot = current.absolutePath == rootDir.absolutePath

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract to folder") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { current.parentFile?.let { if (!atRoot) current = it } },
                        enabled = !atRoot,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up")
                    }
                    Text(
                        text = if (atRoot) "Internal storage" else current.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    if (folders.isEmpty()) {
                        item {
                            Text(
                                "No sub-folders here",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                    items(folders, key = { it.absolutePath }) { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { current = f }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                f.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Extract here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
