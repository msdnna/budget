package website.msdnna.budget_app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL AND sync_status != :pendingDelete ORDER BY date DESC")
    fun observeAll(pendingDelete: String = SyncStatus.PENDING_DELETE): Flow<List<TransactionEntity>>

    @Query("""
        SELECT * FROM transactions
        WHERE deleted_at IS NULL AND sync_status != :pendingDelete
          AND (:type IS NULL OR type = :type)
          AND (:category IS NULL OR category = :category)
          AND (:from IS NULL OR date >= :from)
          AND (:to IS NULL OR date <= :to)
        ORDER BY date DESC
    """)
    fun observeFiltered(
        type: String?,
        category: String?,
        from: String?,
        to: String?,
        pendingDelete: String = SyncStatus.PENDING_DELETE,
    ): Flow<List<TransactionEntity>>

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
