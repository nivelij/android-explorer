package com.android_explorer.util

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object Wallpaper {

    /**
     * Sets an image file as the wallpaper using Android's own crop-and-set screen
     * ([WallpaperManager.getCropAndSetWallpaperIntent]). Some devices/emulators don't support that
     * intent, so we fall back to the system "Set as" chooser ([Intent.ACTION_ATTACH_DATA]).
     */
    fun setAsWallpaper(context: Context, file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: run {
            Toast.makeText(context, "Can't set wallpaper", Toast.LENGTH_SHORT).show()
            return
        }

        // Preferred: the built-in wallpaper cropper.
        try {
            val intent = WallpaperManager.getInstance(context)
                .getCropAndSetWallpaperIntent(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Device doesn't support the crop intent — fall through to the generic chooser.
        }

        // Fallback: "Set as" via any app that handles image attach (gallery/wallpaper apps).
        try {
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "image/*")
                putExtra("mimeType", "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, "Set as").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Can't set wallpaper: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
