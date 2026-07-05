@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.android_explorer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.android_explorer.data.FileItem
import com.android_explorer.util.Formatting

private fun subtitle(item: FileItem, folderSize: Long?): String {
    val time = Formatting.relativeTime(item.lastModified)
    return when {
        item.isDirectory && folderSize != null -> "$time  •  ${Formatting.bytes(folderSize)}"
        item.isDirectory -> time
        else -> "${Formatting.bytes(item.size)}  •  $time"
    }
}

@Composable
fun FileListItem(
    item: FileItem,
    selected: Boolean,
    folderSize: Long?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(item = item, selected = selected, size = 40.dp)
        Spacer(Modifier.size(16.dp))
        // Name takes the remaining width; metadata is pinned to the far right (Explorer-style).
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = sizeText(item, folderSize),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = Formatting.relativeTime(item.lastModified),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun sizeText(item: FileItem, folderSize: Long?): String = when {
    item.isDirectory -> folderSize?.let { Formatting.bytes(it) } ?: "—"
    else -> Formatting.bytes(item.size)
}

/** Richer row: bigger leading visual with real image thumbnails, plus type + size/date. */
@Composable
fun FileDetailsItem(
    item: FileItem,
    selected: Boolean,
    folderSize: Long?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isImage && !selected) {
            Thumbnail(item, size = 56.dp)
        } else {
            IconTile(item = item, selected = selected, size = 56.dp)
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = subtitle(item, folderSize),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = typeLabel(item),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun typeLabel(item: FileItem): String = when {
    item.isDirectory -> "Folder"
    item.isArchive -> "Archive"
    item.extension.isNotEmpty() -> item.extension.uppercase() + " file"
    else -> "File"
}

@Composable
private fun Thumbnail(item: FileItem, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(item.file)
            .size(256)
            .crossfade(true)
            .build(),
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun IconTile(item: FileItem, selected: Boolean, size: androidx.compose.ui.unit.Dp) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) Icons.Rounded.CheckCircle else iconFor(item),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}
