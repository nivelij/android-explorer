package com.android_explorer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android_explorer.data.VolumeStat
import com.android_explorer.util.Formatting

/** A compact card-less storage row: label + total, a rounded usage bar, then used/free beneath it. */
@Composable
fun StorageMeter(volume: VolumeStat, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(targetValue = volume.usedFraction, label = "storage")

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    volume.name.startsWith("Internal") -> Icons.Rounded.Smartphone
                    volume.name.contains("Drive") -> Icons.Rounded.Cloud
                    else -> Icons.Rounded.SdCard
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = volume.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${Formatting.bytes(volume.totalBytes)} total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.size(10.dp))

        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            // A faint tint of the accent, not surfaceVariant: on OLED the card fill and surfaceVariant
            // are both near-black, so the unfilled track collapsed and the free-space portion vanished.
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
            color = MaterialTheme.colorScheme.primary,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            // One continuous bar: M3's default gap + end dot split it into what reads as two
            // separate bars, so the labels below seemed to describe the wrong one.
            gapSize = 0.dp,
            drawStopIndicator = {},
        )

        Spacer(Modifier.size(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Each figure sits under the part of the bar it measures: used under the fill, free under
            // the empty track.
            Text(
                text = "${Formatting.bytes(volume.usedBytes)} used",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${Formatting.bytes(volume.freeBytes)} free",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
