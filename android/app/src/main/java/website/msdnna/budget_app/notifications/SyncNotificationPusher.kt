package website.msdnna.budget_app.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import website.msdnna.budget_app.MainActivity
import website.msdnna.budget_app.R
import website.msdnna.budget_app.data.sync.SyncProgress
import website.msdnna.budget_app.data.sync.SyncProgressBus

/**
 * Ongoing system-tray notification that mirrors the in-app sync banner.
 * Lives only while a sync is running; auto-dismissed (cancel) when the
 * engine emits Done / Failed / Idle.
 *
 * Channel: dedicated [CHANNEL_ID] with IMPORTANCE_LOW (no sound, no
 * peek) — sync is background bookkeeping, not something to interrupt
 * the user for. Created by [website.msdnna.budget_app.MainActivity].
 *
 * Driven from [website.msdnna.budget_app.data.sync.SyncWorker.doWork],
 * which calls [observeWhile] in a child coroutine so updates stop
 * landing as soon as the worker returns.
 */
object SyncNotificationPusher {
    const val CHANNEL_ID = "budget_sync"
    const val NOTIF_ID = 3001

    /**
     * Collect [SyncProgressBus.visibleState] until the caller's scope
     * cancels, keeping a foreground-ish progress notification in sync with
     * the bus. On any terminal state (Done / Failed / Idle) the notification
     * is cancelled — caller is expected to scope-cancel afterwards too.
     *
     * Reads from the debounced visibleState so fast incremental syncs
     * (post-CRUD push/pull, <600ms, total<=500) never produce a tray
     * flash. See [SyncProgressBus.visibleState].
     */
    suspend fun observeWhile(context: Context, source: Flow<SyncProgress> = SyncProgressBus.visibleState) {
        source.collectLatest { state ->
            when (state) {
                is SyncProgress.Running -> show(context, state)
                SyncProgress.Done, is SyncProgress.Failed, SyncProgress.Idle -> cancel(context)
            }
        }
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    /**
     * ForegroundInfo for [website.msdnna.budget_app.data.sync.SyncWorker]
     * to promote itself to a foreground service. Built on demand once we've
     * decided the sync is worth surfacing — long enough that the OS would
     * otherwise risk killing the worker mid-pull (15k+ row first sync
     * routinely takes >1 min).
     *
     * Android 14+ (API 34) requires the service type be declared at
     * `startForeground` time; below that we omit it (older platforms
     * derive type from manifest declarations).
     */
    fun foregroundInfo(context: Context, p: SyncProgress.Running): ForegroundInfo {
        val notification = buildNotificationFor(context, p)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    /**
     * Direct post — used by [website.msdnna.budget_app.data.sync.SyncWorker]
     * to update the tray notification after the initial foreground
     * promotion (which itself posts the first notification via setForeground).
     */
    fun show(context: Context, p: SyncProgress.Running) {
        val notification = buildNotificationFor(context, p)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    private fun buildNotificationFor(context: Context, p: SyncProgress.Running): Notification =
        if (p.total > 0) {
            buildNotification(context, subtitleFor(p), indeterminate = false, total = p.total, processed = p.processed)
        } else {
            buildNotification(context, subtitleFor(p), indeterminate = true)
        }

    private fun buildNotification(
        context: Context,
        contentText: String,
        indeterminate: Boolean,
        total: Int = 0,
        processed: Int = 0,
    ): Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val tapPi = PendingIntent.getActivity(
            context, NOTIF_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Синхронизация")
            .setContentText(contentText)
            .setContentIntent(tapPi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(if (indeterminate) 0 else total, if (indeterminate) 0 else processed, indeterminate)
        return builder.build()
    }

    private fun subtitleFor(p: SyncProgress.Running): String {
        val label = p.phase.label
        return if (p.total > 0) "$label: ${p.processed} / ${p.total}" else label
    }
}
