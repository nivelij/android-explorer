package com.android_explorer.ui.analyzer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android_explorer.data.FileItem
import com.android_explorer.data.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One row in the analyzer: a child entry (folder or file) with its computed on-disk size. */
data class AnalyzerRow(val item: FileItem, val size: Long)

data class AnalyzerUiState(
    val dir: File,
    val rows: List<AnalyzerRow> = emptyList(),
    val totalSize: Long = 0L,
    // Largest-first by default — the whole point is spotting the space hogs.
    val ascending: Boolean = false,
    val scanning: Boolean = false,
    // Progress while a level's sizes compute (recursive folder walks can take a moment).
    val scanned: Int = 0,
    val total: Int = 0,
)

/**
 * Disk-usage analyzer. Drills one directory level at a time: lists the current folder's children and
 * computes each one's recursive size off the main thread (reusing [FileRepository.folderSize]), then
 * sorts folders + files together purely by size. Results are cached per path for the session so
 * going back/forward is instant, and re-sorting (asc/desc) never recomputes.
 */
class StorageAnalyzerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FileRepository()
    private var root: File = repo.storageRoot

    private val _state = MutableStateFlow(AnalyzerUiState(dir = root))
    val state: StateFlow<AnalyzerUiState> = _state.asStateFlow()

    // Session cache of scanned levels (dir path -> computed rows), so revisits skip the walk.
    private val cache = HashMap<String, List<AnalyzerRow>>()
    private var scanJob: Job? = null

    /**
     * Bind the session to a volume [rootDir] and open at [startDir] (navigateUp stops at root).
     * Clears the cache first: this VM is Activity-scoped and survives leaving/returning to the screen,
     * so a fresh open must re-scan to reflect files deleted/added elsewhere (e.g. in the browser)
     * rather than replay stale sizes.
     */
    fun openAt(rootDir: File, startDir: File) {
        root = rootDir
        cache.clear()
        navigateTo(startDir)
    }

    /** Re-scan the current folder from disk (drops all cached levels). Backs the top-bar Refresh action. */
    fun refresh() {
        cache.clear()
        load(_state.value.dir)
    }

    fun open(item: FileItem) {
        if (item.isDirectory) item.file?.let { navigateTo(it) }
    }

    private fun navigateTo(dir: File) {
        _state.value = _state.value.copy(dir = dir)
        load(dir)
    }

    /** Returns false when already at the bound root (caller may then exit the screen). */
    fun navigateUp(): Boolean {
        val current = _state.value.dir
        if (current.absolutePath == root.absolutePath) return false
        val parent = current.parentFile ?: return false
        navigateTo(parent)
        return true
    }

    val canGoUp: Boolean get() = _state.value.dir.absolutePath != root.absolutePath

    fun setAscending(ascending: Boolean) {
        val s = _state.value
        if (s.ascending == ascending) return
        _state.value = s.copy(ascending = ascending, rows = sortRows(s.rows, ascending))
    }

    fun toggleSort() = setAscending(!_state.value.ascending)

    private fun load(dir: File) {
        scanJob?.cancel()
        val ascending = _state.value.ascending

        cache[dir.absolutePath]?.let { cached ->
            _state.value = _state.value.copy(
                rows = sortRows(cached, ascending),
                totalSize = cached.sumOf { it.size },
                scanning = false,
                scanned = cached.size,
                total = cached.size,
            )
            return
        }

        val children = dir.listFiles()?.asList().orEmpty()
        _state.value = _state.value.copy(
            rows = emptyList(),
            totalSize = 0L,
            scanning = children.isNotEmpty(),
            scanned = 0,
            total = children.size,
        )
        if (children.isEmpty()) return

        scanJob = viewModelScope.launch {
            val rows = ArrayList<AnalyzerRow>(children.size)
            var scanned = 0
            for (f in children) {
                val size = withContext(Dispatchers.IO) {
                    if (f.isDirectory) repo.folderSize(f) else f.length()
                }
                rows.add(AnalyzerRow(FileItem.from(f), size))
                scanned++
                // Publish progress; keep the list unsorted/hidden until the level completes.
                _state.value = _state.value.copy(scanned = scanned)
            }
            cache[dir.absolutePath] = rows
            _state.value = _state.value.copy(
                rows = sortRows(rows, _state.value.ascending),
                totalSize = rows.sumOf { it.size },
                scanning = false,
            )
        }
    }

    private fun sortRows(rows: List<AnalyzerRow>, ascending: Boolean): List<AnalyzerRow> =
        if (ascending) rows.sortedBy { it.size } else rows.sortedByDescending { it.size }
}
