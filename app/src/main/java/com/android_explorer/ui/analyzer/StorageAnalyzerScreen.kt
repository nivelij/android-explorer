package com.android_explorer.ui.analyzer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android_explorer.data.FileItem
import com.android_explorer.ui.components.iconFor
import com.android_explorer.util.Formatting
import java.io.File

// The analyzer is a *magnitude ranking*, not a set of categories — so colour does one job: a single
// accent (the theme primary) on the bars, with length + order carrying the ranking. Icons stay neutral
// and identity rests on the glyph shape + name. The breakdown bar shows its top consumers as steps of
// that one hue (fading by rank) so segments separate without a rainbow.
private const val TOP_N = 5

/**
 * Disk-usage analyzer: a drill-down where a segmented **breakdown bar** gives the folder's split at a
 * glance and a ranked list details each child (folder or file) with a proportion bar. Segment colours
 * link the overview bar to its rows. Tapping a folder drills in; a file routes through [onOpenFile].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    onExit: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    // The volume/root this session is bounded to (navigateUp stops here). Defaults to internal.
    rootDir: File? = null,
    // Title shown at the root (e.g. "Internal storage").
    rootLabel: String? = null,
    viewModel: StorageAnalyzerViewModel = viewModel(),
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val bound = rootDir ?: File(android.os.Environment.getExternalStorageDirectory().absolutePath)
        viewModel.openAt(bound, bound)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler { if (!viewModel.navigateUp()) onExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.navigateUp()) onExit() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up")
                    }
                },
                title = {
                    Column {
                        Text(
                            if (viewModel.canGoUp) state.dir.name else (rootLabel ?: "Internal storage"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (state.scanning) "Scanning ${state.scanned}/${state.total}…"
                            else "${Formatting.bytes(state.totalSize)}  •  ${state.rows.size} items",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleSort) {
                        Icon(
                            if (state.ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                            contentDescription = if (state.ascending) "Smallest first" else "Largest first",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.scanning -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.total > 0) {
                        CircularProgressIndicator(progress = { state.scanned.toFloat() / state.total })
                    } else {
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "Measuring ${state.scanned} of ${state.total}…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.rows.isEmpty() -> Text(
                    "Empty folder",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val bySize = state.rows.sortedByDescending { it.size }
                    val total = state.totalSize.coerceAtLeast(1L)
                    val maxSize = bySize.first().size.coerceAtLeast(1L)

                    Column(Modifier.fillMaxSize()) {
                        BreakdownBar(
                            top = bySize.take(TOP_N),
                            otherBytes = bySize.drop(TOP_N).sumOf { it.size },
                            total = total,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                        LazyColumn(
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.rows, key = { it.item.path }) { row ->
                                AnalyzerCard(
                                    row = row,
                                    barFraction = row.size.toFloat() / maxSize,
                                    sharePercent = row.size * 100.0 / total,
                                    onClick = {
                                        if (row.item.isDirectory) viewModel.open(row.item) else onOpenFile(row.item)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The at-a-glance split: one rounded track, a segment per top consumer + a neutral "Other". Segments
 * are one hue (the theme primary) faded by rank so they separate without introducing new colours.
 */
@Composable
private fun BreakdownBar(
    top: List<AnalyzerRow>,
    otherBytes: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        top.forEachIndexed { i, r ->
            // Floor the weight so a tiny-but-present top consumer still shows a sliver.
            val w = (r.size.toFloat() / total).coerceIn(0.01f, 1f)
            Box(Modifier.weight(w).fillMaxHeight().background(primary.copy(alpha = (1f - i * 0.15f).coerceAtLeast(0.4f))))
        }
        if (otherBytes > 0) {
            Box(
                Modifier
                    .weight((otherBytes.toFloat() / total).coerceIn(0.01f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun AnalyzerCard(
    row: AnalyzerRow,
    barFraction: Float,
    sharePercent: Double,
    onClick: () -> Unit,
) {
    // Solid container + hairline outline so the card reads on every theme, OLED included.
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Neutral tile — identity is the glyph shape + name; colour is reserved for the bar.
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            iconFor(row.item),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                Text(
                    row.item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    Formatting.bytes(row.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProportionBar(
                    fraction = barFraction,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).height(8.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    "${percent(sharePercent)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A flat, rounded proportion bar (no Material loading-indicator look): tinted track + accent fill. */
@Composable
private fun ProportionBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

/** Compact share label: "<1" below 1%, else a whole number. */
private fun percent(value: Double): String = when {
    value <= 0 -> "0"
    value < 1 -> "<1"
    else -> value.toInt().toString()
}
