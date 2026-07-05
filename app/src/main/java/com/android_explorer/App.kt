package com.android_explorer

import android.app.Application
import com.android_explorer.util.ThemeManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.init(this)
    }
}
