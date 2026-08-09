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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.android_explorer.MainActivity
import com.android_explorer.R
import com.android_explorer.archive.ArchiveManager
import com.android_explorer.archive.ArchiveResult
import com.android_explorer.archive.ArchiveType
import com.android_explorer.data.NodeRef
import com.android_explorer.data.TransferClipboard
import com.android_explorer.data.TransferManager
import com.android_explorer.data.TransferResult
import com.android_explorer.job.JobListener
import com.android_explorer.job.JobProgress
import com.android_explorer.job.JobProgressBus
import com.android_explorer.job.TransferJobs
import com.android_explorer.util.Formatting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one extract/compress job at a time as a foreground service. Progress is mirrored to a
 * notification (with a progress bar) and to [JobProgressBus] for the in-app dialog.
 */
class FileJobService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotify = 0L
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            JobProgressBus.requestCancel()
            return START_NOT_STICKY
        }
        // The bus holds one job and one cancel flag, so a second job would clobber the first's
        // progress and cross-cancel it. Paste is far more frequent than extract, so this collision
        // is reachable in normal use.
        if (!running.compareAndSet(false, true)) {
            Toast.makeText(this, "Another file job is already running", Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_EXTRACT -> startExtract(intent)
            ACTION_COMPRESS -> startCompress(intent)
            ACTION_COPY -> startCopy(intent)
            else -> {
                running.set(false)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Releases the single-job slot and stops; used for malformed intents. */
    private fun abort() {
        running.set(false)
        stopSelf()
    }

    private fun startCopy(intent: Intent) {
        val job = TransferJobs.take(intent.getLongExtra(EXTRA_TOKEN, -1L)) ?: return abort()
        val kind = if (job.clip.cut) JobProgress.Kind.MOVE else JobProgress.Kind.COPY
        val what = job.clip.items.singleOrNull()?.name ?: "${job.clip.items.size} items"
        val title = if (job.clip.cut) "Moving $what" else "Copying $what"
        val jobId = SystemClock.elapsedRealtime()
        beginForeground(JobProgress(jobId, kind, title))
        scope.launch {
            val result = TransferManager.paste(
                applicationContext, job.clip, job.dest, busListener(jobId, kind, title),
            )
            finishTransfer(jobId, kind, title, result)
        }
    }

    private fun startExtract(intent: Intent) {
        val archive = File(intent.getStringExtra(EXTRA_ARCHIVE) ?: return abort())
        val destDir = File(intent.getStringExtra(EXTRA_DEST) ?: return abort())
        val password = intent.getStringExtra(EXTRA_PASSWORD)
        val jobId = SystemClock.elapsedRealtime()
        val title = "Extracting ${archive.name}"
        beginForeground(
            JobProgress(
                jobId, JobProgress.Kind.EXTRACT, title,
                sourcePath = archive.absolutePath, destPath = destDir.absolutePath,
            ),
        )
        scope.launch {
            val listener = busListener(jobId, JobProgress.Kind.EXTRACT, title)
            val result = ArchiveManager.extract(archive, destDir, listener, password)
            finish(jobId, JobProgress.Kind.EXTRACT, archive.name, archive.absolutePath, destDir.absolutePath, result)
        }
    }

    private fun startCompress(intent: Intent) {
        val sources = intent.getStringArrayListExtra(EXTRA_SOURCES)?.map { File(it) } ?: return abort()
        val dest = File(intent.getStringExtra(EXTRA_DEST) ?: return abort())
        val type = runCatching { ArchiveType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }
            .getOrDefault(ArchiveType.ZIP)
        val jobId = SystemClock.elapsedRealtime()
        val initial = JobProgress(jobId, JobProgress.Kind.COMPRESS, "Compressing ${dest.name}")
        beginForeground(initial)
        scope.launch {
            val listener = busListener(jobId, JobProgress.Kind.COMPRESS, "Compressing ${dest.name}")
            val result = ArchiveManager.compress(sources, dest, type, listener)
            finish(jobId, JobProgress.Kind.COMPRESS, dest.name, dest.absolutePath, dest.absolutePath, result)
        }
    }

    private fun busListener(jobId: Long, kind: JobProgress.Kind, title: String) = object : JobListener {
        override fun onProgress(entry: String, entriesDone: Int, entriesTotal: Int, bytesDone: Long, bytesTotal: Long) {
            val snapshot = JobProgress(
                jobId = jobId,
                kind = kind,
                title = title,
                currentEntry = entry,
                processedEntries = entriesDone,
                totalEntries = entriesTotal,
                processedBytes = bytesDone,
                totalBytes = bytesTotal,
                state = JobProgress.State.RUNNING,
            )
            JobProgressBus.update(snapshot)
            maybeNotify(snapshot)
        }

        override fun isCancelled(): Boolean = JobProgressBus.isCancelled()
    }

    private fun finish(
        jobId: Long,
        kind: JobProgress.Kind,
        name: String,
        sourcePath: String,
        destPath: String,
        result: ArchiveResult,
    ) {
        val done = when (result) {
            is ArchiveResult.Success -> JobProgress(
                jobId, kind, name, state = JobProgress.State.SUCCESS, outputPath = destPath,
                processedEntries = result.entries,
            )
            is ArchiveResult.Failure -> JobProgress(
                jobId, kind, name, state = JobProgress.State.ERROR, error = result.message,
            )
            ArchiveResult.Cancelled -> JobProgress(
                jobId, kind, name, state = JobProgress.State.CANCELLED,
            )
            is ArchiveResult.PasswordRequired -> JobProgress(
                jobId, kind, name, state = JobProgress.State.NEEDS_PASSWORD,
                sourcePath = sourcePath, destPath = destPath, wrongPassword = result.wrong,
            )
        }
        JobProgressBus.update(done)
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
        running.set(false)
        stopSelf()
    }

    private fun finishTransfer(jobId: Long, kind: JobProgress.Kind, title: String, result: TransferResult) {
        val done = when (result) {
            is TransferResult.Success -> JobProgress(
                jobId, kind, title,
                state = JobProgress.State.SUCCESS,
                processedEntries = result.files,
                processedBytes = result.bytes,
                note = result.skipped.takeIf { it.isNotEmpty() }
                    ?.let { "Skipped ${it.size}: ${it.joinToString("; ")}" },
            )
            is TransferResult.Failure -> JobProgress(
                jobId, kind, title, state = JobProgress.State.ERROR, error = result.message,
            )
            // Already-transferred files are deliberately kept, so say how far it got.
            is TransferResult.Cancelled -> JobProgress(
                jobId, kind, title,
                state = JobProgress.State.CANCELLED,
                processedEntries = result.files,
                note = if (result.totalFiles > 0) {
                    "${result.files} of ${result.totalFiles} files already transferred"
                } else {
                    "${result.files} files already transferred"
                },
            )
        }
        JobProgressBus.update(done)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (result is TransferResult.Failure) showTerminalNotification(done)
        running.set(false)
        stopSelf()
    }

    // ---------------------------------------------------------------- notifications

    private fun beginForeground(p: JobProgress) {
        JobProgressBus.start(p)
        ensureChannel()
        val notif = buildProgressNotification(p)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun maybeNotify(p: JobProgress) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 300) return
        lastNotify = now
        notify(NOTIF_ID, buildProgressNotification(p))
    }

    private fun buildProgressNotification(p: JobProgress): Notification {
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

    private fun showTerminalNotification(p: JobProgress) {
        val (title, text) = when (p.state) {
            JobProgress.State.SUCCESS -> {
                val verb = when (p.kind) {
                    JobProgress.Kind.EXTRACT -> "Extracted"
                    JobProgress.Kind.COMPRESS -> "Compressed"
                    JobProgress.Kind.COPY -> "Copied"
                    JobProgress.Kind.MOVE -> "Moved"
                }
                "$verb ${p.title}" to (p.outputPath ?: "Done")
            }
            JobProgress.State.ERROR -> "Failed: ${p.title}" to (p.error ?: "Unknown error")
            JobProgress.State.CANCELLED -> "Cancelled" to p.title
            JobProgress.State.RUNNING, JobProgress.State.NEEDS_PASSWORD -> return
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
        val i = Intent(this, FileJobService::class.java).setAction(ACTION_CANCEL)
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
                    CHANNEL_ID, "File operations", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Extraction, compression and file transfer progress" }
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
        const val ACTION_COPY = "com.android_explorer.action.COPY"
        const val ACTION_CANCEL = "com.android_explorer.action.CANCEL"
        private const val EXTRA_ARCHIVE = "archive"
        private const val EXTRA_DEST = "dest"
        private const val EXTRA_SOURCES = "sources"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_TOKEN = "token"

        /**
         * Runs a paste as a foreground job. Takes ownership of the clip — including clearing the
         * clipboard — so the job is not racing the caller for it.
         */
        fun paste(context: Context, clip: TransferClipboard.Clip, dest: NodeRef) {
            val token = TransferJobs.stash(clip, dest)
            val i = Intent(context, FileJobService::class.java).apply {
                action = ACTION_COPY
                putExtra(EXTRA_TOKEN, token)
            }
            context.startForegroundService(i)
            TransferClipboard.clear()
        }

        fun extract(context: Context, archivePath: String, destPath: String, password: String? = null) {
            val i = Intent(context, FileJobService::class.java).apply {
                action = ACTION_EXTRACT
                putExtra(EXTRA_ARCHIVE, archivePath)
                putExtra(EXTRA_DEST, destPath)
                password?.let { putExtra(EXTRA_PASSWORD, it) }
            }
            context.startForegroundService(i)
        }

        fun compress(context: Context, sourcePaths: List<String>, destPath: String, type: ArchiveType) {
            val i = Intent(context, FileJobService::class.java).apply {
                action = ACTION_COMPRESS
                putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sourcePaths))
                putExtra(EXTRA_DEST, destPath)
                putExtra(EXTRA_TYPE, type.name)
            }
            context.startForegroundService(i)
        }

        fun cancel(context: Context) {
            val i = Intent(context, FileJobService::class.java).setAction(ACTION_CANCEL)
            context.startService(i)
        }
    }
}
