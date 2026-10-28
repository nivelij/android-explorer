package com.android_explorer.ui.update

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.android_explorer.BuildConfig
import com.android_explorer.data.AppRelease
import com.android_explorer.util.Formatting

/** Offers a newer GitHub release; the download itself runs as a regular foreground file job. */
@Composable
fun UpdateDialog(
    release: AppRelease,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val size = release.apkSize.takeIf { it > 0 }?.let { " (${Formatting.bytes(it)})" } ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Text("Explorer ${release.tag} is out — you have v${BuildConfig.VERSION_NAME}. Download and install it now$size?")
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
