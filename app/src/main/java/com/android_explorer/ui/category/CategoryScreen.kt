package com.android_explorer.ui.category

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android_explorer.archive.ArchiveProgress
import com.android_explorer.archive.ArchiveProgressBus
import com.android_explorer.data.FileItem
import com.android_explorer.data.MediaCategory
import com.android_explorer.ui.components.ArchiveContentsDialog
import com.android_explorer.ui.components.ConfirmDialog
import com.android_explorer.ui.components.FileDetailsDialog
import com.android_explorer.ui.components.FileDetailsItem
import com.android_explorer.ui.components.RecentsContextSheet

/**
 * A device-wide, flat list of every file in one [MediaCategory] (e.g. all images). Reuses the
 * details row (thumbnails for images/video) and the Recents context menu — categories are a flat
 * set of files with no browse/paste context, exactly like Recents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    category: MediaCategory,
    onExit: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    viewModel: CategoryViewModel = viewModel(),
) {
    // Load on entry / when the category changes; the ViewModel is Activity-scoped and reused.
    LaunchedEffect(category) { viewModel.load(category) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val progress by ArchiveProgressBus.progress.collectAsStateWithLifecycle()
    val root = remember { android.os.Environment.getExternalStorageDirectory().absolutePath }

    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    var previewItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItem by remember { mutableStateOf<FileItem?>(null) }

    // Re-query after a successful extract (a new folder of files may now exist).
    LaunchedEffect(progress?.state) {
        if (progress?.state == ArchiveProgress.State.SUCCESS) viewModel.refresh()
    }
    BackHandler { onExit() }

    // Archives (rare in Documents) preview their contents; everything else defers to the host.
    val onOpen: (FileItem) -> Unit = { if (it.isArchive) previewItem = it else onOpenFile(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(category.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.items.isEmpty() -> Text(
                    "No ${category.title.lowercase()} found",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.items, key = { it.path }) { item ->
                        FileDetailsItem(
                            item = item,
                            selected = false,
                            folderSize = null,
                            onClick = { onOpen(item) },
                            onLongClick = { contextItem = item },
                            subtitleOverride = parentLabel(item, root),
                        )
                    }
                }
            }
        }
    }

    contextItem?.let { item ->
        RecentsContextSheet(
            item = item,
            onDismiss = { contextItem = null },
            onOpen = { onOpen(item); contextItem = null },
            onViewContents = if (item.isArchive) { { previewItem = item; contextItem = null } } else null,
            onExtract = if (item.isArchive) { { viewModel.extract(item); contextItem = null } } else null,
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

/** The file's containing folder, shown relative to the storage root (e.g. "DCIM/Camera"). */
private fun parentLabel(item: FileItem, root: String): String {
    val parent = item.file.parent ?: return ""
    return when {
        parent == root -> "Internal storage"
        parent.startsWith(root) -> parent.removePrefix(root).trim('/')
        else -> parent
    }
}
