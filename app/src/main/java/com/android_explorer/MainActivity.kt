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
import com.android_explorer.data.FileItem
import com.android_explorer.data.MediaCategory
import com.android_explorer.ui.browser.BrowserScreen
import com.android_explorer.ui.drive.DriveBrowserScreen
import com.android_explorer.ui.category.CategoryScreen
import com.android_explorer.ui.components.ArchiveProgressDialog
import com.android_explorer.ui.editor.EditorScreen
import com.android_explorer.ui.home.HomeScreen
import com.android_explorer.ui.home.HomeViewModel
import com.android_explorer.ui.pdf.PdfScreen
import com.android_explorer.ui.search.SearchScreen
import com.android_explorer.ui.theme.AndroidExplorerTheme
import com.android_explorer.util.FileOpener
import com.android_explorer.util.Permissions
import com.android_explorer.util.PluginManager
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
    // Non-null while viewing a device-wide media category (Documents/Pictures/Music/Video).
    // Stored as the enum name so rememberSaveable can persist it across process death.
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    // True while the full-screen filename search is open (a top-level destination).
    var searching by rememberSaveable { mutableStateOf(false) }
    // Activity uses configChanges (no recreation on rotation), so remember survives rotation.
    var editorFile by remember { mutableStateOf<File?>(null) }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    // True while browsing Google Drive (a top-level destination, like the local browser).
    var driveBrowsing by rememberSaveable { mutableStateOf(false) }

    // Shared open resolver, honouring the Plugins settings: built-in editor for text, built-in
    // reader for PDFs (each only when its plugin is enabled), otherwise the system "open with".
    val openFile: (FileItem) -> Unit = { item ->
        val f = item.file
        if (f != null) {
            when {
                item.isEditableText && PluginManager.textEditorEnabled -> editorFile = f
                item.isPdf && PluginManager.pdfReaderEnabled -> pdfFile = f
                else -> FileOpener.open(context, f)
            }
        }
        // Drive items have no local file yet — download-to-cache then open is wired in a later step.
    }

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
        val viewingPdf = pdfFile
        val browsing = browsePath
        val category = categoryName?.let { runCatching { MediaCategory.valueOf(it) }.getOrNull() }
        when {
            editing != null -> EditorScreen(file = editing, onClose = { editorFile = null })
            viewingPdf != null -> PdfScreen(file = viewingPdf, onClose = { pdfFile = null })
            searching -> SearchScreen(
                onExit = { searching = false },
                onOpenFile = openFile,
                onOpenFolder = { browsePath = it.absolutePath; searching = false },
            )
            category != null -> CategoryScreen(
                category = category,
                onExit = { categoryName = null },
                onOpenFile = openFile,
            )
            browsing != null -> BrowserScreen(
                onExit = { browsePath = null },
                onOpenFile = openFile,
                onSearch = { searching = true },
                startDir = File(browsing),
            )
            driveBrowsing -> DriveBrowserScreen(
                onExit = { driveBrowsing = false },
                onOpenFile = openFile,
            )
            else -> {
                HomeScreen(
                    viewModel = homeViewModel,
                    onBrowse = { browsePath = rootPath },
                    onOpenFolder = { browsePath = it.absolutePath },
                    onOpenCategory = { categoryName = it.name },
                    onOpenFile = openFile,
                    onSearch = { searching = true },
                    onRequestAccess = { context.startActivity(Permissions.allFilesAccessIntent(context)) },
                    onOpenDrive = { driveBrowsing = true },
                )
            }
        }
        // Global overlay: shows extract/compress progress on top of any screen.
        ArchiveProgressDialog()
    }
}
