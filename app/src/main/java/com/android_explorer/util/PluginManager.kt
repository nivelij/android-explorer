package com.android_explorer.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Toggles for the built-in file "plugins" (viewers). When a plugin is on, its file type opens in
 * the app's own screen; when off, the file is handed to the system "open with" chooser instead.
 *
 * Persisted in SharedPreferences (same store as [ThemeManager]) and exposed as StateFlows so both
 * the settings UI and the open-resolver react to changes.
 */
object PluginManager {
    private const val PREFS = "android_explorer_settings"
    private const val KEY_EDITOR = "plugin_text_editor"
    private const val KEY_PDF = "plugin_pdf_reader"
    private const val KEY_IMAGE = "plugin_image_viewer"

    private lateinit var prefs: SharedPreferences

    private val _textEditor = MutableStateFlow(true)
    val textEditor: StateFlow<Boolean> = _textEditor.asStateFlow()

    private val _pdfReader = MutableStateFlow(true)
    val pdfReader: StateFlow<Boolean> = _pdfReader.asStateFlow()

    private val _imageViewer = MutableStateFlow(true)
    val imageViewer: StateFlow<Boolean> = _imageViewer.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _textEditor.value = prefs.getBoolean(KEY_EDITOR, true)
        _pdfReader.value = prefs.getBoolean(KEY_PDF, true)
        _imageViewer.value = prefs.getBoolean(KEY_IMAGE, true)
    }

    /** Non-flow reads for the open-resolver (safe before [init]: default to enabled). */
    val textEditorEnabled: Boolean get() = _textEditor.value
    val pdfReaderEnabled: Boolean get() = _pdfReader.value
    val imageViewerEnabled: Boolean get() = _imageViewer.value

    fun setTextEditor(enabled: Boolean) {
        _textEditor.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_EDITOR, enabled).apply()
    }

    fun setPdfReader(enabled: Boolean) {
        _pdfReader.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_PDF, enabled).apply()
    }

    fun setImageViewer(enabled: Boolean) {
        _imageViewer.value = enabled
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_IMAGE, enabled).apply()
    }
}
