package com.android_explorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val submit = { if (text.isNotBlank()) onConfirm(text.trim()) }

    ImeSafeDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = submit) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            // Done on the keyboard confirms, so the name can be committed without reaching for the
            // buttons — the difference between usable and not on a short landscape window.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    // Focus (and so raise the keyboard) immediately: the layout then settles once, instead of
    // reflowing under the user when they tap the field.
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * An AlertDialog-shaped dialog that stays fully usable while the keyboard is open.
 *
 * Material 3's [AlertDialog] does not apply IME insets, and its action row is the first thing to be
 * pushed off screen. On a short window — a 7" 16:9 handheld in landscape leaves roughly 150dp above
 * the keyboard, against ~230dp of dialog — the buttons vanish and the field is clipped, so there is
 * no way to see what you are typing or to confirm it.
 *
 * Two things make it survive:
 *
 * 1. **A compact layout on short windows.** When the window is too short for a stacked dialog, the
 *    actions move beside the field instead of below it. This is what actually guarantees usability:
 *    the platform may *pan* the window rather than resize it, and panning is defined to keep the
 *    **focused** view on screen — so anything level with the field stays visible, while anything far
 *    below it does not. A tall dialog loses its action row for exactly that reason.
 * 2. Opting out of decor fitting so the IME inset reaches the content, then [safeDrawingPadding]
 *    to keep the card clear of the keyboard and the system bars.
 */
@Composable
fun ImeSafeDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // A 7" 16:9 handheld in landscape is ~411dp tall; a phone in portrait is ~800dp+. Below this,
    // a stacked title/field/actions dialog cannot coexist with the keyboard.
    val compact = LocalConfiguration.current.screenHeightDp < 500
    Dialog(
        onDismissRequest = onDismissRequest,
        // decorFitsSystemWindows = false is required, not cosmetic: this dialog's window fills the
        // screen, and a full-screen window is neither panned nor resized for the keyboard. Opting out
        // of decor fitting is what makes the IME inset get dispatched to the content at all — with it
        // on, imePadding() measures zero and the card sits behind the keyboard.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The window now spans the screen, so "tap outside" is our job, not the platform's.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                )
                // safeDrawing = system bars + cutout + IME, so the card clears all of them.
                .safeDrawingPadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    // Swallow taps on the card so they don't reach the dismiss handler above.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = if (compact) 16.dp else 24.dp,
                            vertical = if (compact) 12.dp else 20.dp,
                        ),
                ) {
                    Text(
                        title,
                        style = if (compact) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                    )
                    Spacer(Modifier.size(if (compact) 8.dp else 16.dp))
                    if (compact) {
                        // Actions sit level with the field, so a panned window keeps them on screen
                        // together with it.
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.weight(1f)) { content() }
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    } else {
                        content()
                        Spacer(Modifier.size(20.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A small, non-dismissable dialog with a spinner and a message, for a blocking operation in progress. */
@Composable
fun ProgressDialog(message: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Spacer(Modifier.size(20.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun ArchiveActionDialog(
    name: String,
    onDismiss: () -> Unit,
    onViewContents: () -> Unit,
    onExtractHere: () -> Unit,
    onExtractTo: () -> Unit,
    onOpen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                ActionRow(Icons.Rounded.Visibility, "View contents", onViewContents)
                ActionRow(Icons.Rounded.Unarchive, "Extract here", onExtractHere)
                ActionRow(Icons.Rounded.FolderOpen, "Extract to folder…", onExtractTo)
                ActionRow(Icons.Rounded.OpenInNew, "Open with…", onOpen)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
