package com.android_explorer.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/** Stats for one mounted volume. */
data class VolumeStat(
    val name: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) else 0f
}

class StorageRepository(private val context: Context) {

    /** Primary shared storage first, then any additional mounted volumes (SD card, USB). */
    fun volumes(): List<VolumeStat> {
        val result = mutableListOf<VolumeStat>()
        val primary = Environment.getExternalStorageDirectory()
        primary?.let { result += stat("Internal storage", it) }

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        sm?.storageVolumes?.forEach { volume ->
            if (volume.isPrimary) return@forEach
            val dir = volumeDirectory(volume)
            if (dir != null && dir.exists()) {
                val label = volume.getDescription(context) ?: "Storage"
                result += stat(label, dir)
            }
        }
        return result
    }

    private fun volumeDirectory(volume: android.os.storage.StorageVolume): File? {
        // API 30+ exposes the directory directly.
        volume.directory?.let { return it }
        return null
    }

    private fun stat(name: String, dir: File): VolumeStat {
        return try {
            val fs = StatFs(dir.absolutePath)
            val total = fs.blockCountLong * fs.blockSizeLong
            val free = fs.availableBlocksLong * fs.blockSizeLong
            VolumeStat(name, dir.absolutePath, total, free)
        } catch (_: Exception) {
            VolumeStat(name, dir.absolutePath, 0, 0)
        }
    }
}
