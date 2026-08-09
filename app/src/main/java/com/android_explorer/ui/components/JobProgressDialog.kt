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
import com.android_explorer.job.JobProgress
import com.android_explorer.job.JobProgressBus
import com.android_explorer.service.FileJobService
import com.android_explorer.util.Formatting

/** Observes the shared progress bus and shows a Material dialog while a job runs or has just finished. */
@Composable
fun JobProgressDialog() {
    val progress by JobProgressBus.progress.collectAsStateWithLifecycle()
    val p = progress ?: return
    val context = LocalContext.current

    if (p.awaitingPassword) {
        PasswordDialog(p, context)
        return
    }

    AlertDialog(
        onDismissRequest = { if (p.finished) JobProgressBus.clear() },
        title = { Text(dialogTitle(p)) },
        text = {
            if (p.finished) FinishedBody(p) else RunningBody(p)
        },
        confirmButton = {
            if (p.finished) {
                TextButton(onClick = { JobProgressBus.clear() }) { Text("Done") }
            } else {
                TextButton(onClick = { FileJobService.cancel(context) }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PasswordDialog(p: JobProgress, context: android.content.Context) {
    var password by remember(p.jobId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { JobProgressBus.clear() },
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
                onClick = { FileJobService.extract(context, p.sourcePath!!, p.destPath!!, password) },
            ) { Text("Extract") }
        },
        dismissButton = { TextButton(onClick = { JobProgressBus.clear() }) { Text("Cancel") } },
    )
}

private fun dialogTitle(p: JobProgress): String = when (p.state) {
    JobProgress.State.RUNNING -> p.title
    JobProgress.State.SUCCESS -> when (p.kind) {
        JobProgress.Kind.EXTRACT -> "Extraction complete"
        JobProgress.Kind.COMPRESS -> "Archive created"
        JobProgress.Kind.COPY -> "Copy complete"
        JobProgress.Kind.MOVE -> "Move complete"
    }
    JobProgress.State.ERROR -> "Operation failed"
    JobProgress.State.CANCELLED -> "Cancelled"
    JobProgress.State.NEEDS_PASSWORD -> "Password required"
}

@Composable
private fun RunningBody(p: JobProgress) {
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

private fun statusLine(p: JobProgress): String {
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
private fun FinishedBody(p: JobProgress) {
    val (icon, tint, message) = when (p.state) {
        JobProgress.State.SUCCESS -> Triple(
            Icons.Rounded.CheckCircle,
            MaterialTheme.colorScheme.primary,
            p.outputPath?.let { "Saved to:\n$it" } ?: "Completed successfully.",
        )
        JobProgress.State.ERROR -> Triple(
            Icons.Rounded.Error,
            MaterialTheme.colorScheme.error,
            p.error ?: "Unknown error.",
        )
        JobProgress.State.CANCELLED -> Triple(
            Icons.Rounded.Info,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "The operation was cancelled.",
        )
        JobProgress.State.RUNNING, JobProgress.State.NEEDS_PASSWORD ->
            Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.primary, "")
    }
    Column(Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        Spacer(Modifier.size(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        // e.g. "2 items skipped" after a paste, or "12 of 40 files copied" after a cancel.
        p.note?.let {
            Spacer(Modifier.size(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
