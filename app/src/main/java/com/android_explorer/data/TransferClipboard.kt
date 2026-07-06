package com.android_explorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single app-wide clipboard shared by the local and Drive browsers, so copy/cut in one and paste in
 * the other performs the right cross-backend transfer (see [TransferManager]). Holds [FileItem]s (which
 * carry their [NodeRef] backend) plus whether this is a move (cut) or duplicate (copy).
 */
object TransferClipboard {
    data class Clip(val items: List<FileItem>, val cut: Boolean)

    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip.asStateFlow()

    val current: Clip? get() = _clip.value

    fun set(items: List<FileItem>, cut: Boolean) {
        _clip.value = if (items.isEmpty()) null else Clip(items, cut)
    }

    fun clear() { _clip.value = null }
}
