package com.android_explorer.util

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileOpener {

    /** Opens a file with the user's default app via a shared FileProvider URI. */
    fun open(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = mimeType(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches the system package installer for an .apk. Uses the explicit
     * `application/vnd.android.package-archive` MIME (MimeTypeMap has no "apk" mapping, so the
     * generic [open] path resolves to a wildcard type and the installer isn't offered) and skips
     * the chooser so the OS installer handles it directly. Needs the REQUEST_INSTALL_PACKAGES perm;
     * the user still grants "install unknown apps" for this app on first use.
     */
    fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open installer: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Shares a file via the native Android share sheet (ACTION_SEND) with a FileProvider URI. */
    fun share(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Can't share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeType(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}
