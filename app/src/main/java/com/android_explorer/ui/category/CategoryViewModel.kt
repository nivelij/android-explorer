package com.android_explorer.ui.category

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android_explorer.data.FileDetails
import com.android_explorer.data.FileItem
import com.android_explorer.data.FileRepository
import com.android_explorer.data.MediaCategory
import com.android_explorer.data.MediaStoreRepository
import com.android_explorer.service.ArchiveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoryUiState(
    val category: MediaCategory? = null,
    val items: List<FileItem> = emptyList(),
    val loading: Boolean = true,
)

/** Backs [CategoryScreen]: a flat, device-wide list of files for one [MediaCategory]. */
class CategoryViewModel(app: Application) : AndroidViewModel(app) {

    private val media = MediaStoreRepository()
    private val fileRepo = FileRepository()

    private val _state = MutableStateFlow(CategoryUiState())
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()

    private val _details = MutableStateFlow<FileDetails?>(null)
    val details: StateFlow<FileDetails?> = _details.asStateFlow()

    fun load(category: MediaCategory) {
        _state.value = CategoryUiState(category = category, loading = true)
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { media.load(getApplication(), category) }
            _state.value = _state.value.copy(items = items, loading = false)
        }
    }

    fun refresh() {
        _state.value.category?.let { load(it) }
    }

    fun delete(item: FileItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { fileRepo.delete(listOf(item)) }
            refresh()
        }
    }

    fun extract(item: FileItem) {
        val dest = fileRepo.extractionTargetFor(item.file)
        ArchiveService.extract(getApplication(), item.path, dest.absolutePath)
    }

    fun showDetails(item: FileItem) {
        viewModelScope.launch {
            _details.value = withContext(Dispatchers.IO) { fileRepo.details(item) }
        }
    }

    fun dismissDetails() {
        _details.value = null
    }
}
