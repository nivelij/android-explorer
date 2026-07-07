package com.android_explorer.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_explorer.data.FileItem
import com.android_explorer.ui.components.ArchiveContentsDialog
import com.android_explorer.ui.components.ConfirmDialog
import com.android_explorer.ui.components.FileDetailsDialog
import com.android_explorer.ui.components.FileListItem
import com.android_explorer.ui.components.RecentsContextSheet
import com.android_explorer.util.FileOpener
import com.android_explorer.util.Wallpaper
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Full recent-files view, reached from the Home "Recent" strip's "See all". Reuses [FileListItem] and
 * the flat-list [RecentsContextSheet]; entries are bucketed by modified date (Today / Yesterday / This
 * week / This month / Older) with plain header rows inside a single [LazyColumn].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    viewModel: HomeViewModel,
    onExit: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    var previewItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItem by remember { mutableStateOf<FileItem?>(null) }

    BackHandler { onExit() }

    // Tap: archives open the contents preview; everything else defers to the shared open resolver.
    val onOpen: (FileItem) -> Unit = { if (it.isArchive) previewItem = it else onOpenFile(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Recent files") },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.recents.isEmpty()) {
                Text(
                    "No recent files",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val groups = remember(state.recents) { groupByDate(state.recents) }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    groups.forEach { (label, items) ->
                        item(key = "header:$label") {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        }
                        items(items, key = { it.path }) { item ->
                            FileListItem(
                                item = item,
                                selected = false,
                                folderSize = null,
                                onClick = { onOpen(item) },
                                onLongClick = { contextItem = item },
                            )
                        }
                    }
                }
            }
        }
    }

    contextItem?.let { item ->
        RecentsContextSheet(
            item = item,
            onDismiss = { contextItem = null },
            onOpen = {
                if (item.isArchive) previewItem = item else onOpenFile(item)
                contextItem = null
            },
            onViewContents = if (item.isArchive) { { previewItem = item; contextItem = null } } else null,
            onExtract = if (item.isArchive) { { viewModel.extract(item); contextItem = null } } else null,
            onShare = if (!item.isDirectory) { { item.file?.let { FileOpener.share(context, it) }; contextItem = null } } else null,
            onSetWallpaper = if (item.isImage) { { item.file?.let { Wallpaper.setAsWallpaper(context, it) }; contextItem = null } } else null,
            onDetails = { viewModel.showDetails(item); contextItem = null },
            onDelete = { deleteItem = item; contextItem = null },
        )
    }
    previewItem?.let { item ->
        ArchiveContentsDialog(
            item = item,
            onDismiss = { previewItem = null },
            onExtract = { viewModel.extract(item); previewItem = null },
        )
    }
    details?.let { FileDetailsDialog(details = it, onDismiss = viewModel::dismissDetails) }
    deleteItem?.let { item ->
        ConfirmDialog(
            title = "Delete ${item.name}?",
            message = "This cannot be undone.",
            confirmLabel = "Delete",
            onDismiss = { deleteItem = null },
            onConfirm = { viewModel.delete(item); deleteItem = null },
        )
    }
}

/**
 * Bucket recents by modified date into ordered, non-empty groups: Today, Yesterday, This week,
 * This month, Older. Input is expected newest-first; each bucket preserves that order.
 */
private fun groupByDate(items: List<FileItem>): List<Pair<String, List<FileItem>>> {
    val zone = ZoneId.systemDefault()
    val today = Instant.now().atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    val weekField = WeekFields.of(Locale.getDefault())
    val thisWeek = today.get(weekField.weekOfWeekBasedYear())
    val thisWeekYear = today.get(weekField.weekBasedYear())

    val buckets = LinkedHashMap<String, MutableList<FileItem>>().apply {
        put("Today", mutableListOf())
        put("Yesterday", mutableListOf())
        put("This week", mutableListOf())
        put("This month", mutableListOf())
        put("Older", mutableListOf())
    }
    for (item in items) {
        val date = Instant.ofEpochMilli(item.lastModified).atZone(zone).toLocalDate()
        val label = when {
            date == today -> "Today"
            date == yesterday -> "Yesterday"
            date.get(weekField.weekOfWeekBasedYear()) == thisWeek &&
                date.get(weekField.weekBasedYear()) == thisWeekYear -> "This week"
            date.year == today.year && date.month == today.month -> "This month"
            else -> "Older"
        }
        buckets.getValue(label).add(item)
    }
    return buckets.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value }
}
