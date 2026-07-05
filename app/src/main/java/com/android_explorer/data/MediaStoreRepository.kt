package com.android_explorer.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.android_explorer.archive.ArchiveType
import java.io.File

/**
 * Aggregates files of a [MediaCategory] across the whole device via MediaStore. MediaStore is
 * already indexed by Android, so this is far faster than walking the filesystem — and, since the
 * app holds All-files access, each row's real path (`DATA`) is readable and maps straight to a
 * [FileItem], reusing the existing list/context UI.
 *
 * Images/Audio/Video use their dedicated collections. Documents have no MediaStore collection, so
 * we query the generic Files table filtered by well-known document extensions.
 */
class MediaStoreRepository {

    fun load(context: Context, category: MediaCategory): List<FileItem> = when (category) {
        MediaCategory.IMAGES -> query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null)
        MediaCategory.VIDEO -> query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null)
        MediaCategory.AUDIO -> query(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null)
        MediaCategory.DOCUMENTS -> {
            // No "documents" collection exists; match by extension on the generic Files table.
            val clause = DOC_EXTENSIONS.joinToString(" OR ") {
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            }
            val args = DOC_EXTENSIONS.map { "%.$it" }.toTypedArray()
            query(context, MediaStore.Files.getContentUri("external"), "($clause)", args)
        }
    }

    private fun query(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<FileItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val out = ArrayList<FileItem>()
        context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { c ->
            val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            if (dataIdx < 0) return emptyList()
            while (c.moveToNext()) {
                val path = c.getString(dataIdx) ?: continue
                val file = File(path)
                // Skip rows the index still lists but that are gone or are directories.
                if (!file.isFile) continue
                val name = (if (nameIdx >= 0) c.getString(nameIdx) else null) ?: file.name
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else file.length()
                val modified = if (dateIdx >= 0) c.getLong(dateIdx) * 1000L else file.lastModified()
                out += FileItem(
                    file = file,
                    name = name,
                    isDirectory = false,
                    size = size,
                    lastModified = modified,
                    isArchive = ArchiveType.isArchive(file),
                )
            }
        }
        return out
    }

    companion object {
        /** Extensions treated as "documents" for the Documents category. */
        val DOC_EXTENSIONS = listOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "rtf", "odt", "ods", "odp", "csv", "epub",
        )
    }
}
