package website.msdnna.budget_app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.*
import java.util.Calendar

enum class StatsPeriod { MONTH, YEAR }

data class StatsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val summary: StatsSummary = StatsSummary(),
    val expenseByCategory: List<CategoryStat> = emptyList(),
    val incomeByCategory: List<CategoryStat> = emptyList(),
    val monthly: List<MonthlyStat> = emptyList()
)

class StatisticsViewModel(serverUrl: String) : ViewModel() {
    private val service = RetrofitClient.getService(serverUrl)
    private val now = Calendar.getInstance()

    private val _period = MutableStateFlow(StatsPeriod.MONTH)
    private val _year   = MutableStateFlow(now.get(Calendar.YEAR))
    private val _month  = MutableStateFlow(now.get(Calendar.MONTH) + 1)
    private val _refreshTick = MutableStateFlow(0)
    private val _state  = MutableStateFlow(StatsUiState())

    val period = _period.asStateFlow()
    val year   = _year.asStateFlow()
    val month  = _month.asStateFlow()
    val state  = _state.asStateFlow()

    private data class Inputs(val period: StatsPeriod, val year: Int, val month: Int)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val pipeline = combine(_period, _year, _month, _refreshTick) { p, y, m, _ ->
        Inputs(p, y, m)
    }
        .flatMapLatest { (p, y, m) -> loadFlow(p, y, m) }
        .onEach { _state.value = it }
        .launchIn(viewModelScope)

    fun setPeriod(p: StatsPeriod) { _period.value = p }

    fun navigateBack() {
        if (_period.value == StatsPeriod.MONTH) {
            if (_month.value == 1) { _month.value = 12; _year.value-- } else _month.value--
        } else {
            _year.value--
        }
    }

    fun navigateForward() {
        if (_period.value == StatsPeriod.MONTH) {
            if (_month.value == 12) { _month.value = 1; _year.value++ } else _month.value++
        } else {
            _year.value++
        }
    }

    fun reload() { _refreshTick.value += 1 }

    private fun loadFlow(period: StatsPeriod, year: Int, month: Int): Flow<StatsUiState> = flow {
        emit(_state.value.copy(loading = true, error = null))
        val mon = if (period == StatsPeriod.MONTH) "%04d-%02d".format(year, month) else null
        val yr  = if (period == StatsPeriod.YEAR)  year.toString() else null
        try {
            val result = withRetry {
                // Single call replaces 4 sequential ones (summary + by-category × 2 + monthly).
                // Backend runs the queries in parallel via errgroup.
                val o = service.getStatisticsOverview(month = mon, year = yr)
                StatsUiState(false, null, o.summary, o.expenseByCategory, o.incomeByCategory, o.monthly)
            }
            emit(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(_state.value.copy(loading = false, error = e.localizedMessage ?: "Ошибка загрузки"))
        }
    }

    companion object {
        fun factory(serverUrl: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                StatisticsViewModel(serverUrl) as T
        }
    }
}
