package website.msdnna.budget_app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.db.SyncStatus
import website.msdnna.budget_app.data.db.TransactionEntity
import website.msdnna.budget_app.data.db.toEntity
import website.msdnna.budget_app.data.db.toModel
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.sync.SyncWorker
import java.time.Instant
import java.util.UUID

/**
 * Offline-first store for transactions. Reads come from Room as a Flow so the
 * UI updates instantly after every local mutation. Writes update Room with a
 * pending sync_status and request a [SyncWorker] run; the worker eventually
 * pushes them to the server (or marks them as conflicts).
 */
object TransactionRepository {
    private val dao get() = AppContainer.db.transactions()

    fun observeAll(): Flow<List<Transaction>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeFiltered(
        type: String? = null,
        categories: Set<String>? = null,
        from: String? = null,
        to: String? = null,
        includeDetailed: Boolean = false,
    ): Flow<List<Transaction>> =
        dao.observeFiltered(type, from, to, includeDetailed).map { list ->
            val filtered = if (categories.isNullOrEmpty()) list
            else list.filter { it.category in categories }
            filtered.map { it.toModel() }
        }

    fun observeConflicts(): Flow<List<Transaction>> =
        dao.observeConflicts().map { list -> list.map { it.toModel() } }

    fun observeConflictCount(): Flow<Int> = dao.observeConflictCount()

    suspend fun create(
        type: String,
        amount: Double,
        date: String,
        category: String,
        source: String? = null,
        purpose: String? = null,
        description: String? = null,
    ): Transaction {
        val now = Instant.now().toString()
        val user = currentUser()
        val entity = TransactionEntity(
            id = UUID.randomUUID().toString(),
            type = type,
            amount = amount,
            date = date,
            category = category,
            source = source,
            purpose = purpose,
            description = description,
            hidden = false,
            createdById = user?.userId,
            createdByName = user?.displayName,
            createdByAvatar = user?.avatarUrl,
            lastModifiedById = user?.userId,
            lastModifiedByName = user?.displayName,
            lastModifiedByAvatar = user?.avatarUrl,
            createdAt = now,
            version = 0,
            updatedAt = now,
            deletedAt = null,
            syncStatus = SyncStatus.PENDING_CREATE,
        )
        dao.upsert(entity)
        SyncWorker.enqueue(AppContainer.appContext)
        return entity.toModel()
    }

    /** Apply a partial update locally and queue a server push. */
    suspend fun update(
        id: String,
        amount: Double? = null,
        date: String? = null,
        category: String? = null,
        source: String? = null,
        purpose: String? = null,
        description: String? = null,
        hidden: Boolean? = null,
        createdBy: UserInfo? = null,
    ): Transaction? {
        val existing = dao.findById(id) ?: return null
        val newStatus = when (existing.syncStatus) {
            SyncStatus.PENDING_CREATE -> SyncStatus.PENDING_CREATE
            else -> SyncStatus.PENDING_UPDATE
        }
        val user = currentUser()
        val updated = existing.copy(
            amount = amount ?: existing.amount,
            date = date ?: existing.date,
            category = category ?: existing.category,
            source = source ?: existing.source,
            purpose = purpose ?: existing.purpose,
            description = description ?: existing.description,
            hidden = hidden ?: existing.hidden,
            createdById = createdBy?.userId ?: existing.createdById,
            createdByName = createdBy?.displayName ?: existing.createdByName,
            createdByAvatar = createdBy?.avatarUrl ?: existing.createdByAvatar,
            lastModifiedById = user?.userId ?: existing.lastModifiedById,
            lastModifiedByName = user?.displayName ?: existing.lastModifiedByName,
            lastModifiedByAvatar = user?.avatarUrl ?: existing.lastModifiedByAvatar,
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
            // Never made it to the server — purge locally without queueing a push.
            dao.deleteHard(id)
            return
        }
        dao.update(existing.copy(
            syncStatus = SyncStatus.PENDING_DELETE,
            updatedAt = Instant.now().toString(),
        ))
        SyncWorker.enqueue(AppContainer.appContext)
    }

    /**
     * Flip `hidden` on every id in [ids] in a single SQL statement, then
     * enqueue one sync. Avoids the N-emit thrash when multi-select toggled
     * 4–5 cards at once.
     */
    suspend fun bulkSetHidden(ids: Collection<String>, hidden: Boolean) {
        if (ids.isEmpty()) return
        val user = currentUser()
        dao.bulkSetHidden(
            ids = ids.toList(),
            hidden = hidden,
            updatedAt = Instant.now().toString(),
            userId = user?.userId,
            userName = user?.displayName,
            userAvatar = user?.avatarUrl,
        )
        SyncWorker.enqueue(AppContainer.appContext)
    }

    /** Bulk soft-delete in one Room transaction → one Flow emission. */
    suspend fun bulkDelete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        dao.bulkDelete(ids = ids.toList(), updatedAt = Instant.now().toString())
        SyncWorker.enqueue(AppContainer.appContext)
    }

    /** Replace the local row from the server-fetched [Transaction]. Used by ad-hoc refreshers. */
    suspend fun upsertFromRemote(t: Transaction) {
        dao.upsert(t.toEntity(SyncStatus.SYNCED))
    }
}

