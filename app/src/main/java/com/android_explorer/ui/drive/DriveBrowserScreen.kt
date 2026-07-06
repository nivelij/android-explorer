package com.android_explorer.ui.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android_explorer.data.FileItem
import com.android_explorer.ui.components.ConfirmDialog
import com.android_explorer.ui.components.DriveContextSheet
import com.android_explorer.ui.components.FileListItem
import com.android_explorer.ui.components.ProgressDialog
import com.android_explorer.ui.components.TextPromptDialog
import kotlinx.coroutines.launch

/**
 * Read/write Google Drive browser. Reuses [FileListItem]: folders navigate in-place; files download to
 * cache then hand off to [onOpenFile]. Long-press → [DriveContextSheet] (copy/cut/rename/delete); the
 * top bar has New-folder and (when the shared clipboard is non-empty) Paste, which uploads/moves/copies
 * into the current folder via the shared transfer engine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveBrowserScreen(
    onExit: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    viewModel: DriveBrowserViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val clip by viewModel.hasClipboard.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf<String?>(null) }
    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItem by remember { mutableStateOf<FileItem?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start() }
    BackHandler { if (!viewModel.goUp()) onExit() }

    // Folder → navigate; file → download to cache then open via the shared resolver.
    fun activate(item: FileItem) {
        if (item.isDirectory) {
            viewModel.openFolder(item)
        } else {
            downloading = item.name
            scope.launch {
                runCatching { viewModel.localFileFor(item) }.onSuccess { onOpenFile(FileItem.from(it)) }
                downloading = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.goUp()) onExit() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (clip != null) {
                        IconButton(onClick = { viewModel.paste() }) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste here")
                        }
                    }
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = "New folder")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null -> Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                state.items.isEmpty() -> Text(
                    "This folder is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    items(state.items, key = { it.path }) { item ->
                        FileListItem(
                            item = item,
                            selected = false,
                            folderSize = null,
                            onClick = { activate(item) },
                            onLongClick = { contextItem = item },
                        )
                    }
                }
            }

        }
    }

    val overlay = busy ?: downloading?.let { "Downloading $it…" }
    if (overlay != null) ProgressDialog(overlay)

    contextItem?.let { item ->
        DriveContextSheet(
            item = item,
            onDismiss = { contextItem = null },
            onOpen = { activate(item); contextItem = null },
            onCopy = { viewModel.copyToClipboard(item, cut = false); contextItem = null },
            onCut = { viewModel.copyToClipboard(item, cut = true); contextItem = null },
            onRename = { renameItem = item; contextItem = null },
            onDelete = { deleteItem = item; contextItem = null },
        )
    }
    if (showNewFolder) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            onDismiss = { showNewFolder = false },
            onConfirm = { viewModel.newFolder(it); showNewFolder = false },
        )
    }
    renameItem?.let { item ->
        TextPromptDialog(
            title = "Rename",
            label = "Name",
            initial = item.name,
            onDismiss = { renameItem = null },
            onConfirm = { viewModel.rename(item, it); renameItem = null },
        )
    }
    deleteItem?.let { item ->
        ConfirmDialog(
            title = "Delete ${item.name}?",
            message = "It will be moved to your Google Drive trash.",
            confirmLabel = "Delete",
            onDismiss = { deleteItem = null },
            onConfirm = { viewModel.delete(item); deleteItem = null },
        )
    }
}
