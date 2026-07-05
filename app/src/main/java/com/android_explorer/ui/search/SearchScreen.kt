package com.android_explorer.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android_explorer.data.FileItem
import com.android_explorer.ui.components.ArchiveContentsDialog
import com.android_explorer.ui.components.ConfirmDialog
import com.android_explorer.ui.components.FileDetailsDialog
import com.android_explorer.ui.components.FileListItem
import com.android_explorer.ui.components.RecentsContextSheet
import com.android_explorer.util.FileOpener
import com.android_explorer.util.Wallpaper

/**
 * Full-screen filename search across all storage. Type a query, press enter, and matching files or
 * folders (by name, any extension) are listed. Tapping opens files via the shared resolver, folders
 * in the browser, and archives in the contents preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onExit: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onOpenFolder: (java.io.File) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    var previewItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItem by remember { mutableStateOf<FileItem?>(null) }

    // Auto-focus the field and raise the keyboard on entry.
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BackHandler { onExit() }

    val onOpen: (FileItem) -> Unit = { item ->
        when {
            item.isDirectory -> onOpenFolder(item.file)
            item.isArchive -> previewItem = item
            else -> onOpenFile(item)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        placeholder = { Text("Search files") },
                        singleLine = true,
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            viewModel.search()
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !state.hasSearched -> Hint("Search files by name across all storage.")
                state.results.isEmpty() -> Hint("No files match “${state.query}”.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.results, key = { it.path }) { item ->
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

    contextItem?.let { item ->
        RecentsContextSheet(
            item = item,
            onDismiss = { contextItem = null },
            onOpen = { onOpen(item); contextItem = null },
            onViewContents = if (item.isArchive) { { previewItem = item; contextItem = null } } else null,
            onExtract = if (item.isArchive) { { viewModel.extract(item); contextItem = null } } else null,
            onShare = if (!item.isDirectory) { { FileOpener.share(context, item.file); contextItem = null } } else null,
            onSetWallpaper = if (item.isImage) { { Wallpaper.setAsWallpaper(context, item.file); contextItem = null } } else null,
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

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
