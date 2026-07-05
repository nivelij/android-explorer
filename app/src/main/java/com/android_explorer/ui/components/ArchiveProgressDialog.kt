package com.android_explorer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_explorer.archive.ArchiveProgress
import com.android_explorer.archive.ArchiveProgressBus
import com.android_explorer.service.ArchiveService
import com.android_explorer.util.Formatting

/** Observes the shared progress bus and shows a Material dialog while a job runs or has just finished. */
@Composable
fun ArchiveProgressDialog() {
    val progress by ArchiveProgressBus.progress.collectAsStateWithLifecycle()
    val p = progress ?: return
    val context = LocalContext.current

    if (p.awaitingPassword) {
        PasswordDialog(p, context)
        return
    }

    AlertDialog(
        onDismissRequest = { if (p.finished) ArchiveProgressBus.clear() },
        title = { Text(dialogTitle(p)) },
        text = {
            if (p.finished) FinishedBody(p) else RunningBody(p)
        },
        confirmButton = {
            if (p.finished) {
                TextButton(onClick = { ArchiveProgressBus.clear() }) { Text("Done") }
            } else {
                TextButton(onClick = { ArchiveService.cancel(context) }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PasswordDialog(p: ArchiveProgress, context: android.content.Context) {
    var password by remember(p.jobId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { ArchiveProgressBus.clear() },
        title = { Text("Password required") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "This archive is protected. Enter its password to extract.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = p.wrongPassword,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (p.wrongPassword) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Wrong password — try again.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank() && p.sourcePath != null && p.destPath != null,
                onClick = { ArchiveService.extract(context, p.sourcePath!!, p.destPath!!, password) },
            ) { Text("Extract") }
        },
        dismissButton = { TextButton(onClick = { ArchiveProgressBus.clear() }) { Text("Cancel") } },
    )
}

private fun dialogTitle(p: ArchiveProgress): String = when (p.state) {
    ArchiveProgress.State.RUNNING -> p.title
    ArchiveProgress.State.SUCCESS ->
        if (p.kind == ArchiveProgress.Kind.EXTRACT) "Extraction complete" else "Archive created"
    ArchiveProgress.State.ERROR -> "Operation failed"
    ArchiveProgress.State.CANCELLED -> "Cancelled"
    ArchiveProgress.State.NEEDS_PASSWORD -> "Password required"
}

@Composable
private fun RunningBody(p: ArchiveProgress) {
    Column(Modifier.fillMaxWidth()) {
        if (p.indeterminate) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round,
            )
        } else {
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = p.currentEntry.ifEmpty { "Preparing…" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = statusLine(p),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusLine(p: ArchiveProgress): String {
    val pct = if (!p.indeterminate) "${(p.fraction * 100).toInt()}%  •  " else ""
    val bytes = if (p.totalBytes > 0) {
        "${Formatting.bytes(p.processedBytes)} / ${Formatting.bytes(p.totalBytes)}"
    } else {
        Formatting.bytes(p.processedBytes)
    }
    val entries = if (p.totalEntries > 0) "  •  ${p.processedEntries}/${p.totalEntries} files" else ""
    return "$pct$bytes$entries"
}

@Composable
private fun FinishedBody(p: ArchiveProgress) {
    val (icon, tint, message) = when (p.state) {
        ArchiveProgress.State.SUCCESS -> Triple(
            Icons.Rounded.CheckCircle,
            MaterialTheme.colorScheme.primary,
            p.outputPath?.let { "Saved to:\n$it" } ?: "Completed successfully.",
        )
        ArchiveProgress.State.ERROR -> Triple(
            Icons.Rounded.Error,
            MaterialTheme.colorScheme.error,
            p.error ?: "Unknown error.",
        )
        ArchiveProgress.State.CANCELLED -> Triple(
            Icons.Rounded.Info,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "The operation was cancelled.",
        )
        ArchiveProgress.State.RUNNING, ArchiveProgress.State.NEEDS_PASSWORD ->
            Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.primary, "")
    }
    Column(Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        Spacer(Modifier.size(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
