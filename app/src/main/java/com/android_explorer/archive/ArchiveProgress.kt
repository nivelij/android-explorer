package com.android_explorer.archive

/** Immutable snapshot of an in-flight (or finished) archive job. Shared by the dialog and notification. */
data class ArchiveProgress(
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
    // Carried so the password prompt can retry the same job.
    val sourcePath: String? = null,
    val destPath: String? = null,
    val wrongPassword: Boolean = false,
) {
    enum class Kind { EXTRACT, COMPRESS }
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
