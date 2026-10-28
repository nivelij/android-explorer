package com.android_explorer.job

/**
 * Immutable snapshot of an in-flight (or finished) long-running file job — archive extraction and
 * compression, copy/move transfers, and app-update downloads. Shared by the dialog and the
 * notification.
 */
data class JobProgress(
    val jobId: Long,
    val kind: Kind,
    val title: String,
    val currentEntry: String = "",
    val processedEntries: Int = 0,
    val totalEntries: Int = -1,
    val processedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val state: State = State.RUNNING,
    val error: String? = null,
    val outputPath: String? = null,
    /** Extra detail on a terminal state, e.g. "2 items skipped". */
    val note: String? = null,
    // Carried so the password prompt can retry the same job.
    val sourcePath: String? = null,
    val destPath: String? = null,
    val wrongPassword: Boolean = false,
) {
    enum class Kind { EXTRACT, COMPRESS, COPY, MOVE, UPDATE }
    enum class State { RUNNING, SUCCESS, ERROR, CANCELLED, NEEDS_PASSWORD }

    val finished: Boolean get() = state == State.SUCCESS || state == State.ERROR || state == State.CANCELLED

    val awaitingPassword: Boolean get() = state == State.NEEDS_PASSWORD

    val indeterminate: Boolean get() = totalBytes <= 0L && totalEntries <= 0

    /** 0f..1f, or 0f when indeterminate. */
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (processedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            totalEntries > 0 -> (processedEntries.toFloat() / totalEntries).coerceIn(0f, 1f)
            else -> 0f
        }
}
