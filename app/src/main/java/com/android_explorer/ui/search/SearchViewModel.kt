package com.android_explorer.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android_explorer.data.FileDetails
import com.android_explorer.data.FileItem
import com.android_explorer.data.FileRepository
import com.android_explorer.service.ArchiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchUiState(
    val query: String = "",
    val results: List<FileItem> = emptyList(),
    val searching: Boolean = false,
    val hasSearched: Boolean = false,
)

/** Backs [SearchScreen]: a device-wide filename search under the storage root. */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FileRepository()

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _details = MutableStateFlow<FileDetails?>(null)
    val details: StateFlow<FileDetails?> = _details.asStateFlow()

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        _state.value = _state.value.copy(searching = true, hasSearched = true)
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) { repo.search(q) }
            _state.value = _state.value.copy(results = results, searching = false)
        }
    }

    fun delete(item: FileItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(listOf(item)) }
            search() // re-run so the deleted entry drops out
        }
    }

    fun extract(item: FileItem) {
        val dest = repo.extractionTargetFor(item.file)
        ArchiveService.extract(getApplication(), item.path, dest.absolutePath)
    }

    fun showDetails(item: FileItem) {
        viewModelScope.launch {
            _details.value = withContext(Dispatchers.IO) { repo.details(item) }
        }
    }

    fun dismissDetails() {
        _details.value = null
    }
}
