package com.android_explorer.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android_explorer.ui.components.TextPromptDialog
import com.android_explorer.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var currentFile by remember { mutableStateOf(file) }
    var content by remember { mutableStateOf(TextFieldValue("")) }
    var originalText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    var showFind by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var showSaveAs by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    val dirty = content.text != originalText

    LaunchedEffect(file.absolutePath) {
        loading = true
        val text = withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrDefault("")
        }
        content = TextFieldValue(text)
        originalText = text
        loading = false
    }

    val density = LocalDensity.current
    val lineHeightSp = 24.sp
    val lineHeightPx = with(density) { lineHeightSp.toPx() }
    val vScroll = rememberScrollState()

    fun save(target: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { target.writeText(content.text) }.isSuccess
            }
            if (ok) {
                currentFile = target
                originalText = content.text
                Toast.makeText(context, "Saved ${target.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun copySelection(cut: Boolean) {
        val sel = content.selection
        if (sel.collapsed) return
        clipboard.setText(AnnotatedString(content.text.substring(sel.min, sel.max)))
        if (cut) {
            val newText = content.text.replaceRange(sel.min, sel.max, "")
            content = TextFieldValue(newText, TextRange(sel.min))
        }
    }

    fun paste() {
        val clip = clipboard.getText()?.text ?: return
        val sel = content.selection
        val newText = content.text.replaceRange(sel.min, sel.max, clip)
        content = TextFieldValue(newText, TextRange(sel.min + clip.length))
    }

    fun findFrom(start: Int, forward: Boolean) {
        if (query.isEmpty()) return
        val hay = if (caseSensitive) content.text else content.text.lowercase()
        val needle = if (caseSensitive) query else query.lowercase()
        val idx = if (forward) {
            hay.indexOf(needle, start).let { if (it >= 0) it else hay.indexOf(needle, 0) }
        } else {
            hay.lastIndexOf(needle, (start - 1).coerceAtLeast(0)).let { if (it >= 0) it else hay.lastIndexOf(needle) }
        }
        if (idx >= 0) {
            content = content.copy(selection = TextRange(idx, idx + needle.length))
            val line = content.text.substring(0, idx).count { it == '\n' }
            scope.launch { vScroll.animateScrollTo((line * lineHeightPx).toInt()) }
        } else {
            Toast.makeText(context, "Not found", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler { if (dirty) confirmClose = true else onClose() }

    Scaffold(
        topBar = {
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmClose = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                    }
                },
                title = {
                    Text(
                        (if (dirty) "• " else "") + currentFile.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { save(currentFile) }) {
                        Icon(Icons.Rounded.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = { showFind = !showFind }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Find")
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Save as…") },
                            leadingIcon = { Icon(Icons.Rounded.SaveAs, null) },
                            onClick = { showSaveAs = true; menu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = { copySelection(cut = false); menu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Cut") },
                            leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
                            onClick = { copySelection(cut = true); menu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Paste") },
                            leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                            onClick = { paste(); menu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Close") },
                            leadingIcon = { Icon(Icons.Rounded.Close, null) },
                            onClick = { menu = false; if (dirty) confirmClose = true else onClose() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            if (showFind) {
                FindBar(
                    query = query,
                    onQuery = { query = it },
                    caseSensitive = caseSensitive,
                    onToggleCase = { caseSensitive = !caseSensitive },
                    onNext = { findFrom(content.selection.max, forward = true) },
                    onPrev = { findFrom(content.selection.min, forward = false) },
                    onClose = { showFind = false },
                )
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val lineCount = content.text.count { it == '\n' } + 1
                val editorStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = lineHeightSp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val gutterStyle = editorStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                val gutterWidth = (lineCount.toString().length.coerceAtLeast(2) * 10 + 20).dp

                Column(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                    Row(Modifier.fillMaxWidth()) {
                        // Line-number gutter as ONE multi-line Text so its line spacing
                        // matches the editor's line-for-line (single-line Texts ignore lineHeight).
                        Text(
                            text = (1..lineCount).joinToString("\n"),
                            style = gutterStyle,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .width(gutterWidth)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(end = 8.dp, start = 4.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            BasicTextField(
                                value = content,
                                onValueChange = { content = it },
                                textStyle = editorStyle,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions.Default,
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 240.dp)
                                    .padding(end = 16.dp, bottom = 24.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSaveAs) {
        TextPromptDialog(
            title = "Save as",
            label = "File name",
            initial = currentFile.name,
            onDismiss = { showSaveAs = false },
            onConfirm = {
                save(File(currentFile.parentFile, it))
                showSaveAs = false
            },
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "You have unsaved changes in ${currentFile.name}.",
            confirmLabel = "Discard",
            onDismiss = { confirmClose = false },
            onConfirm = { confirmClose = false; onClose() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    caseSensitive: Boolean,
    onToggleCase: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Find") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(4.dp))
        FilterChip(
            selected = caseSensitive,
            onClick = onToggleCase,
            label = { Text("Aa") },
        )
        IconButton(onClick = onPrev) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous") }
        IconButton(onClick = onNext) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next") }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close find") }
    }
}
