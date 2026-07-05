package com.android_explorer.data

import com.android_explorer.archive.ArchiveType
import java.io.File

/** A single filesystem entry surfaced to the UI. */
data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isArchive: Boolean,
) {
    val path: String get() = file.absolutePath
    val extension: String get() = if (isDirectory) "" else file.extension.lowercase()
    val isImage: Boolean get() = !isDirectory && extension in IMAGE_EXTENSIONS
    val isPdf: Boolean get() = !isDirectory && extension == "pdf"
    val isEditableText: Boolean get() = !isDirectory && extension in TEXT_EXTENSIONS

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
        )

        fun from(file: File): FileItem = FileItem(
            file = file,
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
