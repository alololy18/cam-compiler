package com.camcompiler.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MergeService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var currentJob: Job? = null
    private val binder = LocalBinder()

    var status: String = "Idle"
    var progress: Float = 0f
    var isRunning: Boolean = false
    var lastResult: MergeEngine.Result? = null
    var lastSourceUris: List<Uri> = emptyList()

    var listener: ((Float, String, MergeEngine.Result?) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): MergeService = this@MergeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            cancelMerge()
            return START_NOT_STICKY
        }

        // Retrieve the pending merge job set by start()
        val job = pendingJob ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        pendingJob = null

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(
            this, NOTIF_ID,
            buildNotification("Starting merge...", 0f, true), type
        )

        lastSourceUris = job.project.clipEdits.map { it.sourceUri }
        startMerge(job.project, job.outputUri, job.mode)
        return START_NOT_STICKY
    }

    private fun startMerge(project: EditProject, outputUri: Uri, mode: MergeEngine.Mode) {
        if (isRunning) return
        isRunning = true
        currentJob = scope.launch {
            val result = MergeEngine.merge(this@MergeService, project, outputUri, mode) { p, s ->
                progress = p
                status = s
                listener?.invoke(p, s, null)
                updateNotification(s, p, true)
            }
            lastResult = result
            isRunning = false
            val finalMsg = when (result) {
                is MergeEngine.Result.Success -> {
                    val mb = result.outputBytes / (1024.0 * 1024.0)
                    val skip = if (result.skipped > 0) " (${result.skipped} skipped)" else ""
                    "Done! ${"%.1f".format(mb)} MB via ${result.method}$skip"
                }
                is MergeEngine.Result.Failure -> "Failed: ${result.message}"
            }
            status = finalMsg
            progress = 1f
            listener?.invoke(1f, finalMsg, result)
            updateNotification(finalMsg, 1f, false)
            kotlinx.coroutines.delay(1500)
            ServiceCompat.stopForeground(this@MergeService, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    fun cancelMerge() {
        currentJob?.cancel()
        isRunning = false
        status = "Cancelled"
        listener?.invoke(0f, "Cancelled", null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.merge_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.merge_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Float, ongoing: Boolean): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(this, MergeService::class.java).apply { action = ACTION_CANCEL }
        val cancelPi = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cam Compiler")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            builder.setProgress(100, (progress * 100).toInt(), progress < 0.01f)
            builder.addAction(0, "Cancel", cancelPi)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Float, ongoing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, progress, ongoing))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "merge_progress"
        const val NOTIF_ID = 1001
        const val ACTION_CANCEL = "com.camcompiler.app.CANCEL"

        /**
         * Holds the EditProject + output + mode for the next service start.
         * We use a static holder because Intent extras can't easily carry
         * complex data classes with URIs and lists.
         */
        data class PendingMergeJob(
            val project: EditProject,
            val outputUri: Uri,
            val mode: MergeEngine.Mode
        )
        @Volatile var pendingJob: PendingMergeJob? = null

        fun start(ctx: Context, project: EditProject, outputUri: Uri, mode: MergeEngine.Mode) {
            pendingJob = PendingMergeJob(project, outputUri, mode)
            val intent = Intent(ctx, MergeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
