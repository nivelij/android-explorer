package com.android_explorer.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.android_explorer.data.FileItem
import java.io.File

/**
 * Built-in image gallery (the "Image viewer" plugin). Opens the tapped image full-screen and lets the
 * user swipe through the **other images in the same folder** (sorted by name). Pinch / double-tap to
 * zoom; while zoomed, paging is suspended so drags pan the image instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(file: File, onClose: () -> Unit) {
    val images = remember(file.absolutePath) { siblingImages(file) }
    val startIndex = remember(file.absolutePath, images) {
        images.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }
    // Whether the currently-visible page is zoomed in — gates pager swiping so pans don't flip pages.
    var currentZoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { currentZoomed = false }

    BackHandler { onClose() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            images.getOrNull(pagerState.currentPage)?.name ?: file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (images.size > 1) {
                            Text(
                                "${pagerState.currentPage + 1} / ${images.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !currentZoomed,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
        ) { page ->
            ZoomableImage(
                file = images[page],
                onZoomChanged = { zoomed -> if (page == pagerState.currentPage) currentZoomed = zoomed },
            )
        }
    }
}

/** A single fit-to-screen image with pinch-zoom, pan (when zoomed), and double-tap to toggle zoom. */
@Composable
private fun ZoomableImage(file: File, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2.5f // centred zoom
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Custom transform loop so that at fit-scale a single-finger horizontal drag is left
                // UNCONSUMED for the pager to page on. We only consume when pinching (≥2 pointers) or
                // when already zoomed in (single-finger pan). detectTransformGestures can't do this —
                // it consumes every drag, which would trap the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (pressed >= 2) {
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            offset = clampOffset(offset + pan, newScale, size.width, size.height)
                            scale = newScale
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1f) {
                            offset = clampOffset(offset + pan, scale, size.width, size.height)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(file).crossfade(true).build(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** Keeps the panned/zoomed image within the viewport; at fit-scale it snaps back to centre. */
private fun clampOffset(raw: Offset, scale: Float, width: Int, height: Int): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

/** Image files in [file]'s folder (sorted by name), guaranteeing [file] itself is included. */
private fun siblingImages(file: File): List<File> {
    val parent = file.parentFile ?: return listOf(file)
    val imgs = parent.listFiles()?.asList().orEmpty()
        .filter { it.isFile && it.extension.lowercase() in FileItem.IMAGE_EXTENSIONS }
        .sortedBy { it.name.lowercase() }
    return when {
        imgs.isEmpty() -> listOf(file)
        imgs.any { it.absolutePath == file.absolutePath } -> imgs
        else -> (imgs + file).sortedBy { it.name.lowercase() }
    }
}
