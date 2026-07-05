package com.android_explorer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_explorer.util.ThemeManager
import com.android_explorer.util.ThemeMode

/** Standalone overflow (⋮) menu offering the three theme choices. Used on the Home screen. */
@Composable
fun ThemeOverflowMenu() {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.Brightness6, contentDescription = "Theme")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMenuItems(onChosen = { expanded = false })
        }
    }
}

/** The three theme options as menu rows, for embedding inside an existing DropdownMenu. */
@Composable
fun ThemeMenuItems(onChosen: () -> Unit = {}) {
    val current by ThemeManager.mode.collectAsStateWithLifecycle()
    Text(
        "Theme",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    themeRow("System default", Icons.Rounded.SettingsBrightness, ThemeMode.SYSTEM, current, onChosen)
    themeRow("Light", Icons.Rounded.LightMode, ThemeMode.LIGHT, current, onChosen)
    themeRow("Dark", Icons.Rounded.DarkMode, ThemeMode.DARK, current, onChosen)
    themeRow("Black (OLED)", Icons.Rounded.Contrast, ThemeMode.OLED, current, onChosen)
}

@Composable
private fun themeRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    mode: ThemeMode,
    current: ThemeMode,
    onChosen: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = {
            if (mode == current) Icon(Icons.Rounded.Check, contentDescription = "Selected")
        },
        onClick = {
            ThemeManager.set(mode)
            onChosen()
        },
    )
}
