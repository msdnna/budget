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
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val section: String,
    val name: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,

    @ColumnInfo(name = "color", defaultValue = "''") val color: String? = null,
    @ColumnInfo(name = "icon", defaultValue = "''") val icon: String? = null,

    @ColumnInfo(name = "created_at") val createdAt: String,
    val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,

    @ColumnInfo(name = "sync_status", defaultValue = SyncStatus.SYNCED)
    val syncStatus: String = SyncStatus.SYNCED,

    @ColumnInfo(name = "server_payload") val serverPayload: String? = null,
)
