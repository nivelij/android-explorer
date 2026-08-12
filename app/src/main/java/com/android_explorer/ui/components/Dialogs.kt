package com.android_explorer.ui.components

import android.view.Gravity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // TextFieldValue rather than String so the caret can start at the end of the name — with a plain
    // String it lands at position 0 and typing prepends, which is exactly the "where am I typing?"
    // confusion this dialog is meant to avoid.
    var value by remember {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    val text = value.text
    val focus = remember { FocusRequester() }
    val submit = { if (text.isNotBlank()) onConfirm(text.trim()) }

    ImeSafeDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = submit) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { tier ->
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            // In the compact bar there is no separate title line, so the field's own label carries
            // it ("Rename") — a label costs no extra height, sitting inside the field's border.
            label = { Text(if (tier == DialogTier.COMPACT) title else label) },
            singleLine = true,
            // Done on the keyboard confirms, so the name can be committed without reaching for the
            // buttons. Load-bearing, not a nicety: under a keyboard that claims touch over the whole
            // screen (SwiftKey) the buttons cannot be tapped at all, so this is the only confirm path.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    // Focus (and so raise the keyboard) immediately: the layout then settles once, instead of
    // reflowing under the user when they tap the field.
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** How much vertical room the window shape gives [ImeSafeDialog] to work with. */
enum class DialogTier {
    /** Roomy (portrait): title, field and an action row stacked as usual. */
    FULL,

    /** Short (landscape on a handheld): one slim bar — field and actions on a single row. */
    COMPACT,
}

/**
 * A dialog for text entry that stays usable when the keyboard leaves very little room.
 *
 * Material 3's [AlertDialog] applies no IME insets and stacks its actions *below* the field, so the
 * action row is the first thing pushed off screen. A 7" 16:9 handheld in landscape leaves ~150dp
 * above the stock keyboard against ~230dp of dialog, and a third-party keyboard such as SwiftKey can
 * take ~4/5 of the screen, leaving ~80dp — so the buttons vanish and the field gets clipped.
 *
 * The fix has two halves, and **both** are needed.
 *
 * 1. Make the dialog *short* rather than fight the window system. In [DialogTier.COMPACT] the title
 *    folds into the field's own label (a label costs no extra height, sitting inside the border) and
 *    the actions move beside the field, giving a ~70dp bar, so field and buttons stand or fall
 *    together instead of the buttons being pushed off first.
 * 2. Anchor the window to the **top** of the screen. Do not rely on the platform moving the dialog out
 *    of the keyboard's way — that is device-dependent. Emulators shrink the dialog's parent frame by
 *    the IME (`INSET_PARENT_FRAME_BY_IME`) so a centred dialog always clears it, but a real Odin 2
 *    Portal on Android 13 does not: the dialog stays centred on the full screen and a keyboard
 *    covering the middle (SwiftKey in landscape starts at ~1/3 height) hides it *entirely* — scrim
 *    visible, dialog not.
 *    Being short made that worse, not better — a small centred dialog is fully swallowed where a tall
 *    one at least peeked out. Pinned to the top, visibility no longer depends on keyboard height or on
 *    whether the platform pans at all, since keyboards are anchored to the bottom.
 *
 * Top gravity buys *visibility*, not touchability: SwiftKey declares an IME window covering the whole
 * screen below the status bar and claims touch over all of it, so while it is up **no tap reaches this
 * dialog at any position** — the buttons render but are dead. That is why [ImeAction.Done] below is not
 * a convenience: on such a keyboard it is the only way to confirm. Verified on an Odin 2 Portal; see the
 * dialog gotcha in CLAUDE.md for how to check it.
 *
 * Deliberately uses an ordinary dialog window. Going full-screen (`usePlatformDefaultWidth = false`
 * plus `decorFitsSystemWindows = false`) to consume IME insets by hand was tried and is worse: such a
 * window is neither panned nor resized, it needs a hand-rolled scrim and tap handling, and it breaks
 * the IME — `showSoftInput()` is called but the keyboard never appears.
 */
@Composable
fun ImeSafeDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable (DialogTier) -> Unit,
) {
    // A handheld in landscape is ~410dp tall; a phone in portrait is 800dp+. Below this there is no
    // room for a stacked dialog alongside any keyboard, so collapse to the one-row bar. Deliberately
    // keyed off the window shape rather than the measured keyboard height: reading the IME inset
    // requires a full-screen dialog window, which breaks the IME (see the KDoc above).
    val tier = if (LocalConfiguration.current.screenHeightDp < 500) {
        DialogTier.COMPACT
    } else {
        DialogTier.FULL
    }
    val compact = tier == DialogTier.COMPACT
    Dialog(onDismissRequest = onDismissRequest) {
        // Sit at the top of the screen instead of centred, so the keyboard — which always comes up
        // from the bottom — can never cover the dialog, however tall it is (see the KDoc above).
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL) }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Transparent breathing room above the card: with TOP gravity the window starts flush
            // against the top of the content area, which would otherwise sit tight under the status bar.
            modifier = Modifier.widthIn(max = 560.dp).padding(top = if (compact) 12.dp else 32.dp),
        ) {
            Column(
                Modifier.padding(
                    horizontal = if (compact) 16.dp else 24.dp,
                    vertical = if (compact) 10.dp else 20.dp,
                ),
            ) {
                if (compact) {
                    // No title line: TextPromptDialog folds the title into the field's own label,
                    // and the actions sit level with the field so panning keeps them all visible.
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.weight(1f)) { content(tier) }
                        dismissButton?.invoke()
                        confirmButton()
                    }
                } else {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(16.dp))
                    content(tier)
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
