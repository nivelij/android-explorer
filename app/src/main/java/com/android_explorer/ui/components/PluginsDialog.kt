package com.android_explorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_explorer.util.PluginManager

/**
 * Settings pop-up for the built-in "plugins" (viewers). Each toggle chooses whether that file type
 * opens in the app's own screen or is handed to another app via the system chooser.
 */
@Composable
fun PluginsDialog(onDismiss: () -> Unit) {
    val editorOn by PluginManager.textEditor.collectAsStateWithLifecycle()
    val pdfOn by PluginManager.pdfReader.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
        title = { Text("Plugins") },
        text = {
            Column {
                PluginRow(
                    icon = Icons.Rounded.EditNote,
                    title = "Text editor",
                    subtitle = "Open text & code files in the built-in editor",
                    checked = editorOn,
                    onChange = PluginManager::setTextEditor,
                )
                PluginRow(
                    icon = Icons.Rounded.PictureAsPdf,
                    title = "PDF reader",
                    subtitle = "Open PDFs in the built-in reader",
                    checked = pdfOn,
                    onChange = PluginManager::setPdfReader,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PluginRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
