package com.android_explorer.job

import java.io.IOException

/** Thrown when the user cancels an in-flight job. */
class JobCancelledException : IOException("Cancelled")

/** Callbacks a job engine uses to report progress and honour cancellation. */
interface JobListener {
    fun onProgress(
        entry: String,
        entriesDone: Int,
        entriesTotal: Int,
        bytesDone: Long,
        bytesTotal: Long,
    )

    fun isCancelled(): Boolean
}
