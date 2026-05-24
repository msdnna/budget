package website.msdnna.budget_app.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.notifications.SyncNotificationPusher

/**
 * Drains pending local mutations and pulls remote changes once.
 *
 * Triggered by:
 *  - [NetworkObserver] when the device gains connectivity.
 *  - Direct calls after a local mutation (so the row leaves "pending" ASAP).
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val serverUrl = AppContainer.prefs.serverUrl.first().orEmpty()
        if (serverUrl.isBlank()) return@coroutineScope Result.success()

        // Combined notification + foreground-promotion job. Reads from
        // visibleState (debounced) so fast incremental syncs never trigger
        // either the tray notification or a foreground-service promotion —
        // both would otherwise flash for sub-second post-CRUD pulls.
        //
        // The very first Running emission upgrades us to a foreground
        // service via setForeground; subsequent Running emissions just
        // re-post the notification via the regular NotificationManager
        // path. Done/Failed/Idle cancel the tray notification.
        //
        // setForeground is best-effort — older OSes / FGS restrictions may
        // reject it (e.g. periodic workers without expedited can't enter FG
        // while the app is in background on Android 12+). Sync still runs
        // if it fails; only the background-survival guarantee is lost.
        val notif = launch {
            var promoted = false
            SyncProgressBus.visibleState.collect { state ->
                when (state) {
                    is SyncProgress.Running -> {
                        if (!promoted) {
                            promoted = true
                            // setForeground itself posts the notification on
                            // success — no extra show() needed in that branch.
                            runCatching {
                                setForeground(SyncNotificationPusher.foregroundInfo(applicationContext, state))
                            }.onFailure {
                                Log.w(TAG, "setForeground rejected", it)
                                SyncNotificationPusher.show(applicationContext, state)
                            }
                        } else {
                            SyncNotificationPusher.show(applicationContext, state)
                        }
                    }
                    SyncProgress.Done, is SyncProgress.Failed, SyncProgress.Idle -> {
                        SyncNotificationPusher.cancel(applicationContext)
                    }
                }
            }
        }
        try {
            when (AppContainer.syncEngine.sync(serverUrl)) {
                SyncEngine.Result.Success, SyncEngine.Result.Skipped -> Result.success()
                SyncEngine.Result.Failed -> Result.retry()
            }
        } finally {
            notif.cancel()
            SyncNotificationPusher.cancel(applicationContext)
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val UNIQUE_NAME = "msdnna_budget_sync"
        private const val PERIODIC_NAME = "msdnna_budget_sync_periodic"

        /**
         * Отменить запланированную/выполняющуюся sync-работу. Вызывается на
         * explicit logout (`AppContainer.wipeUserData`) — иначе уже-в-очереди
         * SyncWorker может стартовать после очистки Room и неудачно дёрнуть
         * сервер на промежуточном состоянии (или, что хуже, на новом сервере
         * под старым auth-token'ом до того, как успеет прорастать clearAuth).
         *
         * Periodic тоже снимаем — иначе после wipe он бы продолжал стучаться
         * на старый сервер.
         */
        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(UNIQUE_NAME)
            wm.cancelUniqueWork(PERIODIC_NAME)
        }

        fun enqueue(context: Context) {
            // Piggyback a reachability re-probe on every mutation. Repositories
            // call enqueue() after every create/update/delete, so this covers
            // the "re-check after working with records" requirement without
            // needing each call-site to remember to ping.
            ReachabilityGate.refresh()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            // KEEP (not REPLACE): callers fire enqueue() liberally — every
            // CRUD, every network transition, every screen-resume reachability
            // tick. REPLACE used to cancel the in-flight pull mid-stream, the
            // partial pull's `lastSyncToken` never got persisted, and the next
            // run refetched 15k+ rows from scratch — the user saw the progress
            // banner reset from "5500 / 23187" back to zero.
            //
            // Pending local mutations sit in Room with PENDING_* flags, so
            // skipping a redundant enqueue is safe: the running sync will pick
            // them up on its push phase or the next periodic tick will.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Periodic background sync: ensures pulls happen even when the user
         * doesn't open the app for hours. Minimum interval enforced by
         * WorkManager is 15 minutes. KEEP policy so re-scheduling on each
         * app start is idempotent — only the very first call actually
         * enqueues.
         *
         * Called from [website.msdnna.budget_app.MainActivity] once a server
         * URL + auth token are known. Cancelled together with the one-shot
         * unique work on explicit logout via [cancel].
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
