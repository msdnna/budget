package website.msdnna.budget_app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.Calendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.*
import website.msdnna.budget_app.data.preferences.CategoryUsage
import website.msdnna.budget_app.data.preferences.sortedByRecentUse
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.data.repository.TransactionRepository
import website.msdnna.budget_app.data.sync.SyncWorker

data class IncomeUiState(
    val transactions: List<Transaction> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

class IncomeViewModel(private val serverUrl: String) : ViewModel() {
    private val now = Calendar.getInstance()

    private val _filterCats = MutableStateFlow<Set<String>>(emptySet())
    private val _filterFrom = MutableStateFlow<String?>(null)
    private val _filterTo = MutableStateFlow<String?>(null)
    private val _filterDeposit = MutableStateFlow<String?>(null)
    private val _filterIncludeSplit = MutableStateFlow(false)
    private val _ibYear = MutableStateFlow(now.get(Calendar.YEAR))
    private val _ibMonth = MutableStateFlow(now.get(Calendar.MONTH) + 1)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())

    val filterCats = _filterCats.asStateFlow()
    val filterFrom = _filterFrom.asStateFlow()
    val filterTo = _filterTo.asStateFlow()
    val filterDeposit = _filterDeposit.asStateFlow()
    val filterIncludeSplit = _filterIncludeSplit.asStateFlow()
    val selectedIds = _selectedIds.asStateFlow()
    val categories: StateFlow<List<Category>> = combine(
        CategoryRepository.income,
        CategoryUsage.usage,
    ) { cats, usage -> cats.sortedByRecentUse(usage["income"].orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val ibYear = _ibYear.asStateFlow()
    val ibMonth = _ibMonth.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<IncomeUiState> = combine(
        _filterCats, _filterFrom, _filterTo, _filterDeposit, _filterIncludeSplit,
    ) { c, f, t, d, s -> FilterTuple(c, f, t, d, s) }
        .flatMapLatest { (cats, from, to, deposit, includeSplit) ->
            TransactionRepository.observeFiltered(
                type = "income",
                categories = cats.takeIf { it.isNotEmpty() },
                from = from,
                to = to,
                deposit = deposit,
                includeSplit = includeSplit,
            ).map { txs -> IncomeUiState(transactions = txs, total = txs.size, loading = false) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IncomeUiState(loading = true))

    private data class FilterTuple(
        val cats: Set<String>,
        val from: String?,
        val to: String?,
        val deposit: String?,
        val includeSplit: Boolean,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val ibByDeposit: StateFlow<Map<String, Transaction>> = combine(_ibYear, _ibMonth) { y, m -> y to m }
        .flatMapLatest { (y, m) ->
            val from = "%04d-%02d-01".format(y, m)
            val lastDay = Calendar.getInstance()
                .apply { set(y, m - 1, 1) }
                .getActualMaximum(Calendar.DAY_OF_MONTH)
            val to = "%04d-%02d-%02d".format(y, m, lastDay)
            TransactionRepository.observeFiltered(
                type = "initial_balance",
                from = from,
                to = to,
            ).map { rows ->
                // Newest-per-deposit. Repo orders desc by date; the first
                // match per scope is the "current" record. Empty-deposit
                // legacy rows collapse to "bank" (mirrors NormalizeDeposit
                // on the server side).
                val out = mutableMapOf<String, Transaction>()
                for (tx in rows) {
                    val dep = tx.deposit.ifBlank { "bank" }
                    if (!out.containsKey(dep)) out[dep] = tx
                }
                out.toMap()
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _page = MutableStateFlow(1)
    val page = _page.asStateFlow()

    fun reload() {
        SyncWorker.enqueue(AppContainer.appContext)
    }

    fun toggleFilterCategory(name: String) {
        val cur = _filterCats.value
        _filterCats.value = if (name in cur) cur - name else cur + name
        _page.value = 1
    }
    fun clearFilterCategories() {
        _filterCats.value = emptySet()
        _page.value = 1
    }

    /** Replace the active category filter set in one shot. Used by the
     *  Statistics → Income drilldown when the user taps an income source
     *  slice on the donut. */
    fun setFilterCategories(names: Set<String>) {
        _filterCats.value = names
        _page.value = 1
    }
    fun setDateRange(from: String?, to: String?) {
        _filterFrom.value = from
        _filterTo.value = to
        _page.value = 1
    }
    fun setFilterDeposit(deposit: String?) {
        _filterDeposit.value = deposit?.takeIf { it.isNotBlank() }
        _page.value = 1
    }

    fun setFilterIncludeSplit(value: Boolean) {
        _filterIncludeSplit.value = value
        _page.value = 1
    }

    /** Clears every filter knob in one go — categories, date range, deposit
     *  scope, split-parent visibility. Used by the «Сбросить» button at the
     *  bottom of the filter card and by long-pressing the funnel icon in the
     *  top app bar. */
    fun resetFilters() {
        _filterCats.value = emptySet()
        _filterFrom.value = null
        _filterTo.value = null
        _filterDeposit.value = null
        _filterIncludeSplit.value = false
        _page.value = 1
    }
    fun loadMore() { /* no-op: Room observes full list */ }

    fun ibNavigateBack() {
        if (_ibMonth.value == 1) {
            _ibMonth.value = 12
            _ibYear.value--
        } else _ibMonth.value--
    }

    fun ibNavigateForward() {
        if (_ibMonth.value == 12) {
            _ibMonth.value = 1
            _ibYear.value++
        } else _ibMonth.value++
    }

    /** Set the IB period directly — used by the tile picker that replaced
     *  the prev/next arrows in the IB card header. */
    fun selectIbMonth(year: Int, month: Int) {
        _ibYear.value = year
        _ibMonth.value = month
    }

    /**
     * Saves an initial-balance amount for the given deposit scope. Upserts
     * the existing record if one exists this month, otherwise creates a
     * fresh one. Callers from the deposit-tabbed sheet invoke this once per
     * scope they want to change.
     */
    fun saveInitialBalance(amount: Double, deposit: String = "bank") {
        viewModelScope.launch {
            val year = _ibYear.value
            val month = _ibMonth.value
            val date = "%04d-%02d-01".format(year, month)
            val current = ibByDeposit.value[deposit]
            if (current != null) {
                TransactionRepository.update(current.id, amount = amount, deposit = deposit)
            } else {
                TransactionRepository.create(
                    type = "initial_balance",
                    amount = amount,
                    date = date,
                    category = "Начальный баланс",
                    deposit = deposit,
                )
            }
        }
    }

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

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun bulkDeleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            TransactionRepository.bulkDelete(ids)
            _selectedIds.value = emptySet()
        }
    }

    fun bulkSetHiddenSelected(targetHidden: Boolean) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            TransactionRepository.bulkSetHidden(ids, targetHidden)
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
                deposit = req.deposit,
            )
            if (req.type == "income") CategoryUsage.recordUse("income", req.category)
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
            deposit = req.deposit,
            createdBy = req.createdBy,
        )
        if (!req.category.isNullOrBlank()) CategoryUsage.recordUse("income", req.category)
    }

    suspend fun getUsers(): List<UserInfo> = runCatching {
        RetrofitClient.getService(serverUrl).getUsers()
    }.getOrDefault(emptyList())

    /**
     * Split an income transaction into N children across deposits via the
     * server endpoint. Online-only: the server is the source of truth for the
     * atomic op; once it returns, we kick a sync pull so Room mirrors the new
     * children + the parent's `excluded_from_stats=true` flip. Returns null on
     * failure so the caller can surface a Snackbar.
     */
    suspend fun splitTransaction(id: String, parts: List<SplitPart>): Throwable? = runCatching {
        RetrofitClient.getService(serverUrl).splitTransaction(id, SplitRequest(parts))
        SyncWorker.enqueue(AppContainer.appContext)
        null
    }.getOrElse { it }

    /**
     * Reverse a previous split — soft-deletes every child and restores the
     * parent's visibility. Same online-only contract as [splitTransaction].
     */
    suspend fun unsplitTransaction(id: String): Throwable? = runCatching {
        RetrofitClient.getService(serverUrl).unsplitTransaction(id)
        SyncWorker.enqueue(AppContainer.appContext)
        null
    }.getOrElse { it }

    suspend fun addCategory(name: String): Category? =
        CategoryRepository.addCategory(serverUrl, "income", name)

    suspend fun deleteCategory(id: String) {
        val deleted = categories.value.find { it.id == id }
        CategoryRepository.deleteCategory(serverUrl, "income", id)
        if (deleted != null) _filterCats.value = _filterCats.value - deleted.name
    }

    companion object {
        fun factory(serverUrl: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                IncomeViewModel(serverUrl) as T
        }
    }
}
