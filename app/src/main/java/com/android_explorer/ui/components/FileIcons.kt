package com.android_explorer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.android_explorer.data.FileItem

/**
 * Every entry maps to one [FileKind]: a rounded Material glyph plus a fixed accent colour.
 * Colours are 400-level Material tones — saturated enough to read on OLED black yet not harsh
 * on a light surface — so they work across the System / Light / Dark / OLED themes without
 * adapting per-theme. Distinct hues per family keep the list scannable at a glance.
 */
enum class FileKind(val icon: ImageVector, val color: Color) {
    FOLDER(Icons.Rounded.Folder, Color(0xFFFFCA28)),        // amber
    ARCHIVE(Icons.Rounded.FolderZip, Color(0xFFFFA726)),    // orange / gold
    IMAGE(Icons.Rounded.Image, Color(0xFF66BB6A)),          // green
    VIDEO(Icons.Rounded.Movie, Color(0xFFAB47BC)),          // purple
    AUDIO(Icons.Rounded.MusicNote, Color(0xFFEC407A)),      // pink
    PDF(Icons.Rounded.PictureAsPdf, Color(0xFFEF5350)),     // red
    DOC(Icons.Rounded.Description, Color(0xFF42A5F5)),      // blue
    SHEET(Icons.Rounded.TableChart, Color(0xFF26A69A)),     // teal
    SLIDES(Icons.Rounded.Slideshow, Color(0xFFFF7043)),     // deep orange
    CODE(Icons.Rounded.Code, Color(0xFF5C6BC0)),            // indigo
    TEXT(Icons.Rounded.Article, Color(0xFF78909C)),         // blue-grey
    APK(Icons.Rounded.Android, Color(0xFF3DDC84)),          // android green
    FILE(Icons.Rounded.InsertDriveFile, Color(0xFF90A4AE)); // neutral
}

/** Resolves the [FileKind] for a UI file item (directories/archives first, then by extension). */
fun kindOf(item: FileItem): FileKind = when {
    item.isDirectory -> FileKind.FOLDER
    item.isArchive -> FileKind.ARCHIVE
    else -> kindForExtension(item.extension)
}

/** Resolves a [FileKind] from a bare name (used for archive-entry rows, which aren't real files). */
fun kindForName(name: String, isDirectory: Boolean): FileKind =
    if (isDirectory) FileKind.FOLDER else kindForExtension(name.substringAfterLast('.', "").lowercase())

private fun kindForExtension(ext: String): FileKind = when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "ico", "tiff" -> FileKind.IMAGE
    "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "m4v", "wmv", "mpeg", "mpg" -> FileKind.VIDEO
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "mid" -> FileKind.AUDIO
    "pdf" -> FileKind.PDF
    "doc", "docx", "odt", "rtf", "pages" -> FileKind.DOC
    "xls", "xlsx", "ods", "csv", "tsv", "numbers" -> FileKind.SHEET
    "ppt", "pptx", "odp", "key" -> FileKind.SLIDES
    "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "htm", "css", "c",
    "cpp", "h", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bash", "sql", "yaml", "yml",
    "toml", "gradle", "dart", "lua", "swift",
    // RetroArch shader presets/sources + playlists (.lpl is JSON) read as code.
    "glslp", "glsl", "slangp", "slang", "cgp", "cg", "lpl" -> FileKind.CODE
    "txt", "md", "markdown", "log", "ini", "cfg", "conf", "properties", "env",
    // RetroArch overrides/options/remaps/core-info/cheats are key=value text.
    "opt", "rmp", "info", "cht" -> FileKind.TEXT
    "apk" -> FileKind.APK
    else -> FileKind.FILE
}

/** Picks a rounded Material icon for a file based on its kind/extension. */
fun iconFor(item: FileItem): ImageVector = kindOf(item).icon

/** The accent colour for a file's kind (glyph tint / soft chip background). */
fun colorFor(item: FileItem): Color = kindOf(item).color

/**
 * A drawable override for well-known top-level public folders (e.g. Download → folder-with-arrow),
 * or null to use the plain [iconFor] glyph. Matched at the storage root only so an arbitrary nested
 * folder named "Download" stays a normal folder.
 */
fun specialFolderIconRes(item: FileItem): Int? {
    if (!item.isDirectory) return null
    val root = android.os.Environment.getExternalStorageDirectory().absolutePath
    if (item.file?.parent != root) return null
    return when (item.name) {
        "Download" -> com.android_explorer.R.drawable.ic_folder_download
        else -> null
    }
}
