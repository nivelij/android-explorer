package com.android_explorer.ui.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// Live pinch can zoom up to this; page bitmaps are only re-rendered sharp up to MAX_RENDER_SCALE
// (beyond that the highest-res bitmap is magnified). MAX_RENDER_WIDTH_PX caps per-page bitmap memory.
private const val MAX_GESTURE_SCALE = 5f
private const val MAX_RENDER_SCALE = 3f
private const val MAX_RENDER_WIDTH_PX = 2400
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Minimal built-in PDF reader: a vertical scroll of pages rendered fit-to-width via the framework's
 * [PdfRenderer] (no external dependency, fully offline). Supports pinch-to-zoom (up to 5x), drag-to-pan
 * while zoomed, and double-tap to toggle zoom; visible pages are re-rendered at the settled zoom level
 * so text stays crisp instead of just being magnified. It's a page viewer — no text selection/search —
 * so anything more advanced can still "Open with" a full PDF app.
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
                // gestureScale = live zoom (graphicsLayer). renderScale = bitmap resolution, which trails
                // gestureScale after the pinch settles so re-rendering doesn't thrash mid-gesture.
                // Keyed on doc so opening another PDF starts fresh at 1x.
                var gestureScale by remember(doc) { mutableFloatStateOf(1f) }
                var offsetX by remember(doc) { mutableFloatStateOf(0f) }
                var renderScale by remember(doc) { mutableFloatStateOf(1f) }
                val lazyState = rememberLazyListState()

                // Debounce: once the pinch pauses, bump the render resolution to match the zoom.
                LaunchedEffect(gestureScale) {
                    delay(150)
                    renderScale = gestureScale.coerceIn(1f, MAX_RENDER_SCALE)
                }

                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { tap ->
                                    if (gestureScale > 1f) {
                                        gestureScale = 1f
                                        offsetX = 0f
                                    } else {
                                        // Zoom in centered on the tapped point (same anchoring as pinch).
                                        offsetX = (tap.x - tap.x * DOUBLE_TAP_SCALE)
                                            .coerceIn(-size.width * (DOUBLE_TAP_SCALE - 1f), 0f)
                                        lazyState.dispatchRawDelta(tap.y * (1f - 1f / DOUBLE_TAP_SCALE))
                                        gestureScale = DOUBLE_TAP_SCALE
                                    }
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            // Run on the Initial pass so we can pre-empt the LazyColumn's own scroll —
                            // but only when actually zooming/panning, and only consume moves. That
                            // leaves single-finger drags at 1x untouched, so the list keeps native
                            // scroll + fling, and taps (no move) still reach the double-tap detector.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.count { it.pressed }
                                    if (gestureScale > 1f || pressed >= 2) {
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val focal = event.calculateCentroid(useCurrent = true)
                                        val cx = if (focal.isSpecified) focal.x else size.width / 2f
                                        val cy = if (focal.isSpecified) focal.y else size.height / 2f
                                        val oldScale = gestureScale
                                        val newScale = (oldScale * zoom).coerceIn(1f, MAX_GESTURE_SCALE)

                                        // Horizontal = translationX. Keep the point under the fingers
                                        // fixed as scale changes (screenX = contentX*scale + offsetX,
                                        // top-left origin), then apply the finger pan.
                                        offsetX =
                                            if (newScale <= 1f) 0f
                                            else (cx - (cx - offsetX) * (newScale / oldScale) + pan.x)
                                                .coerceIn(-size.width * (newScale - 1f), 0f)
                                        gestureScale = newScale

                                        // Vertical = the LazyColumn's own scroll (so paging still works
                                        // while zoomed). Zoom-about-focal + finger pan, both driven
                                        // synchronously via dispatchRawDelta — no per-event coroutine, so
                                        // no scroll-lock contention (the old scrollBy launch barely moved).
                                        val focalScroll =
                                            if (oldScale != newScale) cy * (1f / oldScale - 1f / newScale) else 0f
                                        val scrollDelta = focalScroll - pan.y / newScale
                                        if (scrollDelta != 0f) lazyState.dispatchRawDelta(scrollDelta)

                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                ) {
                    val baseWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                    LazyColumn(
                        state = lazyState,
                        userScrollEnabled = gestureScale <= 1f,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = gestureScale
                                scaleY = gestureScale
                                translationX = offsetX
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                    ) {
                        items(doc.pageCount) { index ->
                            PdfPage(doc, index, baseWidthPx, renderScale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, baseWidthPx: Int, renderScale: Float) {
    // Render at the zoomed resolution (capped) so zoomed-in text is crisp, not a magnified blur.
    val renderWidthPx = (baseWidthPx * renderScale).roundToInt()
        .coerceIn(1, MAX_RENDER_WIDTH_PX)
    // Re-render if the page or the target width (rotation / zoom) changes; produceState cancels on dispose.
    val bitmap by produceState<Bitmap?>(initialValue = null, doc, index, renderWidthPx) {
        value = runCatching { doc.render(index, renderWidthPx) }.getOrNull()
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
