package com.android_explorer

import android.app.Application
import com.android_explorer.data.drive.DriveAuth
import com.android_explorer.data.drive.DriveRepository
import com.android_explorer.util.PluginManager
import com.android_explorer.util.ThemeManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.init(this)
        PluginManager.init(this)
        DriveAuth.init(this)
        // Purge the transient Drive view-cache from previous sessions so it can't accumulate.
        val ctx = this
        Thread { DriveRepository().clearCache(ctx) }.start()
    }
}
