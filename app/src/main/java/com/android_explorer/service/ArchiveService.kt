package com.android_explorer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android_explorer.MainActivity
import com.android_explorer.R
import com.android_explorer.archive.ArchiveListener
import com.android_explorer.archive.ArchiveManager
import com.android_explorer.archive.ArchiveProgress
import com.android_explorer.archive.ArchiveProgressBus
import com.android_explorer.archive.ArchiveResult
import com.android_explorer.archive.ArchiveType
import com.android_explorer.util.Formatting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs one extract/compress job at a time as a foreground service. Progress is mirrored to a
 * notification (with a progress bar) and to [ArchiveProgressBus] for the in-app dialog.
 */
class ArchiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                ArchiveProgressBus.requestCancel()
                return START_NOT_STICKY
            }
            ACTION_EXTRACT -> startExtract(intent)
            ACTION_COMPRESS -> startCompress(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startExtract(intent: Intent) {
        val archive = File(intent.getStringExtra(EXTRA_ARCHIVE) ?: return stopSelf())
        val destDir = File(intent.getStringExtra(EXTRA_DEST) ?: return stopSelf())
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val jobId = SystemClock.elapsedRealtime()
        val title = "Extracting ${archive.name}"
        beginForeground(
            ArchiveProgress(
                jobId, ArchiveProgress.Kind.EXTRACT, title,
                sourcePath = archive.absolutePath, destPath = destDir.absolutePath,
            ),
        )
        scope.launch {
            val listener = busListener(jobId, ArchiveProgress.Kind.EXTRACT, title)
            val result = ArchiveManager.extract(archive, destDir, listener, password)
            finish(jobId, ArchiveProgress.Kind.EXTRACT, archive.name, archive.absolutePath, destDir.absolutePath, result)
        }
    }

    private fun startCompress(intent: Intent) {
        val sources = intent.getStringArrayListExtra(EXTRA_SOURCES)?.map { File(it) } ?: return stopSelf()
        val dest = File(intent.getStringExtra(EXTRA_DEST) ?: return stopSelf())
        val type = runCatching { ArchiveType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }
            .getOrDefault(ArchiveType.ZIP)
        val jobId = SystemClock.elapsedRealtime()
        val initial = ArchiveProgress(jobId, ArchiveProgress.Kind.COMPRESS, "Compressing ${dest.name}")
        beginForeground(initial)
        scope.launch {
            val listener = busListener(jobId, ArchiveProgress.Kind.COMPRESS, "Compressing ${dest.name}")
            val result = ArchiveManager.compress(sources, dest, type, listener)
            finish(jobId, ArchiveProgress.Kind.COMPRESS, dest.name, dest.absolutePath, dest.absolutePath, result)
        }
    }

    private fun busListener(jobId: Long, kind: ArchiveProgress.Kind, title: String) = object : ArchiveListener {
        override fun onProgress(entry: String, entriesDone: Int, entriesTotal: Int, bytesDone: Long, bytesTotal: Long) {
            val snapshot = ArchiveProgress(
                jobId = jobId,
                kind = kind,
                title = title,
                currentEntry = entry,
                processedEntries = entriesDone,
                totalEntries = entriesTotal,
                processedBytes = bytesDone,
                totalBytes = bytesTotal,
                state = ArchiveProgress.State.RUNNING,
            )
            ArchiveProgressBus.update(snapshot)
            maybeNotify(snapshot)
        }

        override fun isCancelled(): Boolean = ArchiveProgressBus.isCancelled()
    }

    private fun finish(
        jobId: Long,
        kind: ArchiveProgress.Kind,
        name: String,
        sourcePath: String,
        destPath: String,
        result: ArchiveResult,
    ) {
        val done = when (result) {
            is ArchiveResult.Success -> ArchiveProgress(
                jobId, kind, name, state = ArchiveProgress.State.SUCCESS, outputPath = destPath,
                processedEntries = result.entries,
            )
            is ArchiveResult.Failure -> ArchiveProgress(
                jobId, kind, name, state = ArchiveProgress.State.ERROR, error = result.message,
            )
            ArchiveResult.Cancelled -> ArchiveProgress(
                jobId, kind, name, state = ArchiveProgress.State.CANCELLED,
            )
            is ArchiveResult.PasswordRequired -> ArchiveProgress(
                jobId, kind, name, state = ArchiveProgress.State.NEEDS_PASSWORD,
                sourcePath = sourcePath, destPath = destPath, wrongPassword = result.wrong,
            )
        }
        ArchiveProgressBus.update(done)
        when (result) {
            // Interactive: keep the in-app dialog, drop the ongoing notification.
            is ArchiveResult.PasswordRequired -> stopForeground(STOP_FOREGROUND_REMOVE)
            // Failures are worth surfacing even if the app is backgrounded.
            is ArchiveResult.Failure -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                showTerminalNotification(done)
            }
            // Success / cancelled: the in-app UI already reflects the result — just clear the
            // notification (no lingering "completed" message).
            else -> stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    // ---------------------------------------------------------------- notifications

    private fun beginForeground(p: ArchiveProgress) {
        ArchiveProgressBus.start(p)
        ensureChannel()
        val notif = buildProgressNotification(p)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun maybeNotify(p: ArchiveProgress) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 300) return
        lastNotify = now
        notify(NOTIF_ID, buildProgressNotification(p))
    }

    private fun buildProgressNotification(p: ArchiveProgress): Notification {
        val percent = (p.fraction * 100).toInt()
        val text = when {
            p.currentEntry.isNotEmpty() && p.totalBytes > 0 ->
                "${p.currentEntry}  •  ${Formatting.bytes(p.processedBytes)} / ${Formatting.bytes(p.totalBytes)}"
            p.currentEntry.isNotEmpty() -> "${p.currentEntry}  •  ${Formatting.bytes(p.processedBytes)}"
            else -> "Preparing…"
        }
        return baseBuilder()
            .setContentTitle(p.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setProgress(100, percent, p.indeterminate)
            .addAction(0, "Cancel", cancelIntent())
            // Show the progress notification right away — Android 12+ otherwise defers foreground
            // service notifications up to 10s, so shorter jobs would finish before it ever appeared.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showTerminalNotification(p: ArchiveProgress) {
        val (title, text) = when (p.state) {
            ArchiveProgress.State.SUCCESS -> {
                val verb = if (p.kind == ArchiveProgress.Kind.EXTRACT) "Extracted" else "Compressed"
                "$verb ${p.title}" to (p.outputPath ?: "Done")
            }
            ArchiveProgress.State.ERROR -> "Failed: ${p.title}" to (p.error ?: "Unknown error")
            ArchiveProgress.State.CANCELLED -> "Cancelled" to p.title
            ArchiveProgress.State.RUNNING, ArchiveProgress.State.NEEDS_PASSWORD -> return
        }
        val notif = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        notify(TERMINAL_NOTIF_ID, notif)
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun cancelIntent(): PendingIntent {
        val i = Intent(this, ArchiveService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notify(id: Int, notif: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(this).notify(id, notif)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; the job still runs.
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Archive operations", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Extraction and compression progress" }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "archive_progress"
        private const val NOTIF_ID = 4201
        private const val TERMINAL_NOTIF_ID = 4202

        const val ACTION_EXTRACT = "com.android_explorer.action.EXTRACT"
        const val ACTION_COMPRESS = "com.android_explorer.action.COMPRESS"
        const val ACTION_CANCEL = "com.android_explorer.action.CANCEL"
        private const val EXTRA_ARCHIVE = "archive"
        private const val EXTRA_DEST = "dest"
        private const val EXTRA_SOURCES = "sources"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_PASSWORD = "password"

        fun extract(context: Context, archivePath: String, destPath: String, password: String? = null) {
            val i = Intent(context, ArchiveService::class.java).apply {
                action = ACTION_EXTRACT
                putExtra(EXTRA_ARCHIVE, archivePath)
                putExtra(EXTRA_DEST, destPath)
                password?.let { putExtra(EXTRA_PASSWORD, it) }
            }
            context.startForegroundService(i)
        }

        fun compress(context: Context, sourcePaths: List<String>, destPath: String, type: ArchiveType) {
            val i = Intent(context, ArchiveService::class.java).apply {
                action = ACTION_COMPRESS
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sourcePaths))
                putExtra(EXTRA_DEST, destPath)
                putExtra(EXTRA_TYPE, type.name)
            }
            context.startForegroundService(i)
        }

        fun cancel(context: Context) {
            val i = Intent(context, ArchiveService::class.java).setAction(ACTION_CANCEL)
            context.startService(i)
        }
    }
}
