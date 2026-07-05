package com.android_explorer.archive

import java.io.File
import java.util.Locale

/** Archive formats the app understands. */
enum class ArchiveType(val label: String, val canCreate: Boolean) {
    ZIP("ZIP", true),
    SEVEN_Z("7z", true),
    RAR("RAR", false),
    TAR("TAR", true),
    TAR_GZ("TAR.GZ", true),
    TAR_BZ2("TAR.BZ2", true),
    TAR_XZ("TAR.XZ", true),
    GZ("GZIP", false),
    BZ2("BZIP2", false),
    XZ("XZ", false);

    companion object {
        /** Best-effort detection by filename; extraction also sniffs magic bytes as a fallback. */
        fun fromFileName(name: String): ArchiveType? {
            val n = name.lowercase(Locale.ROOT)
            return when {
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> TAR_BZ2
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> TAR_XZ
                n.endsWith(".zip") -> ZIP
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".rar") -> RAR
                n.endsWith(".tar") -> TAR
                n.endsWith(".gz") -> GZ
                n.endsWith(".bz2") -> BZ2
                n.endsWith(".xz") -> XZ
                else -> null
            }
        }

        fun isArchive(file: File): Boolean =
            file.isFile && fromFileName(file.name) != null
    }
}
