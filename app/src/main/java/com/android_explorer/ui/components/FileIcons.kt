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
import androidx.compose.ui.graphics.vector.ImageVector
import com.android_explorer.data.FileItem

/** Picks a rounded Material icon for a file based on its kind/extension. */
fun iconFor(item: FileItem): ImageVector {
    if (item.isDirectory) return Icons.Rounded.Folder
    if (item.isArchive) return Icons.Rounded.FolderZip
    return when (item.extension) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> Icons.Rounded.Image
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv" -> Icons.Rounded.Movie
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> Icons.Rounded.MusicNote
        "pdf" -> Icons.Rounded.PictureAsPdf
        "doc", "docx", "odt", "rtf" -> Icons.Rounded.Article
        "txt", "md", "log" -> Icons.Rounded.Description
        "kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "c", "cpp", "sh" -> Icons.Rounded.Code
        "apk" -> Icons.Rounded.Android
        else -> Icons.Rounded.InsertDriveFile
    }
}
