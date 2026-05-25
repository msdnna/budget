package website.msdnna.budget_app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.icons.parseCustomIconKey
import website.msdnna.budget_app.ui.icons.resolveCategoryColor
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.theme.LocalIncomeColor
import website.msdnna.budget_app.ui.viewmodels.StatisticsViewModel
import website.msdnna.budget_app.ui.viewmodels.StatsPeriod

@Composable
fun StatisticsScreen(
    serverUrl: String,
    primaryColor: Color,
    valuesHidden: Boolean = false,
    pieUnitRuble: Boolean = true,
    /** Controlled by the FilterAlt action-icon in MainScreen's TopAppBar.
     *  When true, the deposit-scope card is visible; otherwise it collapses
     *  away to keep the screen tidy (mirrors income/expenses filters). */
    filtersVisible: Boolean = false,
    /** Bumped by MainScreen when the user long-presses the funnel icon —
     *  triggers `vm.resetFilters()`. We can't read the VM directly from
     *  MainScreen (it's owned by this screen), so the parent signals via
     *  this counter and LaunchedEffect picks up the change. */
    resetTrigger: Int = 0,
    // Drilldown callbacks: invoked when the user taps a slice of the
    // expense / income donut. Parent (MainScreen) is responsible for
    // applying the filter on the destination VM, scrolling the pager,
    // and arming the back-handler that returns to Statistics. Args:
    // (categoryName, fromIso?, toIso?) — fromIso/toIso translate the
    // current Stats period into a date filter on the Expenses/Income
    // screens so the drilldown lands on a like-for-like time window.
    onDrilldownExpense: ((String, String?, String?) -> Unit)? = null,
    onDrilldownIncome: ((String, String?, String?) -> Unit)? = null,
) {
    val vm = viewModel<StatisticsViewModel>(key = "stats:$serverUrl", factory = StatisticsViewModel.factory(serverUrl))
    val period by vm.period.collectAsState()
    val year by vm.year.collectAsState()
    val month by vm.month.collectAsState()
    val from by vm.from.collectAsState()
    val to by vm.to.collectAsState()
    val deposit by vm.deposit.collectAsState()
    val state by vm.state.collectAsState()

    // Resolve the current stats period into a (fromIso, toIso) pair —
    // these become the date filter on the destination screen when the
    // user drills down through a donut slice. For RANGE we pass through
    // the user-selected range; for MONTH/YEAR we synthesise inclusive
    // calendar bounds. Kept inline so it tracks period/year/month state.
    val drilldownDateRange: Pair<String?, String?> = remember(period, year, month, from, to) {
        when (period) {
            StatsPeriod.MONTH -> {
                val cal = java.util.Calendar.getInstance()
                cal.set(year, month - 1, 1)
                val last = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                "%04d-%02d-01".format(year, month) to
                    "%04d-%02d-%02d".format(year, month, last)
            }
            StatsPeriod.YEAR -> "%04d-01-01".format(year) to "%04d-12-31".format(year)
            StatsPeriod.RANGE -> from to to
        }
    }

    val incomeColor = LocalIncomeColor.current
    val expenseColor = LocalExpenseColor.current
    val expenseCats by CategoryRepository.expense.collectAsState()
    val incomeCats by CategoryRepository.income.collectAsState()
    // Limit progress for the current calendar month — keyed by category
    // name (same key the chart uses). Refreshed by SyncEngine after each
    // pull; we also poke it on screen mount in case the user reopens
    // Statistics between sync cycles.
    val limitsState by website.msdnna.budget_app.data.repository.LimitsProgressRepository
        .state.collectAsState()
    val limitsByName = remember(limitsState) {
        limitsState?.categories.orEmpty().associateBy { it.name }
    }
    LaunchedEffect(serverUrl) {
        if (serverUrl.isNotBlank()) {
            website.msdnna.budget_app.data.repository.LimitsProgressRepository
                .refresh(serverUrl)
        }
    }
    LaunchedEffect(resetTrigger) {
        // Skip the initial composition (resetTrigger == 0) — only react to
        // subsequent bumps from the long-press in MainScreen.
        if (resetTrigger > 0) vm.resetFilters()
    }

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
            // Period + deposit filters — both collapse under the TopAppBar
            // funnel toggle so the screen defaults to showing the summary
            // cards directly under the bar. Inside one Card so the two rows
            // visually read as one filter block. Both rows are LazyRow's so
            // the labels (e.g. "Банковская карта") never need to truncate —
            // long chips horizontally scroll instead.
            var pickerOpen by remember { mutableStateOf<StatsPeriod?>(null) }
            FilterCard(
                visible = filtersVisible,
                hasActiveFilters = deposit != null,
                onReset = { vm.resetFilters() },
                primaryColor = primaryColor,
            ) {
                FilterSection(title = "Период") {
                    val periodRowState = androidx.compose.foundation.lazy.rememberLazyListState()
                    TrackInnerHorizontalScroll(periodRowState)
                    androidx.compose.foundation.lazy.LazyRow(
                        state = periodRowState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(StatsPeriod.values().toList()) { p ->
                            val label = when {
                                p != period -> when (p) {
                                    StatsPeriod.MONTH -> "Месяц"
                                    StatsPeriod.YEAR -> "Год"
                                    StatsPeriod.RANGE -> "Период"
                                }
                                p == StatsPeriod.MONTH -> "${monthName(month)} $year"
                                p == StatsPeriod.YEAR -> year.toString()
                                else -> if (from != null && to != null) {
                                    "${shortIsoDate(from!!)} — ${shortIsoDate(to!!)}"
                                } else {
                                    "Период"
                                }
                            }
                            Box {
                                FilterChip(
                                    selected = period == p,
                                    onClick = {
                                        if (period != p) vm.setPeriod(p)
                                        pickerOpen = p
                                    },
                                    label = { Text(label, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = primaryColor,
                                        selectedLabelColor = Color.White,
                                    ),
                                )
                                TilePeriodPickerPopup(
                                    open = pickerOpen == StatsPeriod.MONTH && p == StatsPeriod.MONTH,
                                    type = TilePickerType.MONTH,
                                    year = year,
                                    month = month,
                                    primaryColor = primaryColor,
                                    onSelect = { y, m ->
                                        vm.selectMonth(y, m)
                                        pickerOpen = null
                                    },
                                    onDismiss = { pickerOpen = null },
                                )
                                TilePeriodPickerPopup(
                                    open = pickerOpen == StatsPeriod.YEAR && p == StatsPeriod.YEAR,
                                    type = TilePickerType.YEAR,
                                    year = year,
                                    month = month,
                                    primaryColor = primaryColor,
                                    onSelect = { y, _ ->
                                        vm.selectYear(y)
                                        pickerOpen = null
                                    },
                                    onDismiss = { pickerOpen = null },
                                )
                            }
                        }
                    }
                }
                FilterSection(title = "Счёт") {
                    val depositRowState = androidx.compose.foundation.lazy.rememberLazyListState()
                    TrackInnerHorizontalScroll(depositRowState)
                    androidx.compose.foundation.lazy.LazyRow(
                        state = depositRowState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item {
                            DepositScopeChip(
                                selected = deposit == null,
                                label = "Все счета",
                                icon = null,
                                primaryColor = primaryColor,
                                onClick = { vm.selectDeposit(null) },
                            )
                        }
                        items(DEPOSITS) { meta ->
                            DepositScopeChip(
                                selected = deposit == meta.value,
                                label = meta.label,
                                icon = meta.icon,
                                primaryColor = primaryColor,
                                onClick = { vm.selectDeposit(meta.value) },
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
                onConfirm = { f, t ->
                    vm.selectRange(f, t)
                    pickerOpen = null
                },
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
                        SummaryCard(
                            "Доходы", s.totalIncome, "↑", incomeColor,
                            Modifier.weight(1f), hidden = valuesHidden
                        )
                        SummaryCard(
                            "Расходы", s.totalExpense, "↓", expenseColor,
                            Modifier.weight(1f), hidden = valuesHidden
                        )
                    }
                    SummaryCard(
                        "Баланс",
                        kotlin.math.abs(s.balance),
                        if (s.balance >= 0) "+" else "−",
                        if (s.balance >= 0) primaryColor else expenseColor,
                        Modifier.fillMaxWidth(), hidden = valuesHidden
                    )

                    if (state.expenseByCategory.isNotEmpty()) {
                        val expSlices = state.expenseByCategory.map { stat ->
                            val cat = expenseCats.firstOrNull { it.name == stat.category }
                            val limit = limitsByName[stat.category]
                            PieSlice(
                                label = stat.category,
                                value = stat.amount.toFloat(),
                                color = resolveCategoryColor(stat.category, cat?.color),
                                iconKey = cat?.icon,
                                customIconUrl = customIconUrl(serverUrl, cat?.icon),
                                iconScale = normalizeIconScale(cat?.iconScale),
                                limitTotal = limit?.limit,
                                limitSpent = limit?.spent ?: 0.0,
                                limitPercent = limit?.percent ?: 0.0,
                            )
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Расходы по категориям", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(12.dp))
                                CategoryDonut(
                                    allSlices = expSlices,
                                    centerText = "Расходы",
                                    pieUnitRuble = pieUnitRuble,
                                    valuesHidden = valuesHidden,
                                    onCategoryDrilldown = onDrilldownExpense?.let { cb ->
                                        { cat ->
                                            val (f, t) = drilldownDateRange
                                            cb(cat, f, t)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (state.incomeByCategory.isNotEmpty()) {
                        val incSlices = state.incomeByCategory.map { stat ->
                            val cat = incomeCats.firstOrNull { it.name == stat.category }
                            PieSlice(
                                label = stat.category,
                                value = stat.amount.toFloat(),
                                color = resolveCategoryColor(stat.category, cat?.color),
                                iconKey = cat?.icon,
                                customIconUrl = customIconUrl(serverUrl, cat?.icon),
                                iconScale = normalizeIconScale(cat?.iconScale),
                            )
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Доходы по источникам", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(12.dp))
                                CategoryDonut(
                                    allSlices = incSlices,
                                    centerText = "Доходы",
                                    pieUnitRuble = pieUnitRuble,
                                    valuesHidden = valuesHidden,
                                    onCategoryDrilldown = onDrilldownIncome?.let { cb ->
                                        { cat ->
                                            val (f, t) = drilldownDateRange
                                            cb(cat, f, t)
                                        }
                                    },
                                )
                            }
                        }
                    }

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

// Server stores 0 as "default scale" (clients pick 1.0). Non-positive
// values are also treated as default — guards against any stray negative
// payload from a future schema change.
private fun normalizeIconScale(raw: Double?): Float =
    if (raw != null && raw > 0.0) raw.toFloat() else 1f

// Builds the absolute URL to a custom-uploaded category icon when the
// stored `Category.icon` field follows the `custom:<id>` convention. The
// trailing slash + /api/ prefix mirrors `RetrofitClient.buildApiUrl`,
// which guarantees the server URL we already pass into the screen ends
// without a slash.
private fun customIconUrl(serverUrl: String, iconKey: String?): String? {
    val id = parseCustomIconKey(iconKey) ?: return null
    val base = serverUrl.trimEnd('/')
    return "$base/api/icons/$id"
}

/** "yyyy-MM-dd" → "dd.MM" for compact chip labels. */
private fun shortIsoDate(iso: String): String {
    if (iso.length < 10) return iso
    val parts = iso.take(10).split("-")
    if (parts.size != 3) return iso
    return "${parts[2]}.${parts[1]}"
}
