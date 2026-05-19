package website.msdnna.budget_app.data.repository

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.db.SyncStatus
import website.msdnna.budget_app.data.db.toEntity
import website.msdnna.budget_app.data.db.toModel
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.sync.SyncWorker

/**
 * Categories backed by Room. The three section flows ([expense], [income],
 * [wishlist]) are kept for backwards compatibility with the existing UI; they
 * derive from the single underlying DAO query so adds/deletes propagate
 * everywhere automatically.
 *
 * On a cold start [loadAll] is still called from MainActivity to warm the
 * server-side state into Room — the SyncEngine pull will overlap, but the
 * direct call gives a quicker first-paint when there's connectivity.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
object CategoryRepository {
    private val dao get() = AppContainer.db.categories()

    private val sectionsFlow: StateFlow<Map<String, List<Category>>> by lazy {
        dao.observeAll()
            .map { rows -> rows.groupBy({ it.section }, { it.toModel() }) }
            .stateIn(GlobalScope, SharingStarted.Eagerly, emptyMap())
    }

    val expense: StateFlow<List<Category>> by lazy {
        sectionsFlow.map { it["expense"].orEmpty() }
            .stateIn(GlobalScope, SharingStarted.Eagerly, emptyList())
    }
    val income: StateFlow<List<Category>> by lazy {
        sectionsFlow.map { it["income"].orEmpty() }
            .stateIn(GlobalScope, SharingStarted.Eagerly, emptyList())
    }
    val wishlist: StateFlow<List<Category>> by lazy {
        sectionsFlow.map { it["wishlist"].orEmpty() }
            .stateIn(GlobalScope, SharingStarted.Eagerly, emptyList())
    }

    fun observeBySection(section: String): Flow<List<Category>> =
        dao.observeBySection(section).map { list -> list.map { it.toModel() } }

    /** Best-effort warm of Room from the server. Failures are silent. */
    suspend fun loadAll(serverUrl: String) {
        try {
            val all = RetrofitClient.getService(serverUrl).getAllCategories()
            val entities = (all.expense + all.income + all.wishlist)
                .map { it.toEntity(SyncStatus.SYNCED) }
            dao.upsertAll(entities)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Categories load is best-effort; UI renders cached/empty lists if it fails.
        }
    }

    suspend fun addCategory(serverUrl: String, section: String, name: String): Category? {
        val current = when (section) {
            "expense" -> expense.value
            "income" -> income.value
            "wishlist" -> wishlist.value
            else -> return null
        }
        current.find { it.name == name }?.let { return it }

        val now = Instant.now().toString()
        val entity = website.msdnna.budget_app.data.db.CategoryEntity(
            id = UUID.randomUUID().toString(),
            section = section,
            name = name,
            isDefault = false,
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

    suspend fun deleteCategory(serverUrl: String, section: String, id: String) {
        val existing = dao.findById(id) ?: return
        if (existing.isDefault) return
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

    /**
     * Admin-only PATCH on `/api/categories/:id` for the monthly_limit field.
     * Online-only: the sync engine doesn't carry partial updates for
     * categories, and only admins can mutate this anyway. Pass a number to
     * set, or null to clear (sends a literal `null` so the backend's
     * NullableFloat unmarshaler treats it as "explicit clear", not
     * "leave unchanged"). Updates Room on success so the bar refreshes
     * immediately without waiting for the next sync pull.
     */
    suspend fun patchMonthlyLimit(serverUrl: String, id: String, newLimit: Double?): Category? {
        // Manual JSON build so Gson doesn't drop the `null` value. The
        // backend uses `{"monthly_limit": null}` to mean "clear" and
        // `{"monthly_limit": N}` to mean "set to N"; absent key = leave
        // unchanged (which we never want here — every call to this
        // function is an explicit user action on the limit field).
        val literal = newLimit?.let { it.toString() } ?: "null"
        val body = """{"monthly_limit":$literal}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val updated = try {
            RetrofitClient.getService(serverUrl).patchCategory(id, body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        dao.upsert(updated.toEntity(SyncStatus.SYNCED))
        return updated
    }
}
