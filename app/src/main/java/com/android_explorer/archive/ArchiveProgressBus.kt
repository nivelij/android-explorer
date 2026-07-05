package com.android_explorer.archive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for archive-job progress. The service publishes here; both the in-app
 * dialog and the notification observe it. One job runs at a time, so a single slot is enough.
 */
object ArchiveProgressBus {
    private val _progress = MutableStateFlow<ArchiveProgress?>(null)
    val progress: StateFlow<ArchiveProgress?> = _progress.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    fun start(initial: ArchiveProgress) {
        cancelled.set(false)
        _progress.value = initial
    }

    fun update(snapshot: ArchiveProgress) {
        _progress.value = snapshot
    }

    /** Requests cancellation of the running job; the worker polls [isCancelled]. */
    fun requestCancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    /** Clears a finished job from the UI (dialog dismiss). */
    fun clear() {
        _progress.value = null
    }
}
