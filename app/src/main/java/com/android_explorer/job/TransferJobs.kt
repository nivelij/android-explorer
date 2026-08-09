package com.android_explorer.job

import com.android_explorer.data.NodeRef
import com.android_explorer.data.TransferClipboard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local hand-off of a paste from the UI to the foreground service.
 *
 * The service must not read [TransferClipboard] itself — the clipboard is cleared as soon as the
 * paste is handed over, which would race the service's asynchronous start. Serialising a mixed
 * local+Drive clip through Intent extras would mean round-tripping every Drive field, so the intent
 * carries only a token.
 *
 * Losing the stash on process death is fine: the service starts with `START_NOT_STICKY`, so Android
 * never redelivers the intent or recreates the job after the process is gone.
 */
object TransferJobs {
    data class Job(val clip: TransferClipboard.Clip, val dest: NodeRef)

    private val pending = ConcurrentHashMap<Long, Job>()
    private val seq = AtomicLong()

    fun stash(clip: TransferClipboard.Clip, dest: NodeRef): Long =
        seq.incrementAndGet().also { pending[it] = Job(clip, dest) }

    fun take(token: Long): Job? = pending.remove(token)
}
