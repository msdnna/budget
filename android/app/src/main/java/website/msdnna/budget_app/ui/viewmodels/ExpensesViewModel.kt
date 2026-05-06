package website.msdnna.budget_app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.*
import website.msdnna.budget_app.data.preferences.CategoryUsage
import website.msdnna.budget_app.data.preferences.sortedByRecentUse
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.data.repository.TransactionRepository
import website.msdnna.budget_app.data.sync.SyncWorker
import website.msdnna.budget_app.data.AppContainer

data class ExpensesUiState(
    val transactions: List<Transaction> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * Reads transactions from Room (offline-first) so the UI shows whatever was
 * last synced even when the network is gone. Mutations go through
 * [TransactionRepository], which marks rows pending and asks [SyncWorker] to
 * push them when connectivity allows.
 */
class ExpensesViewModel(private val serverUrl: String) : ViewModel() {

    private val _filterCats  = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val filterCats  = _filterCats.asStateFlow()
    val selectedIds = _selectedIds.asStateFlow()
    val categories: StateFlow<List<Category>> = combine(
        CategoryRepository.expense,
        CategoryUsage.usage,
    ) { cats, usage -> cats.sortedByRecentUse(usage["expense"].orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ExpensesUiState> = _filterCats
        .flatMapLatest { cats ->
            TransactionRepository.observeFiltered(
                type = "expense",
                categories = cats.takeIf { it.isNotEmpty() },
            ).map { txs -> ExpensesUiState(transactions = txs, total = txs.size, loading = false) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExpensesUiState(loading = true))

    // Keep page state for compatibility with existing UI scaffolding; pagination is
    // a no-op now since Room observes the whole list.
    private val _page = MutableStateFlow(1)
    val page = _page.asStateFlow()

    fun reload() {
        // Trigger a sync; the Flow from Room will update once the pull writes new rows.
        SyncWorker.enqueue(AppContainer.appContext)
    }

    fun toggleFilterCategory(name: String) {
        val cur = _filterCats.value
        _filterCats.value = if (name in cur) cur - name else cur + name
        _page.value = 1
    }
    fun clearFilterCategories() { _filterCats.value = emptySet(); _page.value = 1 }
    fun loadMore() { /* no-op: full list is always loaded from Room */ }

    fun deleteTransaction(id: String) {
        viewModelScope.launch { TransactionRepository.delete(id) }
    }

    fun toggleHidden(id: String, hidden: Boolean) {
        viewModelScope.launch { TransactionRepository.update(id, hidden = !hidden) }
    }

    fun startSelection(id: String) {
        _selectedIds.value = setOf(id)
    }

    fun toggleSelection(id: String) {
        val cur = _selectedIds.value
        _selectedIds.value = if (id in cur) cur - id else cur + id
    }

    fun clearSelection() { _selectedIds.value = emptySet() }

    fun bulkDeleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { TransactionRepository.delete(it) }
            _selectedIds.value = emptySet()
        }
    }

    /** If every selected record is currently hidden — show them all. Otherwise hide them all. */
    fun bulkSetHiddenSelected(targetHidden: Boolean) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { TransactionRepository.update(it, hidden = targetHidden) }
            _selectedIds.value = emptySet()
        }
    }

    fun createTransaction(req: CreateTransactionRequest) {
        viewModelScope.launch {
            TransactionRepository.create(
                type = req.type,
                amount = req.amount,
                date = req.date,
                category = req.category,
                source = req.source,
                purpose = req.purpose,
                description = req.description,
            )
            CategoryUsage.recordUse("expense", req.category)
        }
    }

    suspend fun updateTransaction(id: String, req: UpdateTransactionRequest) {
        TransactionRepository.update(
            id = id,
            amount = req.amount,
            date = req.date,
            category = req.category,
            source = req.source,
            purpose = req.purpose,
            description = req.description,
            createdBy = req.createdBy,
        )
        if (!req.category.isNullOrBlank()) CategoryUsage.recordUse("expense", req.category)
    }

    suspend fun getUsers(): List<UserInfo> = runCatching {
        RetrofitClient.getService(serverUrl).getUsers()
    }.getOrDefault(emptyList())

    suspend fun addCategory(name: String): Category? =
        CategoryRepository.addCategory(serverUrl, "expense", name)

    suspend fun deleteCategory(id: String) {
        val deleted = categories.value.find { it.id == id }
        CategoryRepository.deleteCategory(serverUrl, "expense", id)
        if (deleted != null) _filterCats.value = _filterCats.value - deleted.name
    }

    companion object {
        fun factory(serverUrl: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ExpensesViewModel(serverUrl) as T
        }
    }
}
