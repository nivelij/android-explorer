package com.android_explorer.data

/**
 * A device-wide media "category" surfaced as a home-screen shortcut. Unlike a folder, a category
 * aggregates every matching file across storage (backed by MediaStore), regardless of location.
 */
enum class MediaCategory(val title: String) {
    DOCUMENTS("Documents"),
    IMAGES("Pictures"),
    AUDIO("Music"),
    VIDEO("Video"),
}
