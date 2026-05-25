package website.msdnna.budget_app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    // Hide pending children of an open detail-request (parent_id != '' AND
    // excluded_from_stats=1). Closed-request parents have excluded_from_stats=1
    // too but stay visible — they're the historical record.
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL AND sync_status != :pendingDelete
          AND (parent_id = '' OR excluded_from_stats = 0)
        ORDER BY date DESC
    """
    )
    fun observeAll(pendingDelete: String = SyncStatus.PENDING_DELETE): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL AND sync_status != :pendingDelete
          AND (parent_id = '' OR excluded_from_stats = 0)
          AND (:includeDetailed = 1 OR detail_request_status != 'closed')
          AND (
            :includeSplit = 1
            OR NOT (
              type = 'income'
              AND excluded_from_stats = 1
              AND parent_id = ''
              AND (detail_request_status IS NULL OR detail_request_status = '')
            )
          )
          AND (:type IS NULL OR type = :type)
          AND (:from IS NULL OR date >= :from)
          AND (:to IS NULL OR date <= :to)
          AND (:deposit IS NULL OR deposit = :deposit)
        ORDER BY date DESC
    """
    )
    fun observeFiltered(
        type: String?,
        from: String?,
        to: String?,
        includeDetailed: Boolean = false,
        deposit: String? = null,
        includeSplit: Boolean = false,
        pendingDelete: String = SyncStatus.PENDING_DELETE,
    ): Flow<List<TransactionEntity>>

    // Children of a parent transaction — used when fulfilling a detail-request
    // so the assignee can see what they've already added.
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL AND parent_id = :parentId
          AND sync_status != :pendingDelete
        ORDER BY date DESC, created_at DESC
    """
    )
    fun observeChildren(parentId: String, pendingDelete: String = SyncStatus.PENDING_DELETE): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE sync_status IN (:pc, :pu, :pd)")
    suspend fun findPending(
        pc: String = SyncStatus.PENDING_CREATE,
        pu: String = SyncStatus.PENDING_UPDATE,
        pd: String = SyncStatus.PENDING_DELETE,
    ): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE sync_status = :conflict")
    fun observeConflicts(conflict: String = SyncStatus.CONFLICT): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE sync_status = :conflict")
    fun observeConflictCount(conflict: String = SyncStatus.CONFLICT): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TransactionEntity>)

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteHard(id: String)

    // ── Bulk mutations ─────────────────────────────────────────────────────
    // Single SQL statement → Room emits invalidation once for the whole batch
    // instead of once per row, which kept the LazyColumn from recomposing N
    // times when a multi-select hide/delete was applied.

    @Query(
        """
        UPDATE transactions
        SET hidden = :hidden,
            sync_status = CASE WHEN sync_status = :pendingCreate THEN sync_status ELSE :pendingUpdate END,
            updated_at = :updatedAt,
            last_modified_by_id = :userId,
            last_modified_by_name = :userName,
            last_modified_by_avatar = :userAvatar
        WHERE id IN (:ids)
    """
    )
    suspend fun bulkSetHidden(
        ids: List<String>,
        hidden: Boolean,
        updatedAt: String,
        userId: String?,
        userName: String?,
        userAvatar: String?,
        pendingCreate: String = SyncStatus.PENDING_CREATE,
        pendingUpdate: String = SyncStatus.PENDING_UPDATE,
    )

    @Query("DELETE FROM transactions WHERE id IN (:ids) AND sync_status = :pendingCreate")
    suspend fun bulkPurgePendingCreate(
        ids: List<String>,
        pendingCreate: String = SyncStatus.PENDING_CREATE,
    )

    @Query(
        """
        UPDATE transactions
        SET sync_status = :pendingDelete,
            updated_at = :updatedAt
        WHERE id IN (:ids) AND sync_status != :pendingCreate
    """
    )
    suspend fun bulkMarkDeleted(
        ids: List<String>,
        updatedAt: String,
        pendingDelete: String = SyncStatus.PENDING_DELETE,
        pendingCreate: String = SyncStatus.PENDING_CREATE,
    )

    @Transaction
    suspend fun bulkDelete(ids: List<String>, updatedAt: String) {
        // PENDING_CREATE rows never made it to the server → purge locally; the
        // rest get a tombstone for SyncWorker to push.
        bulkPurgePendingCreate(ids)
        bulkMarkDeleted(ids, updatedAt)
    }
}

@Dao
interface WishlistDao {
    @Query("SELECT * FROM wishlist WHERE deleted_at IS NULL AND sync_status != :pendingDelete ORDER BY priority ASC, created_at DESC")
    fun observeAll(pendingDelete: String = SyncStatus.PENDING_DELETE): Flow<List<WishlistEntity>>

    @Query("SELECT * FROM wishlist WHERE id = :id")
    suspend fun findById(id: String): WishlistEntity?

    @Query("SELECT * FROM wishlist WHERE sync_status IN (:pc, :pu, :pd)")
    suspend fun findPending(
        pc: String = SyncStatus.PENDING_CREATE,
        pu: String = SyncStatus.PENDING_UPDATE,
        pd: String = SyncStatus.PENDING_DELETE,
    ): List<WishlistEntity>

    @Query("SELECT * FROM wishlist WHERE sync_status = :conflict")
    fun observeConflicts(conflict: String = SyncStatus.CONFLICT): Flow<List<WishlistEntity>>

    @Query("SELECT COUNT(*) FROM wishlist WHERE sync_status = :conflict")
    fun observeConflictCount(conflict: String = SyncStatus.CONFLICT): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WishlistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WishlistEntity>)

    @Update
    suspend fun update(entity: WishlistEntity)

    @Query("DELETE FROM wishlist WHERE id = :id")
    suspend fun deleteHard(id: String)

    @Query(
        """
        UPDATE wishlist
        SET purchased = :purchased,
            sync_status = CASE WHEN sync_status = :pendingCreate THEN sync_status ELSE :pendingUpdate END,
            updated_at = :updatedAt,
            last_modified_by_id = :userId,
            last_modified_by_name = :userName,
            last_modified_by_avatar = :userAvatar
        WHERE id IN (:ids)
    """
    )
    suspend fun bulkSetPurchased(
        ids: List<String>,
        purchased: Boolean,
        updatedAt: String,
        userId: String?,
        userName: String?,
        userAvatar: String?,
        pendingCreate: String = SyncStatus.PENDING_CREATE,
        pendingUpdate: String = SyncStatus.PENDING_UPDATE,
    )

    @Query("DELETE FROM wishlist WHERE id IN (:ids) AND sync_status = :pendingCreate")
    suspend fun bulkPurgePendingCreate(
        ids: List<String>,
        pendingCreate: String = SyncStatus.PENDING_CREATE,
    )

    @Query(
        """
        UPDATE wishlist
        SET sync_status = :pendingDelete,
            updated_at = :updatedAt
        WHERE id IN (:ids) AND sync_status != :pendingCreate
    """
    )
    suspend fun bulkMarkDeleted(
        ids: List<String>,
        updatedAt: String,
        pendingDelete: String = SyncStatus.PENDING_DELETE,
        pendingCreate: String = SyncStatus.PENDING_CREATE,
    )

    @Transaction
    suspend fun bulkDelete(ids: List<String>, updatedAt: String) {
        bulkPurgePendingCreate(ids)
        bulkMarkDeleted(ids, updatedAt)
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deleted_at IS NULL AND sync_status != :pendingDelete AND section = :section ORDER BY is_default DESC, name ASC")
    fun observeBySection(section: String, pendingDelete: String = SyncStatus.PENDING_DELETE): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE deleted_at IS NULL AND sync_status != :pendingDelete ORDER BY section, is_default DESC, name ASC")
    fun observeAll(pendingDelete: String = SyncStatus.PENDING_DELETE): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE sync_status IN (:pc, :pu, :pd)")
    suspend fun findPending(
        pc: String = SyncStatus.PENDING_CREATE,
        pu: String = SyncStatus.PENDING_UPDATE,
        pd: String = SyncStatus.PENDING_DELETE,
    ): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE sync_status = :conflict")
    fun observeConflicts(conflict: String = SyncStatus.CONFLICT): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CategoryEntity)

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteHard(id: String)
}

@Dao
interface NotificationHistoryDao {
    /** Newest first; cap at a reasonable view-size — older rows can be
     *  purged by a background prune step if the table grows unbounded. */
    @Query("SELECT * FROM notification_history ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT COUNT(*) FROM notification_history WHERE read_local = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM notification_history WHERE server_id = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: String): NotificationHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NotificationHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NotificationHistoryEntity>)

    @Query("UPDATE notification_history SET read_local = 1 WHERE read_local = 0")
    suspend fun markAllRead()

    @Query("UPDATE notification_history SET read_local = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE notification_history SET pushed_at = :pushedAt WHERE id = :id")
    suspend fun markPushed(id: String, pushedAt: Long)

    /** Hard-purge anything older than the cutoff timestamp. Called sparingly
     *  by the sync worker; the bell view itself only shows the latest N. */
    @Query("DELETE FROM notification_history WHERE created_at < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
