package com.android_explorer.data

import android.content.Context
import com.android_explorer.data.drive.DriveRepository
import com.android_explorer.job.JobCancelledException
import com.android_explorer.job.JobListener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Outcome of a paste, mirroring `ArchiveResult` so the service can finish every job kind alike. */
sealed interface TransferResult {
    data class Success(val files: Int, val bytes: Long, val skipped: List<String>) : TransferResult
    data class Failure(val message: String) : TransferResult
    data class Cancelled(val files: Int, val totalFiles: Int) : TransferResult
}

/**
 * Executes a paste of a [TransferClipboard.Clip] into a destination folder, dispatching on the
 * source and destination backends:
 *
 * | source → dest | action |
 * |---|---|
 * | Local → Local | filesystem copy / move |
 * | Local → Drive | **upload** (recursive for folders); delete local on cut |
 * | Drive → Local | download (recursive for folders); trash on cut |
 * | Drive → Drive | server-side move (cut) or copy (recursive for folders) |
 *
 * Runs in three phases so progress is honest: same-volume moves are renamed *first* (instant, and
 * they never enter the totals), then what remains is scanned, then transferred. Local work reports
 * per byte; Drive work reports per file, since intra-file progress would mean reaching into the
 * HTTP layer.
 *
 * Suspends; callers run it off the main thread (the foreground service does).
 */
object TransferManager {
    private val files = FileRepository()
    private val drive = DriveRepository()

    /** 1 MB per transfer: large enough for throughput on FUSE-backed storage, small enough to cancel promptly. */
    private const val CHUNK = 1L shl 20

    /** Cap on the pre-scan; past this we show an indeterminate bar rather than stall on a huge tree. */
    private const val SCAN_BUDGET_MS = 2_000L

    /** One clip item that still needs transferring, with its measured cost (-1 ⇒ unknown). */
    private data class Planned(val item: FileItem, val files: Int, val bytes: Long)

    private class Counters(val totalFiles: Int, val totalBytes: Long) {
        var files = 0
        var bytes = 0L
    }

    suspend fun paste(
        context: Context,
        clip: TransferClipboard.Clip,
        dest: NodeRef,
        listener: JobListener,
    ): TransferResult {
        val skipped = mutableListOf<String>()
        var counters = Counters(0, 0L)
        return try {
            listener.onProgress("Scanning…", 0, -1, 0L, -1L)

            // Phase 1 — instant same-volume renames. Done before scanning so a huge tree that only
            // needs a rename costs no walk at all, and never contributes fake progress.
            val remaining = mutableListOf<FileItem>()
            for (item in clip.items) {
                val reason = precheck(item, dest)
                if (reason != null) { skipped += reason; continue }
                val src = item.location
                if (clip.cut && src is NodeRef.Local && dest is NodeRef.Local) {
                    // Cutting into the folder it already lives in is a no-op. It must not fall
                    // through to uniqueTarget, which would rename the file to "name (1)".
                    if (src.file.parentFile == dest.file) continue
                    val target = files.uniqueTarget(dest.file, src.file.name)
                    // renameTo is side-effect-free on failure (cross-volume), so falling through is safe.
                    if (src.file.renameTo(target)) continue
                }
                remaining += item
            }
            if (remaining.isEmpty()) {
                return TransferResult.Success(clip.items.size - skipped.size, 0L, skipped)
            }

            // Phase 2 — measure what is actually left to move.
            val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
            val planned = remaining.map { plan(it, deadline) }
            val unknown = planned.any { it.bytes < 0 }
            counters = Counters(
                totalFiles = if (unknown) -1 else planned.sumOf { it.files },
                totalBytes = if (unknown) -1L else planned.sumOf { it.bytes },
            )

            // Phase 3 — transfer.
            for (p in planned) {
                if (listener.isCancelled()) throw JobCancelledException()
                transfer(context, p.item, dest, clip.cut, counters, listener)
            }
            TransferResult.Success(counters.files, counters.bytes, skipped)
        } catch (_: JobCancelledException) {
            TransferResult.Cancelled(counters.files, counters.totalFiles)
        } catch (e: Exception) {
            TransferResult.Failure(e.message?.ifBlank { null } ?: e.javaClass.simpleName)
        }
    }

    /** Returns a human reason to skip this item, or null to proceed. */
    private fun precheck(item: FileItem, dest: NodeRef): String? {
        val src = item.location
        if (src !is NodeRef.Local || dest !is NodeRef.Local) return null
        if (!src.file.exists()) return "${item.name} (no longer exists)"
        if (files.isInsideOrSame(src.file, dest.file)) return "${item.name} (can't paste a folder into itself)"
        return null
    }

    private fun plan(item: FileItem, deadline: Long): Planned {
        val src = item.location
        if (src is NodeRef.Local) {
            val scanned = scanLocal(src.file, deadline)
            return Planned(item, scanned?.first ?: 1, scanned?.second ?: -1L)
        }
        // Drive folders report size 0 (unknown, not empty) and pre-walking them costs one API round
        // trip per directory, so leave folder cost unknown rather than pre-scanning.
        return if (item.isDirectory) Planned(item, 1, -1L) else Planned(item, 1, item.size)
    }

    /** One walk yielding (fileCount, bytes); null past [deadline] so a giant tree goes indeterminate. */
    private fun scanLocal(root: File, deadline: Long): Pair<Int, Long>? {
        if (!root.isDirectory) return 1 to root.length()
        var n = 0
        var b = 0L
        for (f in root.walkTopDown().onFail { _, _ -> }) {
            if (System.currentTimeMillis() > deadline) return null
            if (f.isFile) { n++; b += f.length() }
        }
        return n to b
    }

    private suspend fun transfer(
        context: Context,
        item: FileItem,
        dest: NodeRef,
        cut: Boolean,
        c: Counters,
        l: JobListener,
    ) {
        when (val src = item.location) {
            is NodeRef.Local -> when (dest) {
                is NodeRef.Local ->
                    copyTree(src.file, files.uniqueTarget(dest.file, src.file.name), c, l, moving = cut)
                is NodeRef.Drive -> {
                    drive.uploadInto(context, src.file, dest.id) { name, bytes -> tick(name, bytes, c, l) }
                    if (cut) src.file.deleteRecursively()
                }
            }
            is NodeRef.Drive -> when (dest) {
                is NodeRef.Local -> {
                    drive.downloadInto(context, item, dest.file) { name, bytes -> tick(name, bytes, c, l) }
                    if (cut) drive.trash(context, item)
                }
                is NodeRef.Drive ->
                    if (cut) {
                        drive.move(context, item, dest.id)
                        tick(item.name, 0L, c, l)
                    } else {
                        drive.copyInto(context, item, dest.id) { name, bytes -> tick(name, bytes, c, l) }
                    }
            }
        }
    }

    /** Per-file progress for Drive work; throws to cancel between files. */
    private fun tick(name: String, bytes: Long, c: Counters, l: JobListener) {
        if (l.isCancelled()) throw JobCancelledException()
        c.files++
        c.bytes += bytes.coerceAtLeast(0)
        l.onProgress(name, c.files, c.totalFiles, c.bytes, c.totalBytes)
    }

    private fun copyTree(src: File, dst: File, c: Counters, l: JobListener, moving: Boolean) {
        if (l.isCancelled()) throw JobCancelledException()
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name), c, l, moving) }
            dst.setLastModified(src.lastModified())
            // Only succeeds once the directory has been emptied by the recursion above.
            if (moving) src.delete()
        } else {
            copyFile(src, dst, c, l)
            // Delete the source only once the copy is verifiably complete, so the data exists in at
            // least one place at every instant — including through a process kill.
            if (moving && dst.length() == src.length()) src.delete()
        }
    }

    private fun copyFile(src: File, dst: File, c: Counters, l: JobListener) {
        var complete = false
        try {
            FileInputStream(src).channel.use { inCh ->
                FileOutputStream(dst).channel.use { outCh ->
                    val size = inCh.size()
                    var pos = 0L
                    l.onProgress(src.name, c.files, c.totalFiles, c.bytes, c.totalBytes)
                    while (pos < size) {
                        if (l.isCancelled()) throw JobCancelledException()
                        val n = inCh.transferTo(pos, CHUNK, outCh)
                        if (n <= 0L) throw IOException("Short copy: ${src.name}")
                        pos += n
                        c.bytes += n
                        l.onProgress(src.name, c.files, c.totalFiles, c.bytes, c.totalBytes)
                    }
                }
            }
            dst.setLastModified(src.lastModified())
            complete = true
        } finally {
            // Never leave a half-written file behind on cancel or error.
            if (!complete) dst.delete()
        }
        c.files++
    }
}
