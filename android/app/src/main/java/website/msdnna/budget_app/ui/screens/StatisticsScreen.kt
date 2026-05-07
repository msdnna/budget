package website.msdnna.budget_app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.theme.LocalIncomeColor
import website.msdnna.budget_app.ui.theme.generateChartColors
import website.msdnna.budget_app.ui.viewmodels.StatisticsViewModel
import website.msdnna.budget_app.ui.viewmodels.StatsPeriod

@Composable
fun StatisticsScreen(serverUrl: String, primaryColor: Color, valuesHidden: Boolean = false, pieUnitRuble: Boolean = true) {
    val vm = viewModel<StatisticsViewModel>(key = "stats:$serverUrl", factory = StatisticsViewModel.factory(serverUrl))
    val period by vm.period.collectAsState()
    val year   by vm.year.collectAsState()
    val month  by vm.month.collectAsState()
    val from   by vm.from.collectAsState()
    val to     by vm.to.collectAsState()
    val state  by vm.state.collectAsState()

    val incomeColor  = LocalIncomeColor.current
    val expenseColor = LocalExpenseColor.current

    PullToRefreshBox(
        // isRefreshing stays false so the spinner only flashes during the
        // gesture itself; once vm.reload() flips state.loading the screen swaps
        // to SkeletonStatisticsContent — same UX as the "Повторить" button.
        isRefreshing = false,
        onRefresh = { vm.reload() },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Period selector — chips are both mode toggles and picker triggers.
            // Active chip shows the picked value (e.g. "Май 2026"), other chips
            // show the type label. Tapping any chip switches mode and opens the
            // corresponding picker.
            var pickerOpen by remember { mutableStateOf<StatsPeriod?>(null) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatsPeriod.values().forEach { p ->
                        val label = when {
                            p != period -> when (p) {
                                StatsPeriod.MONTH -> "Месяц"
                                StatsPeriod.YEAR  -> "Год"
                                StatsPeriod.RANGE -> "Период"
                            }
                            p == StatsPeriod.MONTH -> "${monthName(month)} $year"
                            p == StatsPeriod.YEAR  -> year.toString()
                            else -> if (from != null && to != null)
                                "${shortIsoDate(from!!)} — ${shortIsoDate(to!!)}"
                            else "Период"
                        }
                        Box {
                            FilterChip(
                                selected = period == p,
                                onClick = {
                                    if (period != p) vm.setPeriod(p)
                                    pickerOpen = p
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryColor,
                                    selectedLabelColor     = Color.White,
                                ),
                            )
                            // Anchor the popups to the chip itself so they
                            // appear directly below the trigger.
                            TilePeriodPickerPopup(
                                open = pickerOpen == StatsPeriod.MONTH && p == StatsPeriod.MONTH,
                                type = TilePickerType.MONTH,
                                year = year,
                                month = month,
                                primaryColor = primaryColor,
                                onSelect = { y, m -> vm.selectMonth(y, m); pickerOpen = null },
                                onDismiss = { pickerOpen = null },
                                anchorOffset = androidx.compose.ui.unit.IntOffset(0, 110),
                            )
                            TilePeriodPickerPopup(
                                open = pickerOpen == StatsPeriod.YEAR && p == StatsPeriod.YEAR,
                                type = TilePickerType.YEAR,
                                year = year,
                                month = month,
                                primaryColor = primaryColor,
                                onSelect = { y, _ -> vm.selectYear(y); pickerOpen = null },
                                onDismiss = { pickerOpen = null },
                                anchorOffset = androidx.compose.ui.unit.IntOffset(0, 110),
                            )
                        }
                    }
                }
            }
            DateRangePickerDialog(
                open = pickerOpen == StatsPeriod.RANGE,
                initialFromIso = from,
                initialToIso = to,
                primaryColor = primaryColor,
                onConfirm = { f, t -> vm.selectRange(f, t); pickerOpen = null },
                onDismiss = { pickerOpen = null },
            )

            when {
                state.loading -> SkeletonStatisticsContent()
                state.error != null -> OfflineView(
                    message = "Офлайн-режим. Статистика недоступна",
                    onRetry = { vm.reload() },
                    modifier = Modifier.height(300.dp)
                )
                else -> {
                    val s = state.summary
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryCard("Доходы", s.totalIncome, "↑", incomeColor,
                            Modifier.weight(1f), hidden = valuesHidden)
                        SummaryCard("Расходы", s.totalExpense, "↓", expenseColor,
                            Modifier.weight(1f), hidden = valuesHidden)
                    }
                    SummaryCard(
                        "Баланс",
                        kotlin.math.abs(s.balance),
                        if (s.balance >= 0) "+" else "−",
                        if (s.balance >= 0) primaryColor else expenseColor,
                        Modifier.fillMaxWidth(), hidden = valuesHidden
                    )

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
                                    DonutChart(slices = expSlices, modifier = Modifier.size(130.dp), centerText = "Расходы")
                                    ChartLegend(expSlices, Modifier.weight(1f), pieUnitRuble, valuesHidden)
                                }
                            }
                        }
                    }

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
                                    DonutChart(slices = incSlices, modifier = Modifier.size(130.dp), centerText = "Доходы")
                                    ChartLegend(incSlices, Modifier.weight(1f), pieUnitRuble, valuesHidden)
                                }
                            }
                        }
                    }

                    if (state.monthly.isNotEmpty()) {
                        val sorted  = state.monthly.sortedBy { it.month }
                        val entries = sorted.map { m ->
                            BarEntry(monthName(m.month), m.income.toFloat(), m.expense.toFloat())
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Динамика по месяцам", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Canvas(Modifier.size(8.dp)) { drawCircle(incomeColor) }
                                        Spacer(Modifier.width(4.dp))
                                        Text("Доходы", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Canvas(Modifier.size(8.dp)) { drawCircle(expenseColor) }
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
}

/** "yyyy-MM-dd" → "dd.MM" for compact chip labels. */
private fun shortIsoDate(iso: String): String {
    if (iso.length < 10) return iso
    val parts = iso.take(10).split("-")
    if (parts.size != 3) return iso
    return "${parts[2]}.${parts[1]}"
}
