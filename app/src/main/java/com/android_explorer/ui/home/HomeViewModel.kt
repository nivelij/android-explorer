package com.android_explorer.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android_explorer.data.FileItem
import com.android_explorer.data.RecentFilesRepository
import com.android_explorer.data.StorageRepository
import com.android_explorer.data.VolumeStat
import com.android_explorer.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val loading: Boolean = true,
    val hasAccess: Boolean = false,
    val volumes: List<VolumeStat> = emptyList(),
    val recents: List<FileItem> = emptyList(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val storageRepo = StorageRepository(app)
    private val recentsRepo = RecentFilesRepository(app)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val hasAccess = Permissions.hasAllFilesAccess()
            _state.value = _state.value.copy(loading = true, hasAccess = hasAccess)
            val volumes = withContext(Dispatchers.IO) { storageRepo.volumes() }
            val recents = if (hasAccess) withContext(Dispatchers.IO) { recentsRepo.recentFiles() } else emptyList()
            _state.value = HomeUiState(
                loading = false,
                hasAccess = hasAccess,
                volumes = volumes,
                recents = recents,
            )
        }
    }
}
