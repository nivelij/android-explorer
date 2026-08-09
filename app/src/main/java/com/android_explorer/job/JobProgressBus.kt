package com.android_explorer.job

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for file-job progress. The service publishes here; both the in-app dialog
 * and the notification observe it. One job runs at a time, so a single slot is enough.
 */
object JobProgressBus {
    /** ~10 UI updates/second — smooth to the eye, cheap to recompose. */
    private const val MIN_INTERVAL_NANOS = 100_000_000L

    @Volatile
    private var lastEmitNanos = 0L

    private val _progress = MutableStateFlow<JobProgress?>(null)
    val progress: StateFlow<JobProgress?> = _progress.asStateFlow()

    /**
     * Terminal (success/error/cancelled) events only. Screens observe this to refresh their listing
     * instead of collecting [progress] in their top-level composable body — that would invalidate the
     * whole screen, file list included, on every byte-progress tick. Cancelled and failed jobs still
     * leave real files behind, so all terminal states must refresh, not just success.
     */
    val completions: Flow<JobProgress> = _progress
        .filterNotNull()
        .filter { it.finished }
        .distinctUntilChangedBy { it.jobId to it.state }

    private val cancelled = AtomicBoolean(false)

    fun start(initial: JobProgress) {
        cancelled.set(false)
        lastEmitNanos = 0L
        _progress.value = initial
    }

    /**
     * Publishes a snapshot, coalescing running ticks to [MIN_INTERVAL_NANOS]. Engines report far
     * faster than any UI can consume — and a third-party one (junrar) can report per written byte —
     * so this is a backstop that keeps a misbehaving engine from flooding recomposition regardless
     * of how well each individual extraction path throttles itself. Terminal and password states
     * always publish, so a completion is never dropped.
     */
    fun update(snapshot: JobProgress) {
        if (snapshot.finished || snapshot.awaitingPassword) {
            lastEmitNanos = 0L
            _progress.value = snapshot
            return
        }
        val now = System.nanoTime()
        if (now - lastEmitNanos < MIN_INTERVAL_NANOS) return
        lastEmitNanos = now
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
