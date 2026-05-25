package website.msdnna.budget_app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.*

enum class StatsPeriod { MONTH, YEAR, RANGE }

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
    private val _year = MutableStateFlow(now.get(Calendar.YEAR))
    private val _month = MutableStateFlow(now.get(Calendar.MONTH) + 1)
    private val _from = MutableStateFlow<String?>(null)
    private val _to = MutableStateFlow<String?>(null)
    private val _deposit = MutableStateFlow<String?>(null)
    private val _refreshTick = MutableStateFlow(0)
    private val _state = MutableStateFlow(StatsUiState())

    val period = _period.asStateFlow()
    val year = _year.asStateFlow()
    val month = _month.asStateFlow()
    val from = _from.asStateFlow()
    val to = _to.asStateFlow()
    val deposit = _deposit.asStateFlow()
    val state = _state.asStateFlow()

    private data class Inputs(
        val period: StatsPeriod,
        val year: Int,
        val month: Int,
        val from: String?,
        val to: String?,
        val deposit: String?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val pipeline = combine(
        combine(_period, _year, _month) { p, y, m -> Triple(p, y, m) },
        combine(_from, _to, _deposit) { f, t, d -> Triple(f, t, d) },
        _refreshTick,
    ) { pym, ftd, _ ->
        Inputs(pym.first, pym.second, pym.third, ftd.first, ftd.second, ftd.third)
    }
        .flatMapLatest { loadFlow(it) }
        .onEach { _state.value = it }
        .launchIn(viewModelScope)

    fun setPeriod(p: StatsPeriod) {
        _period.value = p
        // Initialise range when first switching to RANGE so the UI has something to show.
        if (p == StatsPeriod.RANGE && (_from.value == null || _to.value == null)) {
            val cal = Calendar.getInstance()
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            cal.set(y, m - 1, 1)
            val first = "%04d-%02d-01".format(y, m)
            val last = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            _from.value = first
            _to.value = "%04d-%02d-%02d".format(y, m, last)
        }
    }

    fun selectMonth(year: Int, month: Int) {
        _year.value = year
        _month.value = month
    }

    fun selectYear(year: Int) {
        _year.value = year
    }

    fun selectRange(from: String?, to: String?) {
        _from.value = from
        _to.value = to
    }

    fun selectDeposit(value: String?) {
        _deposit.value = value?.takeIf { it.isNotBlank() }
    }

    /** Drops the deposit filter. The period selector intentionally stays —
     *  Statistics is always scoped to some period (it's the primary axis),
     *  so resetting to "all time" would be confusing. */
    fun resetFilters() {
        _deposit.value = null
    }

    fun reload() {
        _refreshTick.value += 1
    }

    private fun loadFlow(inputs: Inputs): Flow<StatsUiState> = flow {
        emit(_state.value.copy(loading = true, error = null))
        val mon = if (inputs.period == StatsPeriod.MONTH)
            "%04d-%02d".format(inputs.year, inputs.month) else null
        val yr = if (inputs.period == StatsPeriod.YEAR)
            inputs.year.toString() else null
        val (fromArg, toArg) = if (inputs.period == StatsPeriod.RANGE)
            inputs.from to inputs.to else null to null
        // Skip the network call when RANGE is incomplete.
        if (inputs.period == StatsPeriod.RANGE && (fromArg == null || toArg == null)) {
            emit(_state.value.copy(loading = false, error = null))
            return@flow
        }
        try {
            val result = withRetry {
                val o = service.getStatisticsOverview(
                    month = mon,
                    year = yr,
                    from = fromArg,
                    to = toArg,
                    deposit = inputs.deposit,
                )
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
