package com.android_explorer.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android_explorer.data.FileItem
import com.android_explorer.util.Formatting
import kotlinx.coroutines.launch

/** One row in the long-press context menu. */
private data class ContextAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Long-press context menu for a single file/folder. */
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
    val actions = buildList {
        add(ContextAction(Icons.Rounded.OpenInNew, "Open", onClick = onOpen))
        add(ContextAction(Icons.Rounded.ContentCopy, "Copy", onClick = onCopy))
        add(ContextAction(Icons.Rounded.ContentCut, "Cut", onClick = onCut))
        add(ContextAction(Icons.Rounded.DriveFileRenameOutline, "Rename", onClick = onRename))
        add(ContextAction(Icons.Rounded.Archive, "Compress (Zip)", onClick = onZip))
        if (onShare != null) add(ContextAction(Icons.Rounded.Share, "Share", onClick = onShare))
        if (onSetWallpaper != null) add(ContextAction(Icons.Rounded.Wallpaper, "Set as wallpaper", onClick = onSetWallpaper))
        if (onViewContents != null) add(ContextAction(Icons.Rounded.Visibility, "View contents", onClick = onViewContents))
        if (onExtract != null) add(ContextAction(Icons.Rounded.Unarchive, "Extract here", onClick = onExtract))
        add(ContextAction(Icons.Rounded.Checklist, "Select", onClick = onSelect))
        add(ContextAction(Icons.Rounded.Info, "Details", onClick = onDetails))
        add(ContextAction(Icons.Rounded.Delete, "Delete", destructive = true, onClick = onDelete))
    }
    ContextMenu(item, onDismiss, actions)
}

/**
 * Slimmer long-press menu for the home screen's Recents list. Recents are a flat set of files with
 * no browsing/paste context, so this offers only the actions that stand alone: open, share/wallpaper,
 * archive preview/extract, details, delete.
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
    val actions = buildList {
        add(ContextAction(Icons.Rounded.OpenInNew, "Open", onClick = onOpen))
        if (onShare != null) add(ContextAction(Icons.Rounded.Share, "Share", onClick = onShare))
        if (onSetWallpaper != null) add(ContextAction(Icons.Rounded.Wallpaper, "Set as wallpaper", onClick = onSetWallpaper))
        if (onViewContents != null) add(ContextAction(Icons.Rounded.Visibility, "View contents", onClick = onViewContents))
        if (onExtract != null) add(ContextAction(Icons.Rounded.Unarchive, "Extract here", onClick = onExtract))
        add(ContextAction(Icons.Rounded.Info, "Details", onClick = onDetails))
        add(ContextAction(Icons.Rounded.Delete, "Delete", destructive = true, onClick = onDelete))
    }
    ContextMenu(item, onDismiss, actions)
}

/**
 * The long-press menu is always a Material [ModalBottomSheet] (thumb-reachable, drag handle, one
 * consistent look in both orientations). Only the *action layout* adapts: a single column in portrait,
 * a 2-column grid in landscape — where the sheet is short, so a tall single column would need a lot of
 * scrolling. Both share [MenuHeader] and a trailing destructive Delete split off by a divider; there's
 * no "Close" button (tap the scrim / swipe down / back). Each action's callback clears the caller's menu
 * state, so it self-dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextMenu(item: FileItem, onDismiss: () -> Unit, actions: List<ContextAction>) {
    val twoColumn = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Play the sheet's slide-down exit before running the action (which clears the caller's state and
    // removes us from composition), so a tap animates out like a scrim/swipe dismiss does.
    fun dismissThen(onClick: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onClick() }
    }
    val normal = actions.filter { !it.destructive }
    val danger = actions.filter { it.destructive }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Scrollable so a long list (or the short landscape sheet) never clips.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            MenuHeader(item, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (twoColumn) {
                    normal.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pair.forEach { a -> Action(a, Modifier.weight(1f)) { dismissThen(a.onClick) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    normal.forEach { a -> Action(a, Modifier.fillMaxWidth()) { dismissThen(a.onClick) } }
                }
            }
            if (danger.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    danger.forEach { a -> Action(a, Modifier.fillMaxWidth()) { dismissThen(a.onClick) } }
                }
            }
        }
    }
}

/** Shared header: coloured type icon + file name + size · extension (or "Folder"). */
@Composable
private fun MenuHeader(item: FileItem, modifier: Modifier = Modifier) {
    val subtitle = when {
        item.isDirectory -> "Folder"
        item.extension.isNotEmpty() -> "${Formatting.bytes(item.size)} · ${item.extension.uppercase()}"
        else -> Formatting.bytes(item.size)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val specialRes = specialFolderIconRes(item)
        if (specialRes != null) {
            Icon(painterResource(specialRes), contentDescription = null, tint = colorFor(item), modifier = Modifier.size(28.dp))
        } else {
            Icon(iconFor(item), contentDescription = null, tint = colorFor(item), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.size(16.dp))
        Column {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Action(action: ContextAction, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(action.icon, contentDescription = null, tint = tint)
        Spacer(Modifier.size(16.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
