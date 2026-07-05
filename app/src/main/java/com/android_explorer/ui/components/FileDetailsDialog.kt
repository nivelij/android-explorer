package com.android_explorer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android_explorer.data.FileDetails
import com.android_explorer.util.Formatting

/** Read-only properties popup for a file/folder. */
@Composable
fun FileDetailsDialog(details: FileDetails, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(details.name, maxLines = 2) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Detail("Type", details.type)
                Detail("Size", Formatting.bytes(details.sizeBytes))
                if (details.itemCount != null) {
                    Detail("Contains", "${details.itemCount} item" + if (details.itemCount == 1) "" else "s")
                }
                Detail("Path", details.path)
                Detail("Modified", Formatting.dateTime(details.modifiedMillis))
                Detail("Created", Formatting.dateTime(details.createdMillis))
                Detail("Accessed", Formatting.dateTime(details.accessedMillis))
                Detail("Access", buildString {
                    append(if (details.readable) "read" else "no-read")
                    append(" / ")
                    append(if (details.writable) "write" else "read-only")
                    if (details.hidden) append(" / hidden")
                })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
