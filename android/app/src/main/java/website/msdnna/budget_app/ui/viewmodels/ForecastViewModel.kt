package website.msdnna.budget_app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.*
import website.msdnna.budget_app.data.preferences.CategoryUsage
import website.msdnna.budget_app.data.preferences.sortedByRecentUse
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.data.repository.TransactionRepository
import website.msdnna.budget_app.data.repository.WishlistRepository

data class ForecastUiState(
    val forecast: ForecastData? = null,
    val wishlist: List<WishlistItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

/**
 * Wishlist comes from Room (offline-first); the forecast is a server-side
 * aggregation we still fetch via the API. When offline the forecast goes
 * empty/stale but the wishlist still works thanks to the local cache.
 */
class ForecastViewModel(private val serverUrl: String) : ViewModel() {
    private val service = RetrofitClient.getService(serverUrl)

    private val _refreshTick = MutableStateFlow(0)
    private val _forecast    = MutableStateFlow<ForecastData?>(null)
    private val _forecastLoading = MutableStateFlow(true)
    private val _forecastError   = MutableStateFlow<String?>(null)
    private val _selectedIds     = MutableStateFlow<Set<String>>(emptySet())
    // Separate selection bucket for «Регулярные расходы» — bulk operations
    // there are different (cancel paid / delete) so we keep selections from
    // bleeding into the wishlist FAB and vice versa.
    private val _selectedRegularIds = MutableStateFlow<Set<String>>(emptySet())

    val selectedIds = _selectedIds.asStateFlow()
    val selectedRegularIds = _selectedRegularIds.asStateFlow()
    val categories: StateFlow<List<Category>> = combine(
        CategoryRepository.wishlist,
        CategoryUsage.usage,
    ) { cats, usage -> cats.sortedByRecentUse(usage["wishlist"].orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val forecastJob = _refreshTick
        .flatMapLatest { fetchForecast() }
        .onEach { f -> _forecast.value = f }
        .launchIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ForecastUiState> = combine(
        _forecast, WishlistRepository.observeAll(), _forecastLoading, _forecastError,
    ) { f, w, loading, err ->
        ForecastUiState(forecast = f, wishlist = w, loading = loading, error = err)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ForecastUiState(loading = true))

    private fun fetchForecast(): Flow<ForecastData?> = flow {
        _forecastLoading.value = true
        _forecastError.value = null
        try {
            val f = withRetry { service.getForecast() }
            emit(f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _forecastError.value = e.localizedMessage ?: "Ошибка загрузки"
            emit(null)
        } finally {
            _forecastLoading.value = false
        }
    }

    fun reload() { _refreshTick.value += 1 }

    fun togglePurchased(id: String, purchased: Boolean) {
        viewModelScope.launch {
            WishlistRepository.update(id, purchased = !purchased)
        }
    }

    fun deleteWishlistItem(id: String) {
        viewModelScope.launch { WishlistRepository.delete(id) }
    }

    fun startSelection(id: String) {
        _selectedIds.value = setOf(id)
        _selectedRegularIds.value = emptySet()
    }

    fun toggleSelection(id: String) {
        val cur = _selectedIds.value
        _selectedIds.value = if (id in cur) cur - id else cur + id
    }

    fun clearSelection() { _selectedIds.value = emptySet() }

    // ── Regular-items selection (parallel to wishlist selection above). ──
    fun startRegularSelection(id: String) {
        _selectedRegularIds.value = setOf(id)
        _selectedIds.value = emptySet()
    }

    fun toggleRegularSelection(id: String) {
        val cur = _selectedRegularIds.value
        _selectedRegularIds.value = if (id in cur) cur - id else cur + id
    }

    fun clearRegularSelection() { _selectedRegularIds.value = emptySet() }

    fun bulkDeleteSelectedRegular() {
        val ids = _selectedRegularIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            WishlistRepository.bulkDelete(ids)
            _selectedRegularIds.value = emptySet()
        }
    }

    /** Cancels the current-period link for every selected regular item that
     *  is currently `paid_this_period`. Caller passes the list of paid ids
     *  so the VM doesn't need a snapshot of the forecast. */
    fun bulkCancelRegular(paidIds: Collection<String>) {
        if (paidIds.isEmpty()) return
        viewModelScope.launch {
            paidIds.forEach { id ->
                runCatching { service.unlinkWishlistPeriod(id) }
            }
            _selectedRegularIds.value = emptySet()
            reload()
        }
    }

    fun bulkDeleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            WishlistRepository.bulkDelete(ids)
            _selectedIds.value = emptySet()
        }
    }

    /**
     * Bulk-set the `purchased` flag on the given ids (caller filters out
     * recurring items, which don't expose the purchased concept). Always
     * clears the entire selection afterwards.
     */
    fun bulkSetPurchased(ids: Collection<String>, targetPurchased: Boolean) {
        viewModelScope.launch {
            WishlistRepository.bulkSetPurchased(ids, targetPurchased)
            _selectedIds.value = emptySet()
        }
    }

    fun createWishlistItem(req: CreateWishlistRequest) {
        viewModelScope.launch {
            WishlistRepository.create(
                name = req.name,
                estimatedCost = req.estimatedCost,
                category = req.category,
                priority = req.priority,
                frequency = req.frequency,
                notes = req.notes,
            )
            CategoryUsage.recordUse("wishlist", req.category)
        }
    }

    suspend fun updateWishlistItem(id: String, req: UpdateWishlistRequest) {
        WishlistRepository.update(
            id = id,
            name = req.name,
            estimatedCost = req.estimatedCost,
            category = req.category,
            frequency = req.frequency,
            purchased = req.purchased,
            notes = req.notes,
            createdBy = req.createdBy,
        )
        if (!req.category.isNullOrBlank()) CategoryUsage.recordUse("wishlist", req.category)
    }

    suspend fun getUsers(): List<UserInfo> =
        runCatching { service.getUsers() }.getOrDefault(emptyList())

    suspend fun addCategory(name: String): Category? =
        CategoryRepository.addCategory(serverUrl, "wishlist", name)

    suspend fun deleteCategory(id: String) {
        CategoryRepository.deleteCategory(serverUrl, "wishlist", id)
    }

    /** Categories for the prefilled expense form opened from a recurring item. */
    suspend fun addExpenseCategory(name: String): Category? =
        CategoryRepository.addCategory(serverUrl, "expense", name)

    suspend fun deleteExpenseCategory(id: String) {
        CategoryRepository.deleteCategory(serverUrl, "expense", id)
    }

    val expenseCategories: StateFlow<List<Category>> = combine(
        CategoryRepository.expense,
        CategoryUsage.usage,
    ) { cats, usage -> cats.sortedByRecentUse(usage["expense"].orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Persist a recurring-payment fulfillment: creates a linked expense, then
     *  refreshes the forecast so paid_this_period flips to true. */
    fun markRegularPaid(req: CreateTransactionRequest) {
        viewModelScope.launch {
            TransactionRepository.create(
                type = req.type,
                amount = req.amount,
                date = req.date,
                category = req.category,
                source = req.source,
                purpose = req.purpose,
                description = req.description,
                wishlistId = req.wishlistId.orEmpty(),
            )
            CategoryUsage.recordUse("expense", req.category)
            // Forecast is server-side; reload pulls the new paid_this_period.
            reload()
        }
    }

    /** Unlinks every transaction linked to [wishlistId] in the current period
     *  (server decides the period from the item's frequency). The server-side
     *  bulk update bumps versions; the next sync pull will refresh local rows. */
    fun unlinkRegularPeriod(wishlistId: String, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                service.unlinkWishlistPeriod(wishlistId)
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Не удалось отменить")
            }
        }
    }

    companion object {
        fun factory(serverUrl: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ForecastViewModel(serverUrl) as T
        }
    }
}
