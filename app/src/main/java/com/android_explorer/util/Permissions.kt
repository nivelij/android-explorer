package com.android_explorer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

object Permissions {

    /** True when the user has granted "All files access" (MANAGE_EXTERNAL_STORAGE). */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Deep-links to this app's All-files-access toggle in system Settings. */
    fun allFilesAccessIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /** POST_NOTIFICATIONS is only a runtime permission on Android 13+. */
    val needsNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
