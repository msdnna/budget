package website.msdnna.budget_app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val amount: Double,
    val date: String,
    val category: String,
    val source: String?,
    val purpose: String?,
    val description: String?,
    val hidden: Boolean,

    @ColumnInfo(name = "created_by_id") val createdById: String?,
    @ColumnInfo(name = "created_by_name") val createdByName: String?,
    @ColumnInfo(name = "created_by_avatar") val createdByAvatar: String?,
    @ColumnInfo(name = "last_modified_by_id") val lastModifiedById: String?,
    @ColumnInfo(name = "last_modified_by_name") val lastModifiedByName: String?,
    @ColumnInfo(name = "last_modified_by_avatar") val lastModifiedByAvatar: String?,

    @ColumnInfo(name = "created_at") val createdAt: String,
    val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,

    /** SyncStatus.* — drives what SyncWorker pushes. */
    @ColumnInfo(name = "sync_status", defaultValue = SyncStatus.SYNCED)
    val syncStatus: String = SyncStatus.SYNCED,

    /** Server document we conflicted against (JSON). Null unless syncStatus=CONFLICT. */
    @ColumnInfo(name = "server_payload") val serverPayload: String? = null,

    // Detail-request linkage. parent_id points to the lump-sum parent for
    // children; detail_request_id/status are set on the parent once a request
    // is opened. excluded_from_stats mirrors the server flag — open children
    // and closed parents are skipped from sums.
    @ColumnInfo(name = "parent_id", defaultValue = "''")
    val parentId: String = "",
    @ColumnInfo(name = "detail_request_id", defaultValue = "''")
    val detailRequestId: String = "",
    @ColumnInfo(name = "detail_request_status", defaultValue = "''")
    val detailRequestStatus: String = "",
    @ColumnInfo(name = "excluded_from_stats", defaultValue = "0")
    val excludedFromStats: Boolean = false,

    // Recurring-payment linkage. Set to a wishlist item's id when this expense
    // is fulfilling a recurring planned payment (Интернет/Связь/коммуналка/…)
    // so the forecast can mark the wishlist item as "paid this period".
    @ColumnInfo(name = "wishlist_id", defaultValue = "''")
    val wishlistId: String = "",

    /** Budget scope: "bank" (card) or "cash". Stored as TEXT NOT NULL so
     *  filters and aggregations don't have to special-case nulls. Empty/old
     *  rows are backfilled to "bank" by the migration below. */
    @ColumnInfo(name = "deposit", defaultValue = "'bank'")
    val deposit: String = "bank",
)

@Entity(tableName = "wishlist")
data class WishlistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "estimated_cost") val estimatedCost: Double,
    val category: String,
    val priority: Int,
    val frequency: String,
    val purchased: Boolean,
    val notes: String?,

    @ColumnInfo(name = "created_by_id") val createdById: String?,
    @ColumnInfo(name = "created_by_name") val createdByName: String?,
    @ColumnInfo(name = "created_by_avatar") val createdByAvatar: String?,
    @ColumnInfo(name = "last_modified_by_id") val lastModifiedById: String?,
    @ColumnInfo(name = "last_modified_by_name") val lastModifiedByName: String?,
    @ColumnInfo(name = "last_modified_by_avatar") val lastModifiedByAvatar: String?,

    @ColumnInfo(name = "created_at") val createdAt: String,
    val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,

    @ColumnInfo(name = "sync_status", defaultValue = SyncStatus.SYNCED)
    val syncStatus: String = SyncStatus.SYNCED,

    @ColumnInfo(name = "server_payload") val serverPayload: String? = null,

    @ColumnInfo(name = "deposit", defaultValue = "'bank'")
    val deposit: String = "bank",
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val section: String,
    val name: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,

    @ColumnInfo(name = "color", defaultValue = "''") val color: String? = null,
    @ColumnInfo(name = "icon", defaultValue = "''") val icon: String? = null,
    @ColumnInfo(name = "icon_scale", defaultValue = "0") val iconScale: Double = 0.0,
    // null = no monthly limit tracked; only meaningful when section="expense".
    @ColumnInfo(name = "monthly_limit") val monthlyLimit: Double? = null,

    @ColumnInfo(name = "created_at") val createdAt: String,
    val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,

    @ColumnInfo(name = "sync_status", defaultValue = SyncStatus.SYNCED)
    val syncStatus: String = SyncStatus.SYNCED,

    @ColumnInfo(name = "server_payload") val serverPayload: String? = null,
)

/**
 * Unified history feed for the in-app bell popover. Two sources land here:
 *   1. Server-side notifications pulled from `/api/notifications`
 *      (category/global limit overflows). `serverId` holds the server UUID;
 *      `readLocal` mirrors per-device read state — we still call
 *      `/notifications/read-all` so the server marks them read for the
 *      current user, but the local flag drives the badge instantly.
 *   2. Local AlarmManager reminders (income/expense transaction nudges).
 *      `serverId` is null; `readLocal` is the source of truth.
 *
 * `pushedAt` is non-null only after we've raised a system-tray notification
 * for this entry — prevents duplicate pushes when the same row arrives via
 * subsequent sync pulls.
 */
@Entity(tableName = "notification_history")
data class NotificationHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "server_id") val serverId: String? = null,
    /** "category_limit_exceeded" | "global_limit_exceeded" | "expenses_reminder" | "income_reminder" */
    val type: String,
    /** YYYY-MM for limit alerts; "" for reminders. */
    val period: String = "",
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    val title: String = "",
    val body: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "read_local") val readLocal: Boolean = false,
    @ColumnInfo(name = "pushed_at") val pushedAt: Long? = null,
)
