package com.android_explorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.android_explorer.data.FileItem

/** Long-press context menu for a single file/folder, shown as a centered pop-up dialog. */
@Composable
fun FileContextSheet(
    item: FileItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onZip: () -> Unit,
    onViewContents: (() -> Unit)? = null,
    onExtract: (() -> Unit)?,
    onShare: (() -> Unit)? = null,
    onSetWallpaper: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
) {
    ContextPopup(item, onDismiss) {
        Action(Icons.Rounded.OpenInNew, "Open") { onOpen() }
        Action(Icons.Rounded.ContentCopy, "Copy") { onCopy() }
        Action(Icons.Rounded.ContentCut, "Cut") { onCut() }
        Action(Icons.Rounded.DriveFileRenameOutline, "Rename") { onRename() }
        Action(Icons.Rounded.Archive, "Compress (Zip)") { onZip() }
        if (onShare != null) {
            Action(Icons.Rounded.Share, "Share") { onShare() }
        }
        if (onSetWallpaper != null) {
            Action(Icons.Rounded.Wallpaper, "Set as wallpaper") { onSetWallpaper() }
        }
        if (onViewContents != null) {
            Action(Icons.Rounded.Visibility, "View contents") { onViewContents() }
        }
        if (onExtract != null) {
            Action(Icons.Rounded.Unarchive, "Extract here") { onExtract() }
        }
        Action(Icons.Rounded.Checklist, "Select") { onSelect() }
        Action(Icons.Rounded.Info, "Details") { onDetails() }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Action(Icons.Rounded.Delete, "Delete", destructive = true) { onDelete() }
    }
}

/**
 * Slimmer long-press menu for the home screen's Recents list. Recents are a flat set of files with
 * no browsing/paste context, so this offers only the actions that stand alone: open, archive
 * preview/extract, details, delete.
 */
@Composable
fun RecentsContextSheet(
    item: FileItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onViewContents: (() -> Unit)?,
    onExtract: (() -> Unit)?,
    onShare: (() -> Unit)? = null,
    onSetWallpaper: (() -> Unit)? = null,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    ContextPopup(item, onDismiss) {
        Action(Icons.Rounded.OpenInNew, "Open") { onOpen() }
        if (onShare != null) {
            Action(Icons.Rounded.Share, "Share") { onShare() }
        }
        if (onSetWallpaper != null) {
            Action(Icons.Rounded.Wallpaper, "Set as wallpaper") { onSetWallpaper() }
        }
        if (onViewContents != null) {
            Action(Icons.Rounded.Visibility, "View contents") { onViewContents() }
        }
        if (onExtract != null) {
            Action(Icons.Rounded.Unarchive, "Extract here") { onExtract() }
        }
        Action(Icons.Rounded.Info, "Details") { onDetails() }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Action(Icons.Rounded.Delete, "Delete", destructive = true) { onDelete() }
    }
}

/**
 * Shared centered pop-up: a coloured type icon + file name header over a scrollable action list.
 *
 * Built on a plain [Dialog] rather than `AlertDialog`: AlertDialog's `text` slot is tightly
 * height-constrained, so a long action list would show only a few rows and force scrolling. Here the
 * list is free to grow up to ~55% of the screen height before it starts scrolling.
 */
@Composable
private fun ContextPopup(
    item: FileItem,
    onDismiss: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(vertical = 20.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val specialRes = specialFolderIconRes(item)
                    if (specialRes != null) {
                        Icon(
                            painter = painterResource(specialRes),
                            contentDescription = null,
                            tint = colorFor(item),
                            modifier = Modifier.size(30.dp),
                        )
                    } else {
                        Icon(
                            imageVector = iconFor(item),
                            contentDescription = null,
                            tint = colorFor(item),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                ) { actions() }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Action(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = textColor)
    }
}
