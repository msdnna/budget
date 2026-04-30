package website.msdnna.budget_app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.CategoryStat
import website.msdnna.budget_app.data.model.MonthlyStat
import website.msdnna.budget_app.data.model.StatsSummary
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.theme.LocalIncomeColor
import website.msdnna.budget_app.ui.theme.generateChartColors
import java.util.Calendar

private enum class Period { MONTH, YEAR }

private data class StatsState(
    val loading: Boolean = true,
    val error: String? = null,
    val summary: StatsSummary = StatsSummary(),
    val expenseByCategory: List<CategoryStat> = emptyList(),
    val incomeByCategory: List<CategoryStat> = emptyList(),
    val monthly: List<MonthlyStat> = emptyList()
)

@Composable
fun StatisticsScreen(serverUrl: String, primaryColor: Color) {
    val service = remember(serverUrl) { RetrofitClient.getService(serverUrl) }
    val scope = rememberCoroutineScope()

    val now = Calendar.getInstance()
    var period by remember { mutableStateOf(Period.MONTH) }
    var year by remember { mutableIntStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(now.get(Calendar.MONTH) + 1) }
    var state by remember { mutableStateOf(StatsState()) }
    var loadKey by remember { mutableIntStateOf(0) }

    fun buildParams(): Triple<String?, String?, String?> {
        return when (period) {
            Period.MONTH -> Triple("%04d-%02d".format(year, month), null, null)
            Period.YEAR  -> Triple(null, year.toString(), null)
        }
    }

    LaunchedEffect(loadKey, period, year, month) {
        state = state.copy(loading = true, error = null)
        val (mon, yr, _) = buildParams()
        try {
            val summary = service.getStatsSummary(month = mon, year = yr)
            val expCat  = service.getByCategory("expense", month = mon, year = yr)
            val incCat  = service.getByCategory("income",  month = mon, year = yr)
            val monthly = service.getMonthlyStats(year = if (period == Period.YEAR) yr else year.toString())
            state = StatsState(false, null, summary, expCat, incCat, monthly)
        } catch (e: Exception) {
            state = state.copy(loading = false, error = e.localizedMessage ?: "Ошибка загрузки")
        }
    }

    val incomeColor  = LocalIncomeColor.current
    val expenseColor = LocalExpenseColor.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Period selector
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Period.values().forEach { p ->
                    FilterChip(
                        selected = period == p,
                        onClick  = { period = p },
                        label    = { Text(if (p == Period.MONTH) "Месяц" else "Год") },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = primaryColor,
                            selectedLabelColor     = Color.White
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                // Navigate period
                IconButton(
                    onClick = {
                        if (period == Period.MONTH) {
                            if (month == 1) { month = 12; year-- } else month--
                        } else year--
                    },
                    modifier = Modifier.size(32.dp)
                ) { Text("‹", fontWeight = FontWeight.Bold) }

                Text(
                    text = if (period == Period.MONTH)
                        "${monthName(month, true)} $year" else year.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                IconButton(
                    onClick = {
                        if (period == Period.MONTH) {
                            if (month == 12) { month = 1; year++ } else month++
                        } else year++
                    },
                    modifier = Modifier.size(32.dp)
                ) { Text("›", fontWeight = FontWeight.Bold) }
            }
        }

        when {
            state.loading -> LoadingView(Modifier.height(300.dp))
            state.error != null -> ErrorView(state.error!!, { loadKey++ }, Modifier.height(300.dp))
            else -> {
                // Summary cards
                val s = state.summary
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(
                        "Доходы", s.totalIncome, "↑", incomeColor,
                        Modifier.weight(1f)
                    )
                    SummaryCard(
                        "Расходы", s.totalExpense, "↓", expenseColor,
                        Modifier.weight(1f)
                    )
                }
                SummaryCard(
                    "Баланс",
                    kotlin.math.abs(s.balance),
                    if (s.balance >= 0) "+" else "−",
                    if (s.balance >= 0) primaryColor else expenseColor,
                    Modifier.fillMaxWidth()
                )

                // Expense pie chart
                if (state.expenseByCategory.isNotEmpty()) {
                    val expColors = generateChartColors(expenseColor, state.expenseByCategory.size)
                    val expSlices = state.expenseByCategory.mapIndexed { i, it ->
                        PieSlice(it.category, it.amount.toFloat(), expColors[i])
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Расходы по категориям", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                DonutChart(
                                    slices = expSlices,
                                    modifier = Modifier.size(130.dp),
                                    centerText = "Расходы"
                                )
                                ChartLegend(expSlices, Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Income pie chart
                if (state.incomeByCategory.isNotEmpty()) {
                    val incColors = generateChartColors(primaryColor, state.incomeByCategory.size)
                    val incSlices = state.incomeByCategory.mapIndexed { i, it ->
                        PieSlice(it.category, it.amount.toFloat(), incColors[i])
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Доходы по источникам", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                DonutChart(
                                    slices = incSlices,
                                    modifier = Modifier.size(130.dp),
                                    centerText = "Доходы"
                                )
                                ChartLegend(incSlices, Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Monthly bar chart
                if (state.monthly.isNotEmpty()) {
                    val sorted = state.monthly.sortedBy { it.month }
                    val entries = sorted.map { m ->
                        BarEntry(monthName(m.month), m.income.toFloat(), m.expense.toFloat())
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Динамика по месяцам", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).then(Modifier)) {
                                        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
                                            drawCircle(incomeColor)
                                        }
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text("Доходы", style = MaterialTheme.typography.labelSmall)
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
                                        drawCircle(expenseColor)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text("Расходы", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            BarChart(entries, Modifier.fillMaxWidth().height(180.dp))
                            Spacer(Modifier.height(4.dp))
                            BarChartLabels(entries, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
