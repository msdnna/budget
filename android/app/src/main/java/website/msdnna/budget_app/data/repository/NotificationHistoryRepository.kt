package website.msdnna.budget_app.data.repository

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.db.NotificationHistoryEntity
import website.msdnna.budget_app.data.model.ServerNotification

/**
 * Unified bell-history feed. Server-pulled notifications + local reminder
 * fires both land in the same Room table; the UI reads it via a single
 * Flow and renders chronologically.
 *
 * Sync flow:
 *  1. After each sync pull, [refreshFromServer] is called with the current
 *     server URL.
 *  2. Each new server notification (looked up by `server_id`) inserts a
 *     new row; existing rows have their server-side `read` state mirrored
 *     into `read_local` only when the local state is still unread (we
 *     don't re-mark-unread something the user already read on another
 *     device — last-write-wins in the user's favour).
 *  3. New rows with `pushed_at == null` are candidates for system-push
 *     notification; the LocalAlertPusher handles dedup + per-toggle
 *     filtering.
 */
object NotificationHistoryRepository {
    private val dao get() = AppContainer.db.notifications()

    fun observeRecent(limit: Int = 100): Flow<List<NotificationHistoryEntity>> =
        dao.observeRecent(limit)

    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    suspend fun markAllRead(serverUrl: String?) {
        dao.markAllRead()
        // Server-side read-state is per-user via /notifications/read-all.
        // Fire-and-forget — if the call fails, local state stays read; a
        // subsequent sync pull will mirror the server's view back if the
        // user reads on another device first.
        if (!serverUrl.isNullOrBlank()) {
            try {
                RetrofitClient.getService(serverUrl).markAllNotificationsRead()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Tolerated — local read state is the source of truth for the bell badge.
            }
        }
    }

    suspend fun markRead(id: String, serverId: String?, serverUrl: String?) {
        dao.markRead(id)
        if (serverId != null && !serverUrl.isNullOrBlank()) {
            try {
                RetrofitClient.getService(serverUrl).markNotificationRead(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Same rationale as markAllRead.
            }
        }
    }

    /** Returns the newly-inserted rows so the caller can decide which need
     *  to trigger a system-push notification. */
    suspend fun refreshFromServer(serverUrl: String): List<NotificationHistoryEntity> {
        if (serverUrl.isBlank()) return emptyList()
        val resp = try {
            RetrofitClient.getService(serverUrl).getNotifications()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val newRows = mutableListOf<NotificationHistoryEntity>()
        for (n in resp.data) {
            val existing = dao.findByServerId(n.id)
            if (existing != null) {
                // If server now says it's read and we hadn't marked locally,
                // sync that down — but don't downgrade local "read" to
                // "unread" if the server still has it unread (we may have
                // read on this device but failed to POST /read-all).
                if (n.read && !existing.readLocal) {
                    dao.markRead(existing.id)
                }
                continue
            }
            val row = n.toEntity()
            dao.upsert(row)
            newRows.add(row)
        }
        return newRows
    }

    suspend fun appendLocalReminder(
        type: String,
        title: String,
        body: String,
        createdAt: Long = System.currentTimeMillis(),
    ): NotificationHistoryEntity {
        val row = NotificationHistoryEntity(
            id = UUID.randomUUID().toString(),
            serverId = null,
            type = type,
            title = title,
            body = body,
            createdAt = createdAt,
            // Local reminders ARE the system push themselves — they always
            // fire a Notification before landing here, so pushedAt is set.
            pushedAt = createdAt,
        )
        dao.upsert(row)
        return row
    }

    suspend fun markPushed(id: String, at: Long = System.currentTimeMillis()) {
        dao.markPushed(id, at)
    }
}

private fun ServerNotification.toEntity(): NotificationHistoryEntity {
    val createdMs = runCatching {
        java.time.Instant.parse(createdAt).toEpochMilli()
    }.getOrElse { System.currentTimeMillis() }
    val (title, body) = when (type) {
        "category_limit_exceeded" ->
            "Превышен лимит: ${categoryName.ifBlank { "категория" }}" to
                "Потрачено ${formatMoney(spent)} ₽ из ${formatMoney(limit)} ₽ за $period"
        "global_limit_exceeded" ->
            "Превышен общий лимит расходов" to
                "Потрачено ${formatMoney(spent)} ₽ из ${formatMoney(limit)} ₽ за $period"
        else -> "Уведомление" to ""
    }
    return NotificationHistoryEntity(
        id = java.util.UUID.randomUUID().toString(),
        serverId = id,
        type = type,
        period = period,
        categoryId = categoryId.ifBlank { null },
        categoryName = categoryName.ifBlank { null },
        limit = limit,
        spent = spent,
        title = title,
        body = body,
        createdAt = createdMs,
        readLocal = read,
        pushedAt = null,
    )
}

private val RU_RU: java.util.Locale = java.util.Locale.forLanguageTag("ru-RU")

private fun formatMoney(value: Double): String =
    java.text.NumberFormat.getNumberInstance(RU_RU).apply {
        maximumFractionDigits = 0
    }.format(value)
