package website.msdnna.budget_app.data.repository

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.db.SyncStatus
import website.msdnna.budget_app.data.db.WishlistEntity
import website.msdnna.budget_app.data.db.toEntity
import website.msdnna.budget_app.data.db.toModel
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.model.WishlistItem
import website.msdnna.budget_app.data.sync.SyncWorker

object WishlistRepository {
    private val dao get() = AppContainer.db.wishlist()

    fun observeAll(): Flow<List<WishlistItem>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeConflicts(): Flow<List<WishlistItem>> =
        dao.observeConflicts().map { list -> list.map { it.toModel() } }

    fun observeConflictCount(): Flow<Int> = dao.observeConflictCount()

    suspend fun create(
        name: String,
        estimatedCost: Double,
        category: String,
        priority: Int = 5,
        frequency: String = "once",
        deposit: String = "bank",
        notes: String? = null,
    ): WishlistItem {
        val now = Instant.now().toString()
        val currentUser = currentUser()
        val entity = WishlistEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            estimatedCost = estimatedCost,
            category = category,
            priority = priority,
            frequency = frequency,
            purchased = false,
            notes = notes,
            createdById = currentUser?.userId,
            createdByName = currentUser?.displayName,
            createdByAvatar = currentUser?.avatarUrl,
            lastModifiedById = currentUser?.userId,
            lastModifiedByName = currentUser?.displayName,
            lastModifiedByAvatar = currentUser?.avatarUrl,
            createdAt = now,
            version = 0,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_CREATE,
            deposit = deposit.ifBlank { "bank" },
        )
        dao.upsert(entity)
        SyncWorker.enqueue(AppContainer.appContext)
        return entity.toModel()
    }

    suspend fun update(
        id: String,
        name: String? = null,
        estimatedCost: Double? = null,
        category: String? = null,
        priority: Int? = null,
        frequency: String? = null,
        purchased: Boolean? = null,
        deposit: String? = null,
        notes: String? = null,
        createdBy: UserInfo? = null,
    ): WishlistItem? {
        val existing = dao.findById(id) ?: return null
        val newStatus = when (existing.syncStatus) {
            SyncStatus.PENDING_CREATE -> SyncStatus.PENDING_CREATE
            else -> SyncStatus.PENDING_UPDATE
        }
        val currentUser = currentUser()
        val updated = existing.copy(
            name = name ?: existing.name,
            estimatedCost = estimatedCost ?: existing.estimatedCost,
            category = category ?: existing.category,
            priority = priority ?: existing.priority,
            frequency = frequency ?: existing.frequency,
            purchased = purchased ?: existing.purchased,
            deposit = deposit?.ifBlank { "bank" } ?: existing.deposit,
            notes = notes ?: existing.notes,
            createdById = createdBy?.userId ?: existing.createdById,
            createdByName = createdBy?.displayName ?: existing.createdByName,
            createdByAvatar = createdBy?.avatarUrl ?: existing.createdByAvatar,
            lastModifiedById = currentUser?.userId ?: existing.lastModifiedById,
            lastModifiedByName = currentUser?.displayName ?: existing.lastModifiedByName,
            lastModifiedByAvatar = currentUser?.avatarUrl ?: existing.lastModifiedByAvatar,
            updatedAt = Instant.now().toString(),
            syncStatus = newStatus,
        )
        dao.update(updated)
        SyncWorker.enqueue(AppContainer.appContext)
        return updated.toModel()
    }

    suspend fun delete(id: String) {
        val existing = dao.findById(id) ?: return
        if (existing.syncStatus == SyncStatus.PENDING_CREATE) {
            dao.deleteHard(id)
            return
        }
        dao.update(
            existing.copy(
                syncStatus = SyncStatus.PENDING_DELETE,
                updatedAt = Instant.now().toString(),
            )
        )
        SyncWorker.enqueue(AppContainer.appContext)
    }

    /** Bulk-flip `purchased` in one SQL statement → one Flow emission. */
    suspend fun bulkSetPurchased(ids: Collection<String>, purchased: Boolean) {
        if (ids.isEmpty()) return
        val user = currentUser()
        dao.bulkSetPurchased(
            ids = ids.toList(),
            purchased = purchased,
            updatedAt = Instant.now().toString(),
            userId = user?.userId,
            userName = user?.displayName,
            userAvatar = user?.avatarUrl,
        )
        SyncWorker.enqueue(AppContainer.appContext)
    }

    suspend fun bulkDelete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        dao.bulkDelete(ids = ids.toList(), updatedAt = Instant.now().toString())
        SyncWorker.enqueue(AppContainer.appContext)
    }

    suspend fun upsertFromRemote(w: WishlistItem) {
        dao.upsert(w.toEntity(SyncStatus.SYNCED))
    }
}
