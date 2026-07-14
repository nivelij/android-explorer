package com.android_explorer.ui.drive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android_explorer.data.VolumeStat
import com.android_explorer.data.drive.DriveAuth
import com.android_explorer.data.drive.DriveRepository
import com.android_explorer.ui.components.StorageMeter
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Returns a lambda that runs the interactive Drive authorization: it asks the Authorization API for
 * the Drive scope, launches the consent UI if required, and on success records the connected account.
 * Once consent is granted, subsequent token fetches (in [DriveRepository]/[DriveAuth]) are silent.
 */
@Composable
fun rememberDriveConnector(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            runCatching { DriveAuth.resultFromIntent(context, data) }
            scope.launch { DriveAuth.setConnected(DriveRepository().accountEmail(context) ?: "Google Drive") }
        }
    }
    return connect@{
        val activity = context.findActivity() ?: return@connect
        Identity.getAuthorizationClient(activity)
            .authorize(DriveAuth.authorizationRequest())
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    scope.launch { DriveAuth.setConnected(DriveRepository().accountEmail(context) ?: "Google Drive") }
                }
            }
            .addOnFailureListener { /* user can retry; a transient failure is non-fatal */ }
    }
}

/**
 * Home-screen "Google Drive" section. Three states:
 * - **Unsupported** (uncertified Google Play services): a disabled card explaining why — the
 *   Authorization API can't run, so sign-in is not offered.
 * - **Disconnected**: a single Connect card.
 * - **Connected**: the same storage-usage card as local storage (Drive quota via [StorageMeter])
 *   with the account + a disconnect action; tapping the card browses Drive.
 */
@Composable
fun DriveSection(onOpenDrive: () -> Unit, modifier: Modifier = Modifier) {
    val account by DriveAuth.account.collectAsStateWithLifecycle()
    val connect = rememberDriveConnector()
    val context = LocalContext.current
    // Certification is a property of the device/GMS install, so probe once per composition.
    val supported = remember { DriveAuth.isSupported(context) }

    Column(modifier) {
        Text(
            "Google Drive",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(12.dp))

        when {
            !supported -> UnsupportedCard()
            account == null -> ConnectCard(onConnect = connect)
            else -> {
                // Tri-state: null quota + !failed = still loading; quota set = OK; failed = the fetch
                // completed but errored (token revoked/expired). The failed branch offers Reconnect so
                // a dead session isn't a permanent "Checking storage…" dead end.
                var quota by remember { mutableStateOf<VolumeStat?>(null) }
                var failed by remember { mutableStateOf(false) }
                LaunchedEffect(account) {
                    quota = null
                    failed = false
                    val q = runCatching { DriveRepository().storageQuota(context) }.getOrNull()
                    if (q != null) quota = q else failed = true
                }
                Card(
                    // Tap the card to browse Drive (the standalone button was removed to save space).
                    onClick = onOpenDrive,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    elevation = CardDefaults.cardElevation(0.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val q = quota
                        when {
                            q != null -> StorageMeter(q)
                            failed -> Text(
                                "Couldn't reach Google Drive — your access may have expired. Reconnect to sign in again.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Text(
                                "Checking storage…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (failed) Icons.Rounded.CloudOff else Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                account.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // Reconnect clears the session (null→email) then relaunches sign-in, so the
                            // quota LaunchedEffect re-runs on success even if the same account returns.
                            if (failed) {
                                TextButton(onClick = { DriveAuth.disconnect(); connect() }) { Text("Reconnect") }
                            }
                            TextButton(onClick = { DriveAuth.disconnect() }) { Text("Disconnect") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectCard(onConnect: () -> Unit) {
    Card(
        onClick = onConnect,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Rounded.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Connect Google Drive",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Browse and upload to your Drive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shown when the device lacks certified Google Play services. The card is non-interactive and
 * explains why Drive sign-in isn't available, so the disabled state isn't mistaken for a bug.
 */
@Composable
private fun UnsupportedCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Drive unavailable on this device",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Sign-in needs certified Google Play services, which this device doesn't have.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
