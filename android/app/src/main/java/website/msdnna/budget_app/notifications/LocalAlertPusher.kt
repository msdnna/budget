package website.msdnna.budget_app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import website.msdnna.budget_app.MainActivity
import website.msdnna.budget_app.R
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.db.NotificationHistoryEntity
import website.msdnna.budget_app.data.repository.NotificationHistoryRepository

/**
 * Fires system-tray notifications for new bell-history rows pulled from
 * the server (limit-overflow alerts). Reminder rows are not pushed here —
 * those are inserted by [NotificationReceiver] *after* it fires the
 * system push, so they're already `pushedAt`-tagged on insert.
 *
 * Dedup rules:
 *  - A row with non-null `pushedAt` is never re-pushed (covers repeat sync
 *    pulls returning the same row).
 *  - Per-type user prefs gate the push: a category-limit row only fires if
 *    `notif_category_limit_enabled == true`, etc.
 *
 * Channel: reuses `budget_reminders` so the user only has one channel to
 * mute. The notification id is derived from the row id's hash so each
 * unique row shows in its own slot (limit alerts shouldn't overwrite each
 * other in the tray — getting two category overflows in a month should
 * surface as two notifications, not one collapse).
 */
object LocalAlertPusher {
    suspend fun pushNewIfAllowed(context: Context, rows: List<NotificationHistoryEntity>) {
        if (rows.isEmpty()) return
        val prefs = AppContainer.prefs
        val categoryEnabled = prefs.notifCategoryLimitEnabled.first()
        val globalEnabled = prefs.notifGlobalLimitEnabled.first()
        val now = System.currentTimeMillis()

        val toPush = rows.filter { row ->
            row.pushedAt == null &&
                when (row.type) {
                    "category_limit_exceeded" -> categoryEnabled
                    "global_limit_exceeded" -> globalEnabled
                    else -> false
                }
        }
        for (row in toPush) {
            val title = row.title.ifBlank { "Уведомление" }
            val body = row.body
            // Stable id per row so duplicate suppression at the OS level is
            // automatic (same id → second post just replaces the first
            // notification in the shade); we additionally guard at the DB
            // level via pushedAt for cross-process correctness.
            val notifId = (row.serverId ?: row.id).hashCode() and 0x7FFFFFFF
            show(context, notifId, title, body)
            NotificationHistoryRepository.markPushed(row.id, now)
        }
    }

    private fun show(context: Context, id: Int, title: String, body: String) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val tapPi = PendingIntent.getActivity(
            context, id, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
    }
}
