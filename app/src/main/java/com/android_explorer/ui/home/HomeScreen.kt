package com.android_explorer.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android_explorer.R
import com.android_explorer.data.FileItem
import com.android_explorer.data.VolumeStat
import com.android_explorer.ui.components.FileListItem
import com.android_explorer.ui.components.StorageMeter
import com.android_explorer.ui.components.ThemeOverflowMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onBrowse: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onRequestAccess: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Light,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = { ThemeOverflowMenu() },
            )
        },
    ) { padding ->
        if (!state.hasAccess) {
            AccessPrompt(Modifier.padding(padding), onRequestAccess)
            return@Scaffold
        }

        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    StoragePane(state.volumes, onBrowse, Modifier.weight(0.42f).fillMaxSize())
                    Spacer(Modifier.size(20.dp))
                    RecentsPane(state.recents, onOpenFile, Modifier.weight(0.58f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    StoragePane(state.volumes, onBrowse, Modifier.fillMaxWidth())
                    Spacer(Modifier.size(20.dp))
                    RecentsPane(state.recents, onOpenFile, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StoragePane(volumes: List<VolumeStat>, onBrowse: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        SectionHeader("Storage")
        Spacer(Modifier.size(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(0.dp),
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
    }
}

@Composable
private fun RecentsPane(recents: List<FileItem>, onOpenFile: (FileItem) -> Unit, modifier: Modifier) {
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
                        onClick = { onOpenFile(item) },
                        onLongClick = {},
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
