@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.android_explorer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
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
    // When set, long-pressing the icon tile enters multi-select (Gmail-style). Null (default) leaves
    // the icon inert so the whole-row long-press below is the only long-press affordance.
    onIconLongClick: (() -> Unit)? = null,
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
        SelectableIcon(onClick = onClick, onIconLongClick = onIconLongClick) {
            IconTile(item = item, selected = selected, size = 40.dp)
        }
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
    // When set (e.g. a device-wide category list), replaces the default size/date subtitle —
    // used to show the file's containing folder so a flat aggregation stays locatable.
    subtitleOverride: String? = null,
    // See FileListItem: long-press the icon to enter multi-select. Null leaves the icon inert.
    onIconLongClick: (() -> Unit)? = null,
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
        SelectableIcon(onClick = onClick, onIconLongClick = onIconLongClick) {
            if (item.isImage && !selected) {
                Thumbnail(item, size = 56.dp)
            } else {
                IconTile(item = item, selected = selected, size = 56.dp)
            }
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
                text = subtitleOverride ?: subtitle(item, folderSize),
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

/** Grid cell: a large square thumbnail (images) or colored icon tile, with the name beneath. */
@Composable
fun FileGridItem(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    // See FileListItem: long-press the icon square to enter multi-select. Null leaves it inert.
    onIconLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val accent = colorFor(item)
    val specialRes = specialFolderIconRes(item)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val rotation by animateFloatAsState(
            targetValue = if (selected) 180f else 0f,
            animationSpec = tween(durationMillis = 360),
            label = "gridFlip",
        )
        val density = LocalDensity.current.density
        val squareMod = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .let {
                if (onIconLongClick != null) {
                    it.clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = onClick, onLongClick = onIconLongClick)
                } else it
            }
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
        Box(squareMod) {
            if (rotation <= 90f) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (item.isImage) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    else accent.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when {
                            item.isImage -> AsyncImage(
                                model = ImageRequest.Builder(context).data(item.file).size(256).crossfade(true).build(),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                            )
                            specialRes != null -> Icon(
                                painter = painterResource(specialRes), contentDescription = null,
                                tint = accent, modifier = Modifier.size(46.dp),
                            )
                            else -> Icon(
                                iconFor(item), contentDescription = null, tint = accent, modifier = Modifier.size(46.dp),
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Wraps a row's leading icon so a long-press on *just the icon* can trigger a separate action
 * (entering multi-select, Gmail-style) while the rest of the row keeps its own click/long-press.
 * When [onIconLongClick] is null the icon is left inert — no nested clickable — so screens without
 * selection (recents/search/category/Drive) behave exactly as before.
 */
@Composable
private fun SelectableIcon(
    onClick: () -> Unit,
    onIconLongClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (onIconLongClick == null) {
        content()
    } else {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = onIconLongClick),
        ) { content() }
    }
}

@Composable
private fun IconTile(item: FileItem, selected: Boolean, size: androidx.compose.ui.unit.Dp) {
    val accent = colorFor(item)
    val specialRes = specialFolderIconRes(item)
    val glyphSize = Modifier.size(size * 0.55f)
    // 3D card-flip when selection toggles: front (type chip + glyph) rotates to the back (primary chip
    // + check). The back is counter-rotated 180° so its glyph reads the right way round.
    val rotation by animateFloatAsState(
        targetValue = if (selected) 180f else 0f,
        animationSpec = tween(durationMillis = 360),
        label = "iconFlip",
    )
    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            },
        contentAlignment = Alignment.Center,
    ) {
        if (rotation <= 90f) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                // A soft chip tinted with the type's accent colour.
                color = accent.copy(alpha = 0.16f),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (specialRes != null) {
                        Icon(painterResource(specialRes), contentDescription = null, tint = accent, modifier = glyphSize)
                    } else {
                        Icon(iconFor(item), contentDescription = null, tint = accent, modifier = glyphSize)
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.CheckCircle, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = glyphSize,
                    )
                }
            }
        }
    }
}
