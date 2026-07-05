package com.android_explorer.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

/** App-wide theme preference, persisted in SharedPreferences and observed by the Compose theme. */
object ThemeManager {
    private const val PREFS = "android_explorer_settings"
    private const val KEY = "theme_mode"

    private lateinit var prefs: SharedPreferences
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mode.value = runCatching { ThemeMode.valueOf(prefs.getString(KEY, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(mode: ThemeMode) {
        _mode.value = mode
        if (::prefs.isInitialized) prefs.edit().putString(KEY, mode.name).apply()
    }
}
