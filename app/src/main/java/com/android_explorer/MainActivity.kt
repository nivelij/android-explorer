package com.android_explorer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android_explorer.ui.browser.BrowserScreen
import com.android_explorer.ui.components.ArchiveProgressDialog
import com.android_explorer.ui.editor.EditorScreen
import com.android_explorer.ui.home.HomeScreen
import com.android_explorer.ui.home.HomeViewModel
import com.android_explorer.ui.theme.AndroidExplorerTheme
import com.android_explorer.util.FileOpener
import com.android_explorer.util.Permissions
import com.android_explorer.util.ThemeManager
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by ThemeManager.mode.collectAsStateWithLifecycle()
            AndroidExplorerTheme(themeMode = themeMode) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val homeViewModel: HomeViewModel = viewModel()
    // Non-null while browsing; holds the folder to open (storage root for "Browse files",
    // or a specific folder for a home-screen shortcut). rememberSaveable survives process death.
    var browsePath by rememberSaveable { mutableStateOf<String?>(null) }
    val rootPath = remember { android.os.Environment.getExternalStorageDirectory().absolutePath }
    // Activity uses configChanges (no recreation on rotation), so remember survives rotation.
    var editorFile by remember { mutableStateOf<File?>(null) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored; jobs still run, just without a visible notification */ }

    LaunchedEffect(Unit) {
        if (Permissions.needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Reload storage/recents on resume — also catches returning from the All-files settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { homeViewModel.refresh() }

    Box(Modifier.fillMaxSize()) {
        val editing = editorFile
        val browsing = browsePath
        when {
            editing != null -> EditorScreen(file = editing, onClose = { editorFile = null })
            browsing != null -> BrowserScreen(
                onExit = { browsePath = null },
                onEditFile = { editorFile = it },
                startDir = File(browsing),
            )
            else -> {
                HomeScreen(
                    viewModel = homeViewModel,
                    onBrowse = { browsePath = rootPath },
                    onOpenFolder = { browsePath = it.absolutePath },
                    onOpenFile = {
                        if (it.isEditableText) editorFile = it.file else FileOpener.open(context, it.file)
                    },
                    onRequestAccess = { context.startActivity(Permissions.allFilesAccessIntent(context)) },
                )
            }
        }
        // Global overlay: shows extract/compress progress on top of any screen.
        ArchiveProgressDialog()
    }
}
