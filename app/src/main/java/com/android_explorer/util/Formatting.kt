package com.android_explorer.util

import java.text.DecimalFormat
import java.util.Date
import java.util.concurrent.TimeUnit

object Formatting {
    private val df = DecimalFormat("#.#")

    /** Human-readable size, e.g. 1.5 GB. Uses binary (1024) units. */
    fun bytes(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var value = size.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "${df.format(value)} ${units[unit]}"
    }

    /** Relative-ish timestamp for lists: "just now", "3h ago", or a date. */
    fun relativeTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val now = System.currentTimeMillis()
        val diff = now - epochMillis
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
            else -> android.text.format.DateFormat.format("dd MMM yyyy", Date(epochMillis)).toString()
        }
    }

    /** Full timestamp for the details popup, e.g. "05 Jul 2026, 17:58". */
    fun dateTime(epochMillis: Long): String =
        if (epochMillis <= 0) "—"
        else android.text.format.DateFormat.format("dd MMM yyyy, HH:mm", Date(epochMillis)).toString()
}
