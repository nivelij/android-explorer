package com.android_explorer.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_explorer.R
import com.android_explorer.data.FileItem
import com.android_explorer.data.MediaCategory
import com.android_explorer.data.VolumeStat
import com.android_explorer.ui.components.ArchiveContentsDialog
import com.android_explorer.ui.components.ConfirmDialog
import com.android_explorer.ui.components.FileDetailsDialog
import com.android_explorer.ui.components.FileListItem
import com.android_explorer.ui.components.PluginsDialog
import com.android_explorer.ui.components.RecentsContextSheet
import com.android_explorer.ui.components.StorageMeter
import com.android_explorer.util.FileOpener
import com.android_explorer.util.Wallpaper
import com.android_explorer.ui.components.ThemeOverflowMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onBrowse: () -> Unit,
    onOpenFolder: (java.io.File) -> Unit,
    onOpenCategory: (MediaCategory) -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onSearch: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var contextItem by remember { mutableStateOf<FileItem?>(null) }
    var previewItem by remember { mutableStateOf<FileItem?>(null) }
    var deleteItem by remember { mutableStateOf<FileItem?>(null) }
    var showPlugins by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Light,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { showPlugins = true }) {
                        Icon(Icons.Rounded.Extension, contentDescription = "Plugins")
                    }
                    ThemeOverflowMenu()
                },
            )
        },
    ) { padding ->
        if (!state.hasAccess) {
            AccessPrompt(Modifier.padding(padding), onRequestAccess)
            return@Scaffold
        }

        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            // Tap: archives open the contents preview (they have no default "open" app);
            // everything else defers to the host (editor for text, otherwise "open with").
            val onOpenRecent: (FileItem) -> Unit = { if (it.isArchive) previewItem = it else onOpenFile(it) }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    StoragePane(state.volumes, onBrowse, onOpenFolder, onOpenCategory, Modifier.weight(0.42f).fillMaxSize())
                    Spacer(Modifier.size(20.dp))
                    RecentsPane(state.recents, onOpenRecent, { contextItem = it }, Modifier.weight(0.58f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    StoragePane(state.volumes, onBrowse, onOpenFolder, onOpenCategory, Modifier.fillMaxWidth())
                    Spacer(Modifier.size(20.dp))
                    RecentsPane(state.recents, onOpenRecent, { contextItem = it }, Modifier.weight(1f).fillMaxWidth())
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
    if (showPlugins) PluginsDialog(onDismiss = { showPlugins = false })
}

@Composable
private fun StoragePane(
    volumes: List<VolumeStat>,
    onBrowse: () -> Unit,
    onOpenFolder: (java.io.File) -> Unit,
    onOpenCategory: (MediaCategory) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SectionHeader("Storage")
        Spacer(Modifier.size(12.dp))
        Card(
            // Solid container + a hairline outline so the panel stays visible on every theme —
            // notably OLED, where the old translucent surfaceVariant tint collapsed into the
            // pure-black background and the card disappeared entirely.
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (volumes.isEmpty()) {
                    Text("No volumes found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    volumes.forEach { StorageMeter(it) }
                }
            }
        }
        Spacer(Modifier.size(16.dp))
        Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Browse files")
        }
        ShortcutsRow(onOpenFolder, onOpenCategory)
    }
}

/**
 * Quick-access chips. "Download" opens the folder for hierarchical browsing (it keeps its
 * down-arrow icon); the media chips open a device-wide category list that aggregates every
 * matching file regardless of folder.
 */
@Composable
private fun ShortcutsRow(
    onOpenFolder: (java.io.File) -> Unit,
    onOpenCategory: (MediaCategory) -> Unit,
) {
    val download = remember {
        java.io.File(android.os.Environment.getExternalStorageDirectory(), "Download")
    }
    Spacer(Modifier.size(20.dp))
    SectionHeader("Shortcuts")
    Spacer(Modifier.size(10.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (download.isDirectory) {
            ShortcutChip("Download", Color(0xFFFFCA28), iconRes = R.drawable.ic_folder_download) {
                onOpenFolder(download)
            }
        }
        ShortcutChip("Documents", Color(0xFF42A5F5), icon = Icons.Rounded.Description) {
            onOpenCategory(MediaCategory.DOCUMENTS)
        }
        ShortcutChip("Pictures", Color(0xFF66BB6A), icon = Icons.Rounded.Image) {
            onOpenCategory(MediaCategory.IMAGES)
        }
        ShortcutChip("Music", Color(0xFFEC407A), icon = Icons.Rounded.MusicNote) {
            onOpenCategory(MediaCategory.AUDIO)
        }
        ShortcutChip("Video", Color(0xFFAB47BC), icon = Icons.Rounded.Movie) {
            onOpenCategory(MediaCategory.VIDEO)
        }
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconRes: Int? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f),
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(icon!!, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RecentsPane(
    recents: List<FileItem>,
    onOpen: (FileItem) -> Unit,
    onLongClick: (FileItem) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        SectionHeader("Recent files")
        Spacer(Modifier.size(8.dp))
        if (recents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recent files", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(recents, key = { it.path }) { item ->
                    FileListItem(
                        item = item,
                        selected = false,
                        folderSize = null,
                        onClick = { onOpen(item) },
                        onLongClick = { onLongClick(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AccessPrompt(modifier: Modifier, onRequestAccess: () -> Unit) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.size(16.dp))
            Text("All-files access needed", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(8.dp))
            Text(
                "android_explorer needs permission to read and manage files on your device so it can browse folders and extract archives.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
            Button(onClick = onRequestAccess) { Text("Grant access") }
        }
    }
}
