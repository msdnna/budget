package website.msdnna.budget_app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.LimitsProgressResponse

/**
 * In-memory snapshot of `/api/categories/limits-progress`, refreshed after
 * sync pulls + on demand from CategoryLimitsScreen / ExpensesScreen. We
 * don't cache this in Room — the values are derived from server-side
 * monthly aggregations that we'd have to recompute locally for offline
 * accuracy, and the screens already gate on connectivity. Offline reads
 * surface the last successful payload (read-only), which is acceptable
 * because the limit window is fixed per calendar month.
 */
object LimitsProgressRepository {
    private val _state = MutableStateFlow<LimitsProgressResponse?>(null)
    val state: StateFlow<LimitsProgressResponse?> = _state.asStateFlow()

    /** Best-effort refresh; failures keep the previous snapshot intact. */
    suspend fun refresh(serverUrl: String, month: String? = null) {
        try {
            val resp = RetrofitClient.getService(serverUrl).getLimitsProgress(month)
            _state.value = resp
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Stale snapshot stays; the bar simply doesn't move.
        }
    }

    fun clear() {
        _state.value = null
    }
}
