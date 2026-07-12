package com.android_explorer.data

import com.android_explorer.archive.ArchiveType
import java.io.File

/**
 * Where a [FileItem] lives. Local items wrap a real [java.io.File]; Drive items carry the Drive id +
 * parent + MIME type and have no local file until downloaded. This is what lets the same UI/browser
 * render either backend (see StorageBackend).
 */
sealed interface NodeRef {
    data class Local(val file: File) : NodeRef
    data class Drive(val id: String, val parentId: String?, val mimeType: String) : NodeRef
}

/** A single entry surfaced to the UI, from either the local filesystem or Google Drive. */
data class FileItem(
    val location: NodeRef,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isArchive: Boolean,
) {
    val isRemote: Boolean get() = location is NodeRef.Drive

    /** The local file, or null for remote (Drive) items. Use [requireFile] where locality is known. */
    val file: File? get() = (location as? NodeRef.Local)?.file

    /** Stable identity for list keys, selection sets, and clipboard refs. */
    val path: String
        get() = when (val loc = location) {
            is NodeRef.Local -> loc.file.absolutePath
            is NodeRef.Drive -> "drive:${loc.id}"
        }

    // Derived from the name so it works for both backends (equivalent to File.extension for local).
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
    val isImage: Boolean get() = !isDirectory && extension in IMAGE_EXTENSIONS
    val isPdf: Boolean get() = !isDirectory && extension == "pdf"
    val isEditableText: Boolean get() = !isDirectory && extension in TEXT_EXTENSIONS

    /** The local file, asserting the item is local. Only call on paths that exclude remote items. */
    fun requireFile(): File = file ?: error("requireFile() on a remote item: $name")

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

        /** Extensions opened by the built-in text editor. */
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "html", "htm",
            "css", "js", "ts", "kt", "kts", "java", "py", "c", "cpp", "h", "hpp", "cs",
            "sh", "bash", "rb", "go", "rs", "php", "sql", "yaml", "yml", "toml", "ini",
            "cfg", "conf", "properties", "gradle", "gitignore", "env", "lua", "dart",
            // RetroArch: overrides (.cfg), core options, input remaps, core info, cheats,
            // shader presets/sources, and playlists (.lpl is JSON) — all plain text.
            "opt", "rmp", "info", "cht", "lpl",
            "glslp", "glsl", "slangp", "slang", "cgp", "cg",
            // GameNative / Daijishō frontend mapping files (plain text: a Steam appid or a
            // "# Daijishou Player Template" body).
            "steam", "steamappid",
        )

        fun from(file: File): FileItem = FileItem(
            location = NodeRef.Local(file),
            name = file.name,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            lastModified = file.lastModified(),
            isArchive = ArchiveType.isArchive(file),
        )
    }
}

enum class SortBy { NAME, SIZE, DATE, TYPE }

enum class ViewMode { LIST, GRID }
