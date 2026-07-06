package com.android_explorer.data

import android.content.Context
import com.android_explorer.data.drive.DriveRepository

/**
 * Executes a paste of a [TransferClipboard.Clip] into a destination folder, dispatching on the source
 * and destination backends:
 *
 * | source → dest | action |
 * |---|---|
 * | Local → Local | filesystem copy / move |
 * | Local → Drive | **upload** (recursive for folders); delete local on cut |
 * | Drive → Local | download (recursive for folders); trash on cut |
 * | Drive → Drive | server-side move (cut) or copy (recursive for folders) |
 *
 * Suspends; callers run it off the main thread and show progress.
 */
object TransferManager {
    private val files = FileRepository()
    private val drive = DriveRepository()

    suspend fun paste(context: Context, clip: TransferClipboard.Clip, dest: NodeRef) {
        for (item in clip.items) {
            when (val src = item.location) {
                is NodeRef.Local -> when (dest) {
                    is NodeRef.Local ->
                        // Skip non-existent sources and pasting a folder into itself/its own subtree.
                        if (src.file.exists() && !files.isInsideOrSame(src.file, dest.file)) {
                            if (clip.cut) files.moveInto(src.file, dest.file) else files.copyInto(src.file, dest.file)
                        }
                    is NodeRef.Drive -> {
                        drive.uploadInto(context, src.file, dest.id)
                        if (clip.cut) src.file.deleteRecursively()
                    }
                }
                is NodeRef.Drive -> when (dest) {
                    is NodeRef.Local -> {
                        drive.downloadInto(context, item, dest.file)
                        if (clip.cut) drive.trash(context, item)
                    }
                    is NodeRef.Drive ->
                        if (clip.cut) drive.move(context, item, dest.id) else drive.copyInto(context, item, dest.id)
                }
            }
        }
    }
}
