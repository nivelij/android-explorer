package com.android_explorer.data

/** Rich metadata for the Details popup. */
data class FileDetails(
    val name: String,
    val path: String,
    val type: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val itemCount: Int?,        // direct children (folders only), null for files
    val createdMillis: Long,
    val modifiedMillis: Long,
    val accessedMillis: Long,
    val readable: Boolean,
    val writable: Boolean,
    val hidden: Boolean,
)
