package com.android_explorer.data.drive

import android.content.Context
import com.android_explorer.archive.ArchiveType
import com.android_explorer.data.FileItem
import com.android_explorer.data.NodeRef
import java.io.File

/**
 * Drive-backed counterpart to [com.android_explorer.data.FileRepository]: lists a Drive folder as
 * [FileItem]s (so the same row/UI renders it) and downloads a file to the cache when a real local
 * [File] is needed (open/view). Read-only for Phase 1.
 */
class DriveRepository {

    /** List the children of [folderId] ("root" = My Drive) as UI [FileItem]s. Server-sorted folder,name. */
    suspend fun list(context: Context, folderId: String = "root"): List<FileItem> =
        DriveApi.listFolder(context, folderId).map { it.toFileItem() }

    /**
     * Ensure a local copy of a Drive [item] exists in the cache and return it (downloading if needed).
     * Keyed by Drive id so re-opening reuses the cached file; the original name is preserved so the
     * editor/PDF reader/"open with" see the right extension.
     */
    suspend fun downloadToCache(context: Context, item: FileItem): File {
        val ref = item.location as? NodeRef.Drive ?: error("downloadToCache on a non-Drive item")
        val dir = File(context.cacheDir, "drive/${ref.id}").apply { mkdirs() }
        val dest = File(dir, item.name)
        if (!dest.exists() || dest.length() == 0L) DriveApi.download(context, ref.id, dest)
        return dest
    }

    /** The connected account's email, for display; null if unavailable. */
    suspend fun accountEmail(context: Context): String? = DriveApi.accountEmail(context)

    /** Delete the transient view-cache (files downloaded just to open/preview). Safe to call on startup. */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, "drive").deleteRecursively() }
    }

    /** Drive storage quota as a VolumeStat (for the StorageMeter); null if unavailable. */
    suspend fun storageQuota(context: Context): com.android_explorer.data.VolumeStat? =
        DriveApi.storageQuota(context)

    // ---- writes ----

    suspend fun createFolder(context: Context, name: String, parentId: String): String =
        DriveApi.createFolder(context, name, parentId)

    suspend fun rename(context: Context, item: FileItem, newName: String) =
        DriveApi.rename(context, item.driveRef.id, newName)

    /** Recoverable delete: moves the Drive item to the trash. */
    suspend fun trash(context: Context, item: FileItem) =
        DriveApi.trash(context, item.driveRef.id)

    /** Drive→Drive move: reparent from the item's current parent to [newParentId]. */
    suspend fun move(context: Context, item: FileItem, newParentId: String) {
        val ref = item.driveRef
        DriveApi.move(context, ref.id, addParent = newParentId, removeParent = ref.parentId ?: "root")
    }

    /** Drive→Drive copy (recursive for folders, since Drive's copy API is file-only). */
    suspend fun copyInto(context: Context, item: FileItem, destParentId: String) {
        if (item.isDirectory) {
            val newId = DriveApi.createFolder(context, item.name, destParentId)
            list(context, item.driveRef.id).forEach { copyInto(context, it, newId) }
        } else {
            DriveApi.copyFile(context, item.driveRef.id, destParentId)
        }
    }

    /** Upload a local file/folder into a Drive folder (recursive for folders). */
    suspend fun uploadInto(context: Context, local: File, parentId: String) {
        if (local.isDirectory) {
            val sub = DriveApi.createFolder(context, local.name, parentId)
            local.listFiles()?.forEach { uploadInto(context, it, sub) }
        } else {
            DriveApi.uploadFile(context, local, parentId)
        }
    }

    /** Download a Drive file/folder into a local directory (recursive for folders); returns the new local File. */
    suspend fun downloadInto(context: Context, item: FileItem, destDir: File): File {
        val target = uniqueChild(destDir, item.name)
        if (item.isDirectory) {
            target.mkdirs()
            list(context, item.driveRef.id).forEach { downloadInto(context, it, target) }
        } else {
            DriveApi.download(context, item.driveRef.id, target)
        }
        return target
    }

    private val FileItem.driveRef: NodeRef.Drive
        get() = location as? NodeRef.Drive ?: error("Expected a Drive item: $name")

    /** A non-clashing child File in [dir] (appends " (n)" before any extension). */
    private fun uniqueChild(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 1
        while (t.exists()) { t = File(dir, "$base ($i)$ext"); i++ }
        return t
    }

    private fun DriveFile.toFileItem(): FileItem = FileItem(
        location = NodeRef.Drive(id = id, parentId = parentId, mimeType = mimeType),
        name = name,
        isDirectory = isFolder,
        size = size,
        lastModified = modifiedTime,
        isArchive = !isFolder && ArchiveType.fromFileName(name) != null,
    )
}
