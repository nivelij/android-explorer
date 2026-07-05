package com.android_explorer.ui.browser

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
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android_explorer.archive.ArchiveProgress
import com.android_explorer.archive.ArchiveProgressBus
import com.android_explorer.data.FileItem
import com.android_explorer.data.SortBy
import com.android_explorer.data.ViewMode
import com.android_explorer.ui.components.ArchiveActionDialog
import com.android_explorer.ui.components.CompressDialog
import com.android_explorer.ui.components.ConfirmDialog
import com.android_explorer.ui.components.FileContextSheet
import com.android_explorer.ui.components.FileDetailsDialog
import com.android_explorer.ui.components.FileDetailsItem
import com.android_explorer.ui.components.FileListItem
import com.android_explorer.ui.components.FolderPickerDialog
import com.android_explorer.ui.components.TextPromptDialog
import com.android_explorer.ui.components.ThemeMenuItems
import com.android_explorer.util.FileOpener

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onExit: () -> Unit,
    onEditFile: (java.io.File) -> Unit,
    viewModel: BrowserViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by ArchiveProgressBus.progress.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showNewFolder by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var archiveAction by remember { mutableStateOf<FileItem?>(null) }
    var folderPickerFor by remember { mutableStateOf<FileItem?>(null) }
    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    var renameItem by remember { mutableStateOf<FileItem?>(null) }
    var compressItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var deleteItems by remember { mutableStateOf<List<FileItem>>(emptyList()) }

    // Refresh the listing whenever an archive job finishes successfully.
    androidx.compose.runtime.LaunchedEffect(progress?.state) {
        if (progress?.state == ArchiveProgress.State.SUCCESS) viewModel.refresh()
    }

    BackHandler {
        when {
            state.inSelectionMode -> viewModel.clearSelection()
            !viewModel.navigateUp() -> onExit()
        }
    }

    Scaffold(
        topBar = {
            if (state.inSelectionMode) {
                SelectionBar(
                    count = state.selected.size,
                    canExtract = viewModel.selectedItems().singleOrNull()?.isArchive == true,
                    onClose = viewModel::clearSelection,
                    onExtract = { viewModel.selectedItems().firstOrNull()?.let { viewModel.extract(it) }; viewModel.clearSelection() },
                    onCopy = { viewModel.copyToClipboard(viewModel.selectedItems(), cut = false) },
                    onCut = { viewModel.copyToClipboard(viewModel.selectedItems(), cut = true) },
                    onCompress = { compressItems = viewModel.selectedItems() },
                    onDelete = { deleteItems = viewModel.selectedItems() },
                )
            } else {
                BrowserBar(
                    title = if (viewModel.canGoUp) state.dir.name else "Internal storage",
                    details = state.view == ViewMode.DETAILS,
                    showPaste = state.hasClipboard,
                    onPaste = viewModel::paste,
                    onUp = { if (!viewModel.navigateUp()) onExit() },
                    onToggleView = viewModel::toggleView,
                    onSort = viewModel::setSort,
                    onNewFolder = { showNewFolder = true },
                    onNewFile = { showNewFile = true },
                    onToggleHidden = viewModel::toggleHidden,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.items.isEmpty() -> Text(
                    "Empty folder",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> FileListing(
                    state = state,
                    onClick = { item -> onItemClick(item, state, viewModel, context, onEditFile) { archiveAction = it } },
                    onLongClick = { item ->
                        if (state.inSelectionMode) viewModel.toggleSelect(item) else contextItem = item
                    },
                )
            }
        }
    }

    if (compressItems.isNotEmpty()) {
        CompressDialog(
            defaultName = viewModel.defaultArchiveNameFor(compressItems),
            onDismiss = { compressItems = emptyList() },
            onConfirm = { name, type ->
                viewModel.compress(compressItems, name, type)
                compressItems = emptyList()
            },
        )
    }
    if (showNewFolder) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            onDismiss = { showNewFolder = false },
            onConfirm = { viewModel.createFolder(it); showNewFolder = false },
        )
    }
    if (showNewFile) {
        TextPromptDialog(
            title = "New file",
            label = "File name",
            onDismiss = { showNewFile = false },
            onConfirm = { viewModel.createFile(it); showNewFile = false },
        )
    }
    if (deleteItems.isNotEmpty()) {
        ConfirmDialog(
            title = "Delete ${deleteItems.size} item(s)?",
            message = "This cannot be undone.",
            confirmLabel = "Delete",
            onDismiss = { deleteItems = emptyList() },
            onConfirm = { viewModel.delete(deleteItems); deleteItems = emptyList() },
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
    contextItem?.let { item ->
        FileContextSheet(
            item = item,
            onDismiss = { contextItem = null },
            onOpen = {
                when {
                    item.isDirectory -> viewModel.open(item)
                    item.isEditableText -> onEditFile(item.file)
                    else -> FileOpener.open(context, item.file)
                }
                contextItem = null
            },
            onCopy = { viewModel.copyToClipboard(listOf(item), cut = false); contextItem = null },
            onCut = { viewModel.copyToClipboard(listOf(item), cut = true); contextItem = null },
            onRename = { renameItem = item; contextItem = null },
            onZip = { compressItems = listOf(item); contextItem = null },
            onExtract = if (item.isArchive) { { viewModel.extract(item); contextItem = null } } else null,
            onDelete = { deleteItems = listOf(item); contextItem = null },
            onSelect = { viewModel.toggleSelect(item); contextItem = null },
            onDetails = { viewModel.showDetails(item); contextItem = null },
        )
    }
    details?.let { FileDetailsDialog(details = it, onDismiss = viewModel::dismissDetails) }
    archiveAction?.let { item ->
        ArchiveActionDialog(
            name = item.name,
            onDismiss = { archiveAction = null },
            onExtractHere = { viewModel.extract(item); archiveAction = null },
            onExtractTo = { folderPickerFor = item; archiveAction = null },
            onOpen = { FileOpener.open(context, item.file); archiveAction = null },
        )
    }
    folderPickerFor?.let { item ->
        FolderPickerDialog(
            startDir = state.dir,
            rootDir = viewModel.storageRoot,
            listFolders = viewModel::subFolders,
            onDismiss = { folderPickerFor = null },
            onPick = { dir -> viewModel.extractTo(item, dir); folderPickerFor = null },
        )
    }
}

private fun onItemClick(
    item: FileItem,
    state: BrowserUiState,
    viewModel: BrowserViewModel,
    context: android.content.Context,
    onEditFile: (java.io.File) -> Unit,
    onArchive: (FileItem) -> Unit,
) {
    when {
        state.inSelectionMode -> viewModel.toggleSelect(item)
        item.isDirectory -> viewModel.open(item)
        item.isArchive -> onArchive(item)
        item.isEditableText -> onEditFile(item.file)
        else -> FileOpener.open(context, item.file)
    }
}

@Composable
private fun FileListing(
    state: BrowserUiState,
    onClick: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(state.items, key = { it.path }) { item ->
            val selected = item.path in state.selected
            val folderSize = if (item.isDirectory) state.folderSizes[item.path] else null
            if (state.view == ViewMode.DETAILS) {
                FileDetailsItem(
                    item = item,
                    selected = selected,
                    folderSize = folderSize,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            } else {
                FileListItem(
                    item = item,
                    selected = selected,
                    folderSize = folderSize,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserBar(
    title: String,
    details: Boolean,
    showPaste: Boolean,
    onPaste: () -> Unit,
    onUp: () -> Unit,
    onToggleView: () -> Unit,
    onSort: (SortBy) -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onUp) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up")
            }
        },
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            if (showPaste) {
                IconButton(onClick = onPaste) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                }
            }
            IconButton(onClick = onToggleView) {
                Icon(
                    if (details) Icons.Rounded.ViewList else Icons.Rounded.ViewAgenda,
                    contentDescription = if (details) "List view" else "Details view",
                )
            }
            IconButton(onClick = { sortMenu = true }) {
                Icon(Icons.Rounded.Sort, contentDescription = "Sort")
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SortBy.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text("By ${s.name.lowercase().replaceFirstChar { it.uppercase() }}") },
                        onClick = { onSort(s); sortMenu = false },
                    )
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("New folder") },
                    leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    onClick = { onNewFolder(); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("New file") },
                    leadingIcon = { Icon(Icons.Rounded.NoteAdd, null) },
                    onClick = { onNewFile(); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Toggle hidden files") },
                    leadingIcon = { Icon(Icons.Rounded.Visibility, null) },
                    onClick = { onToggleHidden(); menu = false },
                )
                HorizontalDivider()
                ThemeMenuItems(onChosen = { menu = false })
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    canExtract: Boolean,
    onClose: () -> Unit,
    onExtract: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onCompress: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
        },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onCut) { Icon(Icons.Rounded.ContentCut, contentDescription = "Cut") }
            if (canExtract) {
                IconButton(onClick = onExtract) { Icon(Icons.Rounded.Unarchive, contentDescription = "Extract") }
            }
            IconButton(onClick = onCompress) {
                Icon(Icons.Rounded.Archive, contentDescription = "Compress")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
        },
    )
}
