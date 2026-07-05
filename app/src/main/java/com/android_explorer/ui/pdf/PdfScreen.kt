package com.android_explorer.ui.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal built-in PDF reader: a vertical scroll of pages rendered fit-to-width via the framework's
 * [PdfRenderer] (no external dependency, fully offline). It's a page viewer — no text selection or
 * search — so anything more advanced can still "Open with" a full PDF app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(file: File, onClose: () -> Unit) {
    BackHandler { onClose() }
    // Opening can fail on password-protected or corrupt PDFs (SecurityException/IOException).
    val doc = remember(file) { runCatching { PdfDocument(file) }.getOrNull() }
    androidx.compose.runtime.DisposableEffect(doc) { onDispose { doc?.close() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (doc == null) {
                Text(
                    "Can't open this PDF (it may be password-protected or corrupt).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(doc.pageCount) { index ->
                            PdfPage(doc, index, widthPx)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, widthPx: Int) {
    // Re-render if the page or the target width (rotation) changes; produceState cancels on dispose.
    val bitmap by produceState<Bitmap?>(initialValue = null, doc, index, widthPx) {
        value = runCatching { doc.render(index, widthPx) }.getOrNull()
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .height(480.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

/**
 * Thin wrapper around [PdfRenderer]. Rendering is serialized with a [Mutex] because PdfRenderer
 * forbids having more than one page open at a time (LazyColumn may request several concurrently).
 */
private class PdfDocument(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    val pageCount: Int = renderer.pageCount
    private val mutex = Mutex()

    suspend fun render(index: Int, targetWidthPx: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val scale = targetWidthPx.toFloat() / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(targetWidthPx, height, Bitmap.Config.ARGB_8888)
                // PDFs assume a white page; without this, transparent areas render as black.
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}
