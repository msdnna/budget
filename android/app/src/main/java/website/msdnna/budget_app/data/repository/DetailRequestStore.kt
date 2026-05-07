package website.msdnna.budget_app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.CreateDetailRequestPayload
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.DetailRequest
import website.msdnna.budget_app.data.model.DetailRequestView
import website.msdnna.budget_app.data.model.Transaction

/**
 * In-memory store for detail-requests. Detail-requests are online-only by
 * design (the user explicitly requested an error if you try to create one
 * offline), so we don't persist them in Room — we just keep the latest
 * server-known list as a StateFlow that the UI observes.
 */
object DetailRequestStore {
    private val _items = MutableStateFlow<List<DetailRequest>>(emptyList())
    val items: StateFlow<List<DetailRequest>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private suspend fun api() = RetrofitClient.getService(currentServerUrl())

    /** Most recently saved server URL — taken from prefs. */
    private suspend fun currentServerUrl(): String {
        val prefs = AppContainer.prefs
        return prefs.serverUrl.first().orEmpty()
    }

    suspend fun refresh() {
        _loading.value = true
        try {
            _items.value = api().listDetailRequests()
        } catch (_: Exception) {
            // Network may be down. Keep last known list.
        } finally {
            _loading.value = false
        }
    }

    fun myOpen(userId: String): List<DetailRequest> = _items.value.filter {
        it.status == "open" && it.assignee?.userId == userId
    }

    fun byParentTxId(txId: String): DetailRequest? = _items.value.firstOrNull {
        it.parentTransactionId == txId
    }

    suspend fun create(transactionId: String, assigneeId: String): DetailRequest {
        val out = api().createDetailRequest(CreateDetailRequestPayload(transactionId, assigneeId))
        refresh()
        return out
    }

    suspend fun get(id: String): DetailRequestView = api().getDetailRequest(id)

    suspend fun addChild(id: String, req: CreateTransactionRequest): Transaction =
        api().addDetailRequestChild(id, req)

    suspend fun close(id: String) {
        api().closeDetailRequest(id)
        refresh()
    }

    suspend fun cancel(id: String) {
        api().cancelDetailRequest(id)
        refresh()
    }
}
