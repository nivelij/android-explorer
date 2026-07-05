package com.android_explorer.data

import android.os.Environment
import java.io.File
import java.util.Locale

/** Directory listing and basic file operations over the raw filesystem (all-files access). */
class FileRepository {

    val storageRoot: File get() = Environment.getExternalStorageDirectory()

    /**
     * Recursively finds files/folders whose name contains [query] (case-insensitive), anywhere under
     * the storage root, regardless of extension. Hidden entries are skipped, unreadable directories
     * are ignored. Collection is capped for memory; results are ranked name-starts-with first, then
     * alphabetically, and truncated to [limit].
     */
    fun search(query: String, limit: Int = 500): List<FileItem> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()

        val matches = ArrayList<FileItem>()
        val collectCap = 2000
        val walker = storageRoot.walkTopDown()
            .onEnter { !it.name.startsWith(".") } // don't descend into hidden directories
            .onFail { _, _ -> }                   // ignore directories we can't read
        for (f in walker) {
            val name = f.name
            if (name.startsWith(".")) continue
            if (name.lowercase(Locale.ROOT).contains(q)) {
                matches.add(FileItem.from(f))
                if (matches.size >= collectCap) break
            }
        }
        return matches
            .sortedWith(
                compareByDescending<FileItem> { it.name.lowercase(Locale.ROOT).startsWith(q) }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            .take(limit)
    }

    fun list(dir: File, showHidden: Boolean, sortBy: SortBy, ascending: Boolean): List<FileItem> {
        val children = dir.listFiles()?.asList().orEmpty()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { FileItem.from(it) }

        val comparator: Comparator<FileItem> = when (sortBy) {
            SortBy.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.TYPE -> compareBy({ it.extension }, { it.name.lowercase(Locale.ROOT) })
        }
        val directionApplied = if (ascending) comparator else comparator.reversed()
        // Folders always float to the top regardless of sort direction.
        return children.sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(directionApplied))
    }

    fun delete(items: List<FileItem>): Int {
        var count = 0
        items.forEach { if (deleteRecursively(it.file)) count++ }
        return count
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        return file.delete()
    }

    fun createFolder(parent: File, name: String): Boolean = File(parent, name).mkdirs()

    /** Creates an empty file; returns false if it already exists or on error. */
    fun createFile(parent: File, name: String): Boolean = try {
        File(parent, name).createNewFile()
    } catch (_: Exception) {
        false
    }

    /** Full metadata for the Details popup (created/accessed via NIO; folder size is recursive). */
    fun details(item: FileItem): FileDetails {
        val f = item.file
        var created = 0L
        var accessed = 0L
        try {
            val attrs = java.nio.file.Files.readAttributes(
                f.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
            )
            created = attrs.creationTime().toMillis()
            accessed = attrs.lastAccessTime().toMillis()
        } catch (_: Exception) {
            // Some filesystems don't expose creation/access time.
        }
        val size = if (f.isDirectory) folderSize(f) else f.length()
        val count = if (f.isDirectory) (f.listFiles()?.size ?: 0) else null
        val type = when {
            f.isDirectory -> "Folder"
            item.isArchive -> "Archive (${item.extension})"
            item.extension.isNotEmpty() -> "${item.extension.uppercase()} file"
            else -> "File"
        }
        return FileDetails(
            name = f.name,
            path = f.absolutePath,
            type = type,
            isDirectory = f.isDirectory,
            sizeBytes = size,
            itemCount = count,
            createdMillis = created,
            modifiedMillis = f.lastModified(),
            accessedMillis = accessed,
            readable = f.canRead(),
            writable = f.canWrite(),
            hidden = f.isHidden,
        )
    }

    /** Recursive size of a directory's contents (bytes). May be slow for large trees. */
    fun folderSize(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }

    /** Sub-directories of [dir] (for the folder picker), sorted by name. */
    fun subFolders(dir: File): List<File> =
        dir.listFiles()?.asList().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun rename(item: FileItem, newName: String): Boolean {
        val target = File(item.file.parentFile, newName)
        return item.file.renameTo(target)
    }

    /** Copies a file/folder into [destDir], picking a non-clashing name. */
    fun copyInto(source: File, destDir: File): Boolean = try {
        source.copyRecursively(uniqueTarget(destDir, source.name), overwrite = false)
    } catch (_: Exception) {
        false
    }

    /** Moves a file/folder into [destDir] (rename if possible, else copy+delete). */
    fun moveInto(source: File, destDir: File): Boolean {
        val target = uniqueTarget(destDir, source.name)
        if (source.renameTo(target)) return true
        return try {
            source.copyRecursively(target, overwrite = false)
            deleteRecursively(source)
        } catch (_: Exception) {
            false
        }
    }

    /** True if [destDir] is [source] or lives inside it (guards move/copy into self). */
    fun isInsideOrSame(source: File, destDir: File): Boolean {
        val s = source.canonicalPath
        val d = destDir.canonicalPath
        return d == s || d.startsWith(s + File.separator)
    }

    private fun uniqueTarget(destDir: File, name: String): File {
        var target = File(destDir, name)
        if (!target.exists()) return target
        val hasExt = name.contains('.') && !name.startsWith('.')
        val base = if (hasExt) name.substringBeforeLast('.') else name
        val ext = if (hasExt) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (target.exists()) {
            target = File(destDir, "$base ($i)$ext")
            i++
        }
        return target
    }

    /** Default extraction target: a sibling folder named after the archive (deduped if needed). */
    fun extractionTargetFor(archive: File): File =
        extractionTargetIn(archive.parentFile ?: storageRoot, archive)

    /** Extraction target inside a chosen [parent]: a folder named after the archive (deduped). */
    fun extractionTargetIn(parent: File, archive: File): File {
        val baseName = archive.name
            .removeSuffix(".tar.gz").removeSuffix(".tar.bz2").removeSuffix(".tar.xz")
            .substringBeforeLast('.')
        var target = File(parent, baseName)
        var i = 1
        while (target.exists()) {
            target = File(parent, "$baseName ($i)")
            i++
        }
        return target
    }
}
