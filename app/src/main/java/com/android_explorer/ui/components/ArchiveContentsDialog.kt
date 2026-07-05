package com.android_explorer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android_explorer.archive.ArchiveManager
import com.android_explorer.archive.ArchiveManager.ArchiveListing
import com.android_explorer.data.FileItem
import com.android_explorer.util.Formatting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only preview of an archive's contents (no extraction). Lists entries with per-type icons,
 * a count/size summary, and an Extract shortcut. Entry names load off the main thread.
 */
@Composable
fun ArchiveContentsDialog(
    item: FileItem,
    onDismiss: () -> Unit,
    onExtract: () -> Unit,
) {
    val listing by produceState<ArchiveListing?>(initialValue = null, item.path) {
        value = withContext(Dispatchers.IO) { ArchiveManager.listEntries(item.file) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 380.dp)) {
                when (val l = listing) {
                    null -> LoadingRow()
                    is ArchiveListing.Entries -> EntriesView(l)
                    ArchiveListing.Encrypted -> Note(
                        "This archive is encrypted — its file list can't be shown. " +
                            "Extract it and enter the password when prompted.",
                    )
                    is ArchiveListing.Failure -> Note("Couldn't read this archive: ${l.message}")
                }
            }
        },
        confirmButton = { TextButton(onClick = onExtract) { Text("Extract") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text("Reading contents…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun EntriesView(entries: ArchiveListing.Entries) {
    // Folders first, then files, each alphabetical by path (case-insensitive).
    val sorted = entries.items.sortedWith(
        compareByDescending<ArchiveManager.ArchiveEntryInfo> { it.isDirectory }
            .thenBy { it.name.lowercase() },
    )
    val fileCount = entries.items.count { !it.isDirectory }
    val folderCount = entries.items.size - fileCount
    val totalBytes = entries.items.filter { !it.isDirectory && it.size > 0 }.sumOf { it.size }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = summary(fileCount, folderCount, totalBytes) +
                if (entries.truncated) "  (first ${entries.items.size} shown)" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(sorted, key = { it.name }) { EntryRow(it) }
        }
    }
}

@Composable
private fun EntryRow(entry: ArchiveManager.ArchiveEntryInfo) {
    val kind = kindForName(entry.name, entry.isDirectory)
    // Show the last path segment prominently; the archive tree can nest arbitrarily deep.
    val display = entry.name.trimEnd('/').substringAfterLast('/').ifBlank { entry.name }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kind.icon, contentDescription = null, tint = kind.color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = if (entry.size >= 0) Formatting.bytes(entry.size) else "—",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun summary(files: Int, folders: Int, totalBytes: Long): String {
    val parts = mutableListOf<String>()
    parts += "$files file" + if (files == 1) "" else "s"
    if (folders > 0) parts += "$folders folder" + if (folders == 1) "" else "s"
    if (totalBytes > 0) parts += Formatting.bytes(totalBytes)
    return parts.joinToString("  •  ")
}
