package com.android_explorer.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android_explorer.archive.ArchiveType
import com.android_explorer.data.FileDetails
import com.android_explorer.data.FileItem
import com.android_explorer.data.FileRepository
import com.android_explorer.data.SortBy
import com.android_explorer.data.ViewMode
import com.android_explorer.service.ArchiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files staged for paste, and whether the paste should move (cut) or duplicate (copy). */
data class ClipboardData(val paths: List<String>, val cut: Boolean)

data class BrowserUiState(
    val dir: File,
    val items: List<FileItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val showHidden: Boolean = false,
    val view: ViewMode = ViewMode.LIST,
    val folderSizes: Map<String, Long> = emptyMap(),
    val clipboard: ClipboardData? = null,
    val loading: Boolean = true,
) {
    val inSelectionMode: Boolean get() = selected.isNotEmpty()
    val hasClipboard: Boolean get() = clipboard != null && clipboard.paths.isNotEmpty()
}

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FileRepository()
    private val root: File = repo.storageRoot

    private val _state = MutableStateFlow(BrowserUiState(dir = root))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _details = MutableStateFlow<FileDetails?>(null)
    val details: StateFlow<FileDetails?> = _details.asStateFlow()

    init {
        refresh()
    }

    private var folderSizeJob: Job? = null

    fun refresh() {
        val s = _state.value
        _state.value = s.copy(loading = true, folderSizes = emptyMap())
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                repo.list(s.dir, s.showHidden, s.sortBy, s.ascending)
            }
            _state.value = _state.value.copy(items = items, loading = false)
            computeFolderSizes(items)
        }
    }

    /** Computes directory sizes off the main thread, filling them in as each completes. */
    private fun computeFolderSizes(items: List<FileItem>) {
        folderSizeJob?.cancel()
        val dirs = items.filter { it.isDirectory }
        if (dirs.isEmpty()) return
        folderSizeJob = viewModelScope.launch {
            val sizes = HashMap(_state.value.folderSizes)
            for (dir in dirs) {
                val size = withContext(Dispatchers.IO) { repo.folderSize(dir.file) }
                sizes[dir.path] = size
                // Publish incrementally so the UI updates as sizes arrive.
                _state.value = _state.value.copy(folderSizes = HashMap(sizes))
            }
        }
    }

    fun open(item: FileItem) {
        if (item.isDirectory) navigateTo(item.file)
    }

    fun navigateTo(dir: File) {
        _state.value = _state.value.copy(dir = dir, selected = emptySet())
        refresh()
    }

    /** Returns false when already at the storage root (caller may then exit the screen). */
    fun navigateUp(): Boolean {
        val current = _state.value.dir
        if (current.absolutePath == root.absolutePath) return false
        val parent = current.parentFile ?: return false
        navigateTo(parent)
        return true
    }

    val canGoUp: Boolean get() = _state.value.dir.absolutePath != root.absolutePath

    fun toggleSelect(item: FileItem) {
        val sel = _state.value.selected.toMutableSet()
        if (!sel.add(item.path)) sel.remove(item.path)
        _state.value = _state.value.copy(selected = sel)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun selectedItems(): List<FileItem> {
        val sel = _state.value.selected
        return _state.value.items.filter { it.path in sel }
    }

    fun setSort(sortBy: SortBy) {
        val s = _state.value
        val ascending = if (s.sortBy == sortBy) !s.ascending else true
        _state.value = s.copy(sortBy = sortBy, ascending = ascending)
        refresh()
    }

    fun toggleHidden() {
        _state.value = _state.value.copy(showHidden = !_state.value.showHidden)
        refresh()
    }

    fun toggleView() {
        val next = if (_state.value.view == ViewMode.LIST) ViewMode.DETAILS else ViewMode.LIST
        _state.value = _state.value.copy(view = next)
    }

    fun deleteSelected() = delete(selectedItems())

    fun delete(items: List<FileItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(items) }
            clearSelection()
            refresh()
        }
    }

    fun rename(item: FileItem, newName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.rename(item, newName) }
            clearSelection()
            refresh()
        }
    }

    // ------------------------------------------------------------- clipboard

    fun copyToClipboard(items: List<FileItem>, cut: Boolean) {
        if (items.isEmpty()) return
        _state.value = _state.value.copy(
            clipboard = ClipboardData(items.map { it.path }, cut),
            selected = emptySet(),
        )
    }

    fun clearClipboard() {
        _state.value = _state.value.copy(clipboard = null)
    }

    fun paste() {
        val clip = _state.value.clipboard ?: return
        val destDir = _state.value.dir
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                clip.paths.map(::File).forEach { src ->
                    if (!src.exists() || repo.isInsideOrSame(src, destDir)) return@forEach
                    if (clip.cut) repo.moveInto(src, destDir) else repo.copyInto(src, destDir)
                }
            }
            // A cut is consumed; a copy stays available for repeated pastes.
            if (clip.cut) clearClipboard()
            refresh()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.createFolder(_state.value.dir, name) }
            refresh()
        }
    }

    fun createFile(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.createFile(_state.value.dir, name) }
            refresh()
        }
    }

    fun extract(item: FileItem) {
        val dest = repo.extractionTargetFor(item.file)
        ArchiveService.extract(getApplication(), item.path, dest.absolutePath)
    }

    /** Extract [item] into a user-chosen [destParent] (a folder named after the archive is created). */
    fun extractTo(item: FileItem, destParent: File) {
        val dest = repo.extractionTargetIn(destParent, item.file)
        ArchiveService.extract(getApplication(), item.path, dest.absolutePath)
    }

    fun subFolders(dir: File): List<File> = repo.subFolders(dir)

    val storageRoot: File get() = repo.storageRoot

    fun compress(items: List<FileItem>, fileName: String, type: ArchiveType) {
        if (items.isEmpty()) return
        val dest = File(_state.value.dir, fileName)
        ArchiveService.compress(getApplication(), items.map { it.path }, dest.absolutePath, type)
        clearSelection()
    }

    fun defaultArchiveNameFor(items: List<FileItem>): String =
        if (items.size == 1) items.first().file.nameWithoutExtension else _state.value.dir.name

    fun showDetails(item: FileItem) {
        viewModelScope.launch {
            _details.value = withContext(Dispatchers.IO) { repo.details(item) }
        }
    }

    fun dismissDetails() {
        _details.value = null
    }
}
