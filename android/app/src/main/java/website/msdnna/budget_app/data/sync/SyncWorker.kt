package website.msdnna.budget_app.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import website.msdnna.budget_app.data.AppContainer

/**
 * Drains pending local mutations and pulls remote changes once.
 *
 * Triggered by:
 *  - [NetworkObserver] when the device gains connectivity.
 *  - Direct calls after a local mutation (so the row leaves "pending" ASAP).
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val serverUrl = AppContainer.prefs.serverUrl.first().orEmpty()
        if (serverUrl.isBlank()) return Result.success()
        return when (AppContainer.syncEngine.sync(serverUrl)) {
            SyncEngine.Result.Success, SyncEngine.Result.Skipped -> Result.success()
            SyncEngine.Result.Failed -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "msdnna_budget_sync"

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
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
