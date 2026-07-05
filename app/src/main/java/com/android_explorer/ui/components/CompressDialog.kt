package com.android_explorer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android_explorer.archive.ArchiveType

private val CREATABLE = listOf(
    ArchiveType.ZIP,
    ArchiveType.SEVEN_Z,
    ArchiveType.TAR_GZ,
    ArchiveType.TAR_XZ,
)

private fun ArchiveType.suffix(): String = when (this) {
    ArchiveType.ZIP -> ".zip"
    ArchiveType.SEVEN_Z -> ".7z"
    ArchiveType.TAR -> ".tar"
    ArchiveType.TAR_GZ -> ".tar.gz"
    ArchiveType.TAR_BZ2 -> ".tar.bz2"
    ArchiveType.TAR_XZ -> ".tar.xz"
    else -> ""
}

/** Collects a base name and a target format, then hands back the full filename + type. */
@Composable
fun CompressDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (fileName: String, type: ArchiveType) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var type by remember { mutableStateOf(ArchiveType.ZIP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create archive") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    suffix = { Text(type.suffix()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(16.dp))
                Text("Format", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    CREATABLE.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val base = name.trim()
                    onConfirm(base + type.suffix(), type)
                },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
