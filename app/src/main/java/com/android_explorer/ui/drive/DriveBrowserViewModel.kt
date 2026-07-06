package com.android_explorer.ui.drive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android_explorer.data.FileItem
import com.android_explorer.data.NodeRef
import com.android_explorer.data.TransferClipboard
import com.android_explorer.data.TransferManager
import com.android_explorer.data.drive.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DriveUiState(
    val title: String = "My Drive",
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Read-only Google Drive browser state. Navigation is a stack of folder frames (id + display name);
 * the root frame is "root" (My Drive). Listings come from [DriveRepository] as [FileItem]s so the
 * same row UI renders them; opening a file downloads it to cache first (see [localFileFor]).
 */
class DriveBrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DriveRepository()

    private data class Frame(val id: String, val name: String)
    private val stack = ArrayDeque<Frame>()

    private val _state = MutableStateFlow(DriveUiState())
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    /** Set while a write/transfer runs, to show a blocking overlay (with a label). */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    val canGoUp: Boolean get() = stack.size > 1
    val currentFolderId: String get() = stack.lastOrNull()?.id ?: "root"
    /** True while the shared clipboard holds something to paste here. */
    val hasClipboard get() = TransferClipboard.clip

    // ---- writes ----

    fun copyToClipboard(item: FileItem, cut: Boolean) = TransferClipboard.set(listOf(item), cut)

    fun rename(item: FileItem, newName: String) = run("Renaming…") { repo.rename(getApplication(), item, newName) }

    /** Delete = move to Drive trash (recoverable). */
    fun delete(item: FileItem) = run("Deleting…") { repo.trash(getApplication(), item) }

    fun newFolder(name: String) = run("Creating folder…") { repo.createFolder(getApplication(), name, currentFolderId) }

    fun paste() {
        val clip = TransferClipboard.current ?: return
        val label = if (clip.cut) "Moving…" else if (clip.items.any { it.isRemote }) "Copying…" else "Uploading…"
        run(label) {
            TransferManager.paste(getApplication(), clip, NodeRef.Drive(currentFolderId, parentId = null, mimeType = ""))
            if (clip.cut) TransferClipboard.clear()
        }
    }

    /** Run a write off the main thread behind the busy overlay, then reload the current folder. */
    private fun run(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = label
            runCatching { block() }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "Operation failed") }
            _busy.value = null
            load()
        }
    }

    /** Enter at My Drive root (idempotent — safe to call from a LaunchedEffect on every entry). */
    fun start() {
        if (stack.isEmpty()) {
            stack.addLast(Frame("root", "My Drive"))
            load()
        }
    }

    fun openFolder(item: FileItem) {
        val ref = item.location as? NodeRef.Drive ?: return
        stack.addLast(Frame(ref.id, item.name))
        load()
    }

    /** Returns false when already at the root (the caller may then exit the screen). */
    fun goUp(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        load()
        return true
    }

    fun retry() = load()

    private fun load() {
        val frame = stack.lastOrNull() ?: return
        _state.value = DriveUiState(title = frame.name, loading = true)
        viewModelScope.launch {
            val result = runCatching { repo.list(getApplication(), frame.id) }
            _state.value = result.fold(
                onSuccess = { DriveUiState(title = frame.name, items = it, loading = false) },
                onFailure = {
                    DriveUiState(title = frame.name, loading = false, error = it.message ?: "Couldn't load Drive")
                },
            )
        }
    }

    /** Download [item] to the cache and return the local file (for the editor / PDF reader / "open with"). */
    suspend fun localFileFor(item: FileItem): File = repo.downloadToCache(getApplication(), item)
}
