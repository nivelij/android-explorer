package com.android_explorer.data

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Reads recently added/modified files from MediaStore (requires all-files access to see everything). */
class RecentFilesRepository(private val context: Context) {

    fun recentFiles(limit: Int = 30): List<FileItem> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        // Only regular files (exclude directory rows), newest first.
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val out = ArrayList<FileItem>(limit)
        try {
            context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext() && out.size < limit) {
                    val path = c.getString(dataIdx) ?: continue
                    val file = File(path)
                    if (file.isFile) out += FileItem.from(file)
                }
            }
        } catch (_: Exception) {
            // Permission may not be granted yet; return what we have (possibly empty).
        }
        return out
    }
}
