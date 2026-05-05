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
import website.msdnna.budget_app.data.repository.CategoryRepository
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

    val selectedIds = _selectedIds.asStateFlow()
    val categories  = CategoryRepository.wishlist

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
            ids.forEach { WishlistRepository.delete(it) }
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
            ids.forEach { WishlistRepository.update(it, purchased = targetPurchased) }
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
    }

    suspend fun getUsers(): List<UserInfo> =
        runCatching { service.getUsers() }.getOrDefault(emptyList())

    suspend fun addCategory(name: String): Category? =
        CategoryRepository.addCategory(serverUrl, "wishlist", name)

    suspend fun deleteCategory(id: String) {
        CategoryRepository.deleteCategory(serverUrl, "wishlist", id)
    }

    companion object {
        fun factory(serverUrl: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ForecastViewModel(serverUrl) as T
        }
    }
}
