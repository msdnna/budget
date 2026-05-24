package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.CreateWishlistRequest
import website.msdnna.budget_app.data.model.RegularItem
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UpdateWishlistRequest
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.model.WishlistItem
import website.msdnna.budget_app.data.repository.TransactionRepository
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.viewmodels.ForecastViewModel

// "once" added; recurring items have no "Purchased" concept in UI
val FREQUENCIES = listOf(
    "once" to "Разовая",
    "monthly" to "Ежемесячно",
    "quarterly" to "Ежеквартально",
    "yearly" to "Ежегодно",
)

private fun isRecurring(frequency: String) =
    frequency == "monthly" || frequency == "quarterly" || frequency == "yearly"

private fun frequencyLabel(freq: String): String =
    FREQUENCIES.find { it.first == freq }?.second ?: freq

private fun monthlyContribution(item: WishlistItem): Double = when (item.frequency) {
    "quarterly" -> item.estimatedCost / 3
    "yearly" -> item.estimatedCost / 12
    else -> item.estimatedCost // once / monthly / empty
}

/** Returns the start (inclusive) and exclusive end of the calendar period
 *  enclosing [now] for the given [frequency]. Mirrors the backend's bucket
 *  logic so paid_this_period stays consistent online and offline. */
private fun periodBounds(frequency: String, now: LocalDate): Pair<LocalDate, LocalDate> = when (frequency) {
    "monthly" -> {
        val start = now.withDayOfMonth(1)
        start to start.plusMonths(1)
    }
    "quarterly" -> {
        val q = (now.monthValue - 1) / 3
        val start = LocalDate.of(now.year, q * 3 + 1, 1)
        start to start.plusMonths(3)
    }
    "yearly" -> {
        val start = LocalDate.of(now.year, 1, 1)
        start to start.plusYears(1)
    }
    else -> now to now
}

/** Best-effort YYYY-MM-DD parser for transaction.date. Tolerates trailing
 *  time/zone parts (RFC3339) by taking the first ten chars. */
private fun parseTxDate(s: String): LocalDate? = runCatching {
    LocalDate.parse(s.take(10))
}.getOrNull()

/** Local synthesis of `forecast.regular_items` when the server endpoint is
 *  unreachable. Reads the Room-cached wishlist + transactions and computes
 *  paid_this_period / paid_amount / paid_count / next_due_date the same
 *  way the backend does, so the offline view feels consistent.
 *
 *  Excluded fields: monthly cost (we store full per-period cost),
 *  forecast totals (we never compute the historical_avg side offline). */
private fun synthesizeRegularItems(
    wishlist: List<WishlistItem>,
    transactions: List<Transaction>,
    now: LocalDate = LocalDate.now(),
): List<RegularItem> = wishlist
    .filter { isRecurring(it.frequency) }
    .map { wl ->
        val linked = transactions.filter { it.wishlistId == wl.id && it.deletedAt == null }
        val (start, end) = periodBounds(wl.frequency, now)
        val inPeriod = linked.filter { tx ->
            val d = parseTxDate(tx.date) ?: return@filter false
            !d.isBefore(start) && d.isBefore(end)
        }
        val latest = linked.mapNotNull { parseTxDate(it.date) }.maxOrNull()
        val nextDue = latest?.let {
            when (wl.frequency) {
                "monthly" -> it.plusMonths(1)
                "quarterly" -> it.plusMonths(3)
                "yearly" -> it.plusYears(1)
                else -> it
            }
        }
        RegularItem(
            id = wl.id,
            name = wl.name,
            estimatedCost = wl.estimatedCost,
            monthlyCost = wl.estimatedCost, // full per-period cost
            frequency = wl.frequency,
            category = wl.category,
            notes = wl.notes ?: "",
            paidThisPeriod = inPeriod.isNotEmpty(),
            paidAmount = inPeriod.sumOf { it.amount },
            paidCount = inPeriod.size,
            nextDueDate = nextDue?.toString() ?: "",
        )
    }

private val ColourPurchased = Color(0xFF388E3C) // right swipe: mark purchased
private val ColourWlDelete = Color(0xFFE53935) // left  swipe: delete

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun ForecastScreen(
    serverUrl: String,
    primaryColor: Color,
    onSelectionCountChange: (Int) -> Unit = {},
    /** Open the «привязать существующий расход» overlay for the given
     *  wishlist/regular item (id + display name). null-call disables the
     *  swipe action in the parent. */
    onLinkExisting: (id: String, name: String) -> Unit = { _, _ -> },
) {
    val vm = viewModel<ForecastViewModel>(key = "forecast:$serverUrl", factory = ForecastViewModel.factory(serverUrl))
    val uiState by vm.uiState.collectAsState()
    val categories by vm.categories.collectAsState()
    val expenseCategories by vm.expenseCategories.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val selectedRegularIds by vm.selectedRegularIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()
    val regularSelectionMode = selectedRegularIds.isNotEmpty()
    val anySelectionMode = selectionMode || regularSelectionMode

    // Wishlist (one-off planned purchases) and regular expenses live in the
    // same `wishlist` collection on the backend, distinguished only by
    // `frequency`. The forecast screen renders them in two separate
    // sections so the user can tell wishes from obligations at a glance.
    val wishlistOneOff = remember(uiState.wishlist) {
        uiState.wishlist.filter { !isRecurring(it.frequency) }
    }

    // Local transactions feed the offline synth of regular_items so
    // paid_this_period / next_due_date stay populated when the forecast
    // endpoint is unreachable.
    val localTransactions by TransactionRepository.observeAll().collectAsState(initial = emptyList())

    val expenseColor = LocalExpenseColor.current
    var showAdd by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<WishlistItem?>(null) }
    // When the detail sheet opens from a regular card we also remember the
    // server-computed forecast context (paid_this_period / next_due_date)
    // so the sheet shows «Оплачено / Не оплачено» вместо «Не куплено».
    // null when opened from a one-off wishlist row.
    var detailRegularCtx by remember { mutableStateOf<RegularItem?>(null) }
    // When non-null, opens AddExpenseSheet prefilled from a recurring item.
    var payRegular by remember { mutableStateOf<RegularItem?>(null) }
    // Same idea for «Куплено» on a one-off wishlist row.
    var payWishlist by remember { mutableStateOf<WishlistItem?>(null) }

    BackHandler(enabled = anySelectionMode) {
        if (selectionMode) vm.clearSelection()
        if (regularSelectionMode) vm.clearRegularSelection()
    }

    LaunchedEffect(selectedIds.size, selectedRegularIds.size) {
        onSelectionCountChange(selectedIds.size + selectedRegularIds.size)
    }

    // Currently-selected regular items snapshot — drives bulk-FAB state
    // (visible cancel-action only when ≥1 selected item is paid_this_period).
    val selectedRegularItems = remember(selectedRegularIds, uiState.forecast?.regularItems) {
        uiState.forecast?.regularItems.orEmpty().filter { it.id in selectedRegularIds }
    }
    val selectedPaidIds = selectedRegularItems.filter { it.paidThisPeriod }.map { it.id }

    // Determine label for the "Куплено" bulk button:
    //   • Of the selectable (non-recurring) selected items, if all are already
    //     purchased → button toggles to "Не куплено"; otherwise → "Куплено".
    val selectedItems = remember(selectedIds, uiState.wishlist) {
        uiState.wishlist.filter { it.id in selectedIds }
    }
    val purchasableSelected = remember(selectedItems) {
        selectedItems.filter { !isRecurring(it.frequency) }
    }
    val allPurchased = purchasableSelected.isNotEmpty() && purchasableSelected.all { it.purchased }

    // Stable per-row callback hoisted out of `items {}` so identity doesn't
    // flip on each list emit (id-typed callbacks use VM method references).
    val onShowDetails: (WishlistItem) -> Unit = remember {
        { wl -> detailItem = wl }
    }

    Scaffold(
        // Bulk-mode FAB swap snaps — see IncomeScreen comment for why no
        // outer animation. Inner icon Crossfade stays.
        floatingActionButton = {
            when {
                selectionMode -> {
                    // Bulk-purchase убран в android 1.28.0 — теперь покупка
                    // фиксируется через bottom-sheet (требует prefill-данных
                    // на каждый итем). Bulk-«Не куплено» оставлен (по
                    // аналогии с регулярным «Отменить»): отвязывает все
                    // привязанные txs у выбранных и сбрасывает purchased.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (purchasableSelected.isNotEmpty() && allPurchased) {
                            FloatingActionButton(
                                onClick = {
                                    vm.bulkSetPurchased(
                                        ids = purchasableSelected.map { it.id },
                                        targetPurchased = false,
                                    )
                                },
                                containerColor = Color(0xFF757575),
                                contentColor = Color.White,
                            ) { Icon(Icons.Default.RadioButtonUnchecked, "Снять отметку «куплено»") }
                        }
                        FloatingActionButton(
                            onClick = { vm.bulkDeleteSelected() },
                            containerColor = Color(0xFFE53935),
                            contentColor = Color.White,
                        ) { Icon(Icons.Default.Delete, "Удалить выбранные") }
                    }
                }
                regularSelectionMode -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (selectedPaidIds.isNotEmpty()) {
                            FloatingActionButton(
                                onClick = { vm.bulkCancelRegular(selectedPaidIds) },
                                containerColor = ColourRegularCancel,
                                contentColor = Color.White,
                            ) { Icon(Icons.Default.Close, "Отменить выбранные") }
                        }
                        FloatingActionButton(
                            onClick = { vm.bulkDeleteSelectedRegular() },
                            containerColor = Color(0xFFE53935),
                            contentColor = Color.White,
                        ) { Icon(Icons.Default.Delete, "Удалить выбранные") }
                    }
                }
                else -> {
                    FloatingActionButton(
                        onClick = { showAdd = true },
                        containerColor = primaryColor, contentColor = Color.White
                    ) { Icon(Icons.Default.Add, "Добавить в список") }
                }
            }
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { vm.reload() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Wishlist is Room-backed and works fully offline (mirrors the
            // income/expenses pattern). The forecast aggregation, on the other
            // hand, is a server-side computation — when it's loading or
            // unreachable we hide just that block while keeping the wishlist
            // operational.
            // Aggregation block + title compose in one `item { Column }` so the
            // wishlist below can be flat `items()` and benefit from LazyColumn
            // recycling — wrapping the wishlist in a single `item { Column }`
            // would compose every row eagerly (broken on 1000+ records).
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Top block: summary cards + categories breakdown. Kept as a
                // single `item { Column }` because it composes once and stays
                // small; the heavy lists below benefit from LazyColumn
                // recycling as separate items.
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val forecastDeposit by vm.filterDeposit.collectAsState()
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Счёт:", style = MaterialTheme.typography.labelMedium)
                            FilterChip(
                                selected = forecastDeposit == null,
                                onClick = { vm.setFilterDeposit(null) },
                                label = { Text("Все") },
                            )
                            DEPOSITS.forEach { meta ->
                                FilterChip(
                                    selected = forecastDeposit == meta.value,
                                    onClick = { vm.setFilterDeposit(meta.value) },
                                    label = { Text(meta.label) },
                                    leadingIcon = {
                                        Icon(meta.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                )
                            }
                        }
                        when {
                            uiState.loading -> ForecastSummarySkeleton()
                            uiState.error != null -> OfflineView(
                                message = "Офлайн-режим. Прогноз недоступен",
                                onRetry = { vm.reload() }
                            )
                            else -> {
                                val fc = uiState.forecast ?: website.msdnna.budget_app.data.model.ForecastData()
                                // 2×2 summary grid: Прогноз / Ср.3мес on top,
                                // Регулярные / Желания on the second row. The
                                // wishlist-only contribution is computed
                                // client-side: total wishlist − recurring.
                                val wishlistOnlyContrib = (fc.wishlistContrib - fc.regularContrib).coerceAtLeast(0.0)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SummaryCard("Прогноз / мес", fc.totalMonthly, "", expenseColor, Modifier.weight(1f))
                                    SummaryCard("Ср. за 3 мес", fc.historicalAvg, "", primaryColor, Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SummaryCard("Регулярные / мес", fc.regularContrib, "", expenseColor, Modifier.weight(1f))
                                    SummaryCard("Желания / мес", wishlistOnlyContrib, "", primaryColor, Modifier.weight(1f))
                                }

                                if (fc.breakdown.isNotEmpty()) {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text("Прогноз по категориям", style = MaterialTheme.typography.titleMedium)
                                            Spacer(Modifier.height(8.dp))
                                            fc.breakdown.sortedByDescending { it.amount }.forEach { stat ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    CategoryLabel(
                                                        name = stat.category,
                                                        // Forecast breakdown mixes expense + wishlist categories,
                                                        // but the wishlist set is a superset that includes
                                                        // expense names — picking from `expenseCategories` first
                                                        // and falling back to the wishlist list lets us resolve
                                                        // any category the row references.
                                                        category = expenseCategories.firstOrNull { it.name == stat.category }
                                                            ?: categories.firstOrNull { it.name == stat.category },
                                                        serverUrl = serverUrl,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                    )
                                                    Text(
                                                        "${formatMoney(stat.amount)} ₽",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Регулярные расходы (heading + flat list of swipeable cards).
                // Two data paths:
                //   • online — server-computed forecast.regular_items carries
                //     paid_this_period / next_due_date / paid_amount.
                //   • offline — synthesise from the locally-cached wishlist +
                //     transactions (Room). `synthesizeRegularItems` mirrors
                //     the backend's calendar-period bucketing so paid badges
                //     and «след. оплата» stay consistent without the network.
                val regular: List<RegularItem> = uiState.forecast?.regularItems
                    ?.takeIf { it.isNotEmpty() }
                    ?: synthesizeRegularItems(uiState.wishlist, localTransactions)
                if (regular.isNotEmpty()) {
                    item {
                        Text(
                            "Регулярные расходы",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(regular, key = { "reg:${it.id}" }) { regItem ->
                        SwipeableRegularItemCard(
                            modifier = Modifier.animateItem(),
                            item = regItem,
                            primaryColor = primaryColor,
                            selectionMode = regularSelectionMode,
                            selected = regItem.id in selectedRegularIds,
                            onLongPress = vm::startRegularSelection,
                            onSelectToggle = vm::toggleRegularSelection,
                            onShowDetails = { reg ->
                                // Reuse the wishlist detail/edit sheet — under
                                // the hood regular расходы и wishlist — это
                                // одна и та же запись с разной частотой.
                                uiState.wishlist.firstOrNull { it.id == reg.id }
                                    ?.let {
                                        detailItem = it
                                        detailRegularCtx = reg
                                    }
                            },
                            onMarkPaid = { payRegular = regItem },
                            onCancelPaid = { vm.unlinkRegularPeriod(regItem.id) },
                            onLinkExisting = { onLinkExisting(regItem.id, regItem.name) },
                            onDelete = { vm.deleteWishlistItem(regItem.id) },
                            // Regular items reuse expense categories (the
                            // backend stores them in the wishlist collection
                            // but they're rendered alongside расходы).
                            categories = expenseCategories,
                            serverUrl = serverUrl,
                        )
                    }
                }

                item {
                    Text(
                        "Список желаний",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (wishlistOneOff.isEmpty()) {
                    item { EmptyView("Список желаний пуст") }
                } else {
                    items(wishlistOneOff, key = { it.id }) { item ->
                        SwipeableWishlistCard(
                            modifier = Modifier.animateItem(),
                            item = item,
                            primaryColor = primaryColor,
                            selectionMode = selectionMode,
                            selected = item.id in selectedIds,
                            onLongPress = vm::startSelection,
                            onSelectToggle = vm::toggleSelection,
                            onPurchase = { wl -> payWishlist = wl },
                            onUnpurchase = { id -> vm.unpurchaseWishlist(id) },
                            onLinkExisting = { onLinkExisting(item.id, item.name) },
                            onDelete = vm::deleteWishlistItem,
                            onDetails = onShowDetails,
                            categories = categories,
                            serverUrl = serverUrl,
                        )
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        } // PullToRefreshBox
    }

    if (showAdd) {
        AddWishlistSheet(
            primaryColor = primaryColor,
            categories = categories,
            serverUrl = serverUrl,
            onAddCategory = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onDismiss = { showAdd = false },
            onSave = { req ->
                vm.createWishlistItem(req)
                showAdd = false
            }
        )
    }

    detailItem?.let { item ->
        WishlistInteractiveSheet(
            item = item,
            primaryColor = primaryColor,
            categories = categories,
            serverUrl = serverUrl,
            onAddCategory = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onSave = { req -> vm.updateWishlistItem(item.id, req) },
            onGetUsers = { vm.getUsers() },
            onDismiss = {
                detailItem = null
                detailRegularCtx = null
            },
            onSaved = {
                detailItem = null
                detailRegularCtx = null
                vm.reload()
            },
            // Forecast-aware status row for recurring items. null for wishlist
            // (one-off) — sheet falls back to «Куплено / Не куплено».
            paidThisPeriod = detailRegularCtx?.paidThisPeriod,
            nextDueDate = detailRegularCtx?.nextDueDate.orEmpty(),
        )
    }

    payRegular?.let { item ->
        // Prefill the expense form from the recurring wishlist item. Amount
        // defaults to estimated_cost (the actual bill), not monthly_cost —
        // for quarterly/yearly items the user pays the full bill once per
        // period. Точное копирование: name → «Назначение» (purpose),
        // notes → «Описание» (description). Раньше name шёл в description
        // — пофикшено по жалобе пользователя.
        AddExpenseSheet(
            primaryColor = primaryColor,
            template = Transaction(
                amount = item.estimatedCost.takeIf { it > 0.0 } ?: item.monthlyCost,
                category = item.category,
                purpose = item.name,
                description = item.notes,
            ),
            categories = expenseCategories,
            serverUrl = serverUrl,
            onAddCategory = { name -> vm.addExpenseCategory(name) },
            onDeleteCategory = { id -> vm.deleteExpenseCategory(id) },
            onDismiss = { payRegular = null },
            onSave = { req ->
                vm.markRegularPaid(req.copy(wishlistId = item.id))
                payRegular = null
            },
            title = "Фиксация оплаты",
        )
    }

    payWishlist?.let { item ->
        // Same prefilled flow used for «Куплено» on a one-off wishlist
        // item: создаёт linked tx + ставит purchased=true (см. VM).
        AddExpenseSheet(
            primaryColor = primaryColor,
            template = Transaction(
                amount = item.estimatedCost,
                category = item.category,
                purpose = item.name,
                description = item.notes ?: "",
            ),
            categories = expenseCategories,
            serverUrl = serverUrl,
            onAddCategory = { name -> vm.addExpenseCategory(name) },
            onDeleteCategory = { id -> vm.deleteExpenseCategory(id) },
            onDismiss = { payWishlist = null },
            onSave = { req ->
                vm.purchaseWishlist(req.copy(wishlistId = item.id))
                payWishlist = null
            },
            title = "Фиксация покупки",
        )
    }
}

// ─── Skeleton for the forecast aggregation block ─────────────────────────────

@Composable
private fun ForecastSummarySkeleton() {
    SkeletonShimmer {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SkeletonBox(width = 90.dp, height = 12.dp)
                            SkeletonBox(width = 110.dp, height = 22.dp)
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SkeletonBox(width = 140.dp, height = 12.dp)
                    SkeletonBox(width = 180.dp, height = 24.dp)
                }
            }
        }
    }
}

// ─── Регулярные расходы (forecast.regular_items) ─────────────────────────────

private val ColourRegularPaid = Color(0xFF388E3C) // right action: «Оплачено»
private val ColourRegularCancel = Color(0xFF757575) // left action:  «Отменить»
private val ColourLinkExisting = Color(0xFF2080F0) // left action:  «Привязать»

private fun frequencyUnit(freq: String): String = when (freq) {
    "quarterly" -> "₽/кв"
    "yearly" -> "₽/год"
    else -> "₽/мес"
}

private fun formatDueDate(iso: String): String {
    if (iso.isBlank()) return ""
    // YYYY-MM-DD → DD.MM.YYYY
    val parts = iso.split("-")
    if (parts.size != 3) return iso
    return "${parts[2]}.${parts[1]}.${parts[0]}"
}

/**
 * Swipe layout (mirrors `SwipeableWishlistCard`):
 *   • Swipe RIGHT → reveal LEFT background = «Оплачено» (green)
 *   • Swipe LEFT  → reveal RIGHT background:
 *       - paid_this_period: «Отменить» + «Удалить» side by side
 *       - else:             «Удалить» only
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableRegularItemCard(
    item: RegularItem,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onShowDetails: (RegularItem) -> Unit = {},
    onLongPress: (id: String) -> Unit = {},
    onSelectToggle: (id: String) -> Unit = {},
    onMarkPaid: () -> Unit,
    onCancelPaid: () -> Unit,
    onLinkExisting: () -> Unit = {},
    onDelete: (id: String) -> Unit,
    categories: List<website.msdnna.budget_app.data.model.Category> = emptyList(),
    serverUrl: String = "",
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val expense = LocalExpenseColor.current

    val payRevealDp = 88.dp
    val cancelRevealDp = 88.dp
    val deleteRevealDp = 88.dp
    val linkRevealDp = 88.dp

    val leftRevealDp = payRevealDp
    val rightRevealDp =
        (if (item.paidThisPeriod) cancelRevealDp else 0.dp) + linkRevealDp + deleteRevealDp

    val leftRevealPx = with(density) { leftRevealDp.toPx() }
    val rightRevealPx = with(density) { rightRevealDp.toPx() }

    val offsetX = remember(item.id) { Animatable(0f) }
    var pendingDelete by remember { mutableStateOf(false) }
    // Mirror the «Удалить» two-stage pattern for «Отменить»: first tap arms,
    // second tap commits. Both pendings reset whenever we snap back to 0.
    var pendingCancel by remember { mutableStateOf(false) }

    fun snapTo(target: Float) = scope.launch {
        offsetX.animateTo(
            target,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        if (target == 0f) {
            pendingDelete = false
            pendingCancel = false
        }
    }

    // Selection mode forces the row back to centre — swipe actions and
    // multi-select don't mix.
    LaunchedEffect(selectionMode) {
        if (selectionMode && offsetX.value != 0f) {
            offsetX.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            )
            pendingDelete = false
            pendingCancel = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
    ) {
        if (!selectionMode) {
            // ── Left background: «Оплачено» (revealed by right-swipe). ──
            Box(
                modifier = Modifier
                    .width(leftRevealDp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(ColourRegularPaid)
                    .clickable {
                        snapTo(0f)
                        onMarkPaid()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text("Оплачено", color = Color.White, fontSize = 10.sp)
                }
            }

            // ── Right background: «Отменить» (if paid) + «Удалить». ──
            Row(
                modifier = Modifier
                    .width(rightRevealDp)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd),
            ) {
                if (item.paidThisPeriod) {
                    Box(
                        modifier = Modifier
                            .width(cancelRevealDp)
                            .fillMaxHeight()
                            .background(ColourRegularCancel)
                            .clickable {
                                if (pendingCancel) {
                                    snapTo(0f)
                                    onCancelPaid()
                                } else {
                                    pendingCancel = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            AnimatedContent(
                                targetState = pendingCancel,
                                transitionSpec = {
                                    (fadeIn(tween(160)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160))) togetherWith
                                        (fadeOut(tween(140)) + scaleOut(targetScale = 0.85f, animationSpec = tween(140)))
                                },
                                label = "regCancelConfirm",
                            ) { pending ->
                                Text(
                                    if (pending) "Подтвердить?" else "Отменить",
                                    color = Color.White, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                // «Привязать» — открывает экран выбора несвязанного расхода.
                // В отличие от destructive-действий рядом, подтверждение здесь
                // не двухступенчатое: финальный конфирм происходит на экране
                // выбора, по второй tap-у на выбранной строке.
                Box(
                    modifier = Modifier
                        .width(linkRevealDp)
                        .fillMaxHeight()
                        .background(ColourLinkExisting)
                        .clickable {
                            snapTo(0f)
                            onLinkExisting()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Link, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("Привязать", color = Color.White, fontSize = 10.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .width(deleteRevealDp)
                        .fillMaxHeight()
                        .background(ColourWlDelete)
                        .clickable {
                            if (pendingDelete) {
                                snapTo(0f)
                                onDelete(item.id)
                            } else {
                                pendingDelete = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        AnimatedContent(
                            targetState = pendingDelete,
                            transitionSpec = {
                                (fadeIn(tween(160)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160))) togetherWith
                                    (fadeOut(tween(140)) + scaleOut(targetScale = 0.85f, animationSpec = tween(140)))
                            },
                            label = "regDeleteConfirm",
                        ) { pending ->
                            Text(
                                if (pending) "Подтвердить?" else "Удалить",
                                color = Color.White, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        } // close `if (!selectionMode)` for the swipe-reveal backgrounds

        // ── Foreground card. ──
        val baseSurface = MaterialTheme.colorScheme.surface
        val targetBg = when {
            selected -> primaryColor.copy(alpha = 0.16f).compositeOver(baseSurface)
            item.paidThisPeriod -> MaterialTheme.colorScheme.surfaceVariant
            else -> baseSurface
        }
        val animatedBg by animateColorAsState(
            targetValue = targetBg,
            animationSpec = tween(durationMillis = 220),
            label = "regBg",
        )

        val cardBaseModifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
        val cardModifier = if (!selectionMode) {
            cardBaseModifier
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-rightRevealPx, leftRevealPx))
                        }
                    },
                    onDragStopped = {
                        when {
                            offsetX.value < -rightRevealPx * 0.35f -> snapTo(-rightRevealPx)
                            offsetX.value > leftRevealPx * 0.35f -> snapTo(leftRevealPx)
                            else -> snapTo(0f)
                        }
                    }
                )
        } else cardBaseModifier
        // Dynamic corner shape — see SwipeableTransactionCard for the
        // rationale; ×6 multiplier kills the rail-through-corner hairline
        // by reaching 0dp inside the first ~17% of swipe.
        val baseCorner = 12.dp
        val rightSwipeProgress = ((offsetX.value / leftRevealPx) * 6f).coerceIn(0f, 1f)
        val leftSwipeProgress = ((-offsetX.value / rightRevealPx) * 6f).coerceIn(0f, 1f)
        val leftEdgeCorner = baseCorner * (1f - rightSwipeProgress)
        val rightEdgeCorner = baseCorner * (1f - leftSwipeProgress)
        val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = leftEdgeCorner,
            bottomStart = leftEdgeCorner,
            topEnd = rightEdgeCorner,
            bottomEnd = rightEdgeCorner,
        )
        Card(
            modifier = cardModifier
                .combinedClickable(
                    onClick = {
                        if (selectionMode) onSelectToggle(item.id)
                        else onShowDetails(item)
                    },
                    onLongClick = { if (!selectionMode) onLongPress(item.id) },
                ),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = animatedBg)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (item.paidThisPeriod) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (item.paidThisPeriod) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.category.isNotBlank()) {
                            CategoryLabel(
                                name = item.category,
                                category = categories.firstOrNull { it.name == item.category },
                                serverUrl = serverUrl,
                                style = MaterialTheme.typography.bodySmall,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "·", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                frequencyLabel(item.frequency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                frequencyLabel(item.frequency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (item.paidThisPeriod) {
                            Text(
                                "· оплачено · ${formatMoney(item.paidAmount)} ₽",
                                style = MaterialTheme.typography.labelSmall,
                                color = ColourRegularPaid.copy(alpha = 0.85f),
                            )
                        }
                    }
                    if (item.paidThisPeriod && item.nextDueDate.isNotBlank()) {
                        Text(
                            "след. оплата: ${formatDueDate(item.nextDueDate)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${formatMoney(item.monthlyCost)} ${frequencyUnit(item.frequency)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (item.paidThisPeriod)
                            MaterialTheme.colorScheme.onSurfaceVariant else expense,
                        textDecoration = if (item.paidThisPeriod)
                            TextDecoration.LineThrough else TextDecoration.None,
                    )
                }
            }
        }

        SelectionOverlay(
            visible = selectionMode,
            selected = selected,
            primaryColor = primaryColor,
            onClick = { onSelectToggle(item.id) },
        )
    }
}

// ─── Swipeable wishlist card ──────────────────────────────────────────────────

/**
 * Callbacks accept ids / the item itself so callers can pass `vm::method`
 * references and avoid allocating fresh closures per row on every list emit.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableWishlistCard(
    item: WishlistItem,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: (id: String) -> Unit = {},
    onSelectToggle: (id: String) -> Unit = {},
    /** Open the prefilled expense form to record a wishlist purchase. */
    onPurchase: (WishlistItem) -> Unit = {},
    /** Unlink the transaction created via [onPurchase] and reset the
     *  purchased flag — keeps the original tx in расходах. */
    onUnpurchase: (id: String) -> Unit = {},
    /** Open the «привязать существующий расход» picker. */
    onLinkExisting: () -> Unit = {},
    onDelete: (id: String) -> Unit,
    onDetails: (WishlistItem) -> Unit = {},
    categories: List<website.msdnna.budget_app.data.model.Category> = emptyList(),
    serverUrl: String = "",
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val recurring = isRecurring(item.frequency)

    // One-time items: right swipe → purchased; left swipe → link+delete
    // Recurring items: left swipe → link+delete (link is no-op once recurring
    // handled by SwipeableRegularItemCard, but kept simple here too).
    // «Привязать» прячется когда `purchased=true` — одну запись можно связать
    // только с одной wishlist-позицией.
    val showLink = !item.purchased
    val linkRevealDp = 80.dp
    val deleteRevealDp = 72.dp
    val leftRevealDp = if (!recurring) 80.dp else 0.dp
    val rightRevealDp = (if (showLink) linkRevealDp else 0.dp) + deleteRevealDp

    val leftRevealPx = with(density) { leftRevealDp.toPx() }
    val rightRevealPx = with(density) { rightRevealDp.toPx() }

    val offsetX = remember(item.id) { Animatable(0f) }
    var pendingDelete by remember { mutableStateOf(false) }

    fun snapTo(target: Float) = scope.launch {
        offsetX.animateTo(
            target,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
        if (target == 0f) pendingDelete = false
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode && offsetX.value != 0f) {
            offsetX.animateTo(
                0f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            pendingDelete = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
    ) {
        if (!selectionMode) {
            // ── Left background: "Куплено/Не куплено" (only for once items) ──
            if (!recurring) {
                Box(
                    modifier = Modifier
                        .width(leftRevealDp)
                        .fillMaxHeight()
                        .align(Alignment.CenterStart)
                        .background(if (item.purchased) Color(0xFF757575) else ColourPurchased)
                        .clickable {
                            snapTo(0f)
                            // «Куплено» теперь открывает префилл-форму
                            // расхода (как у регулярных), создаёт linked tx
                            // + переключает purchased. «Отменить» отвязывает
                            // и сбрасывает флаг, не удаляя транзакцию.
                            if (item.purchased) onUnpurchase(item.id)
                            else onPurchase(item)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (item.purchased) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle,
                            null, tint = Color.White, modifier = Modifier.size(22.dp)
                        )
                        Text(
                            if (item.purchased) "Отменить" else "Куплено",
                            color = Color.White, fontSize = 10.sp
                        )
                    }
                }
            }

            // ── Right backgrounds: [Привязать?] + Удалить ──
            Row(
                modifier = Modifier
                    .width(rightRevealDp)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd),
            ) {
                if (showLink) {
                    Box(
                        modifier = Modifier
                            .width(linkRevealDp)
                            .fillMaxHeight()
                            .background(ColourLinkExisting)
                            .clickable {
                                snapTo(0f)
                                onLinkExisting()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Link, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Text("Привязать", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(deleteRevealDp)
                        .fillMaxHeight()
                        .background(ColourWlDelete)
                        .clickable {
                            if (pendingDelete) {
                                snapTo(0f)
                                onDelete(item.id)
                            } else {
                                pendingDelete = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        AnimatedContent(
                            targetState = pendingDelete,
                            transitionSpec = {
                                (fadeIn(tween(160)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160))) togetherWith
                                    (fadeOut(tween(140)) + scaleOut(targetScale = 0.85f, animationSpec = tween(140)))
                            },
                            label = "wlDeleteConfirm",
                        ) { pending ->
                            Text(
                                if (pending) "Подтвердить?" else "Удалить",
                                color = Color.White, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // ── Foreground card ──
        val cardModifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .let { base ->
                if (!selectionMode) base.draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val min = -rightRevealPx
                            val max = if (!recurring) leftRevealPx else 0f
                            offsetX.snapTo((offsetX.value + delta).coerceIn(min, max))
                        }
                    },
                    onDragStopped = {
                        when {
                            !recurring && offsetX.value > leftRevealPx * 0.35f -> snapTo(leftRevealPx)
                            offsetX.value < -rightRevealPx * 0.35f -> snapTo(-rightRevealPx)
                            else -> snapTo(0f)
                        }
                    }
                ) else base
            }
            .combinedClickable(
                onClick = { if (selectionMode) onSelectToggle(item.id) else onDetails(item) },
                onLongClick = { if (!selectionMode) onLongPress(item.id) }
            )

        // compositeOver keeps the bg opaque so swipe rails don't bleed through.
        // animateColorAsState is fine here for the purchased branch — that
        // flag flips at user-action frequency, not per-scroll-frame; the
        // selected branch stays a hard swap (SelectionOverlay cross-fades
        // on top of it anyway).
        val baseSurface = MaterialTheme.colorScheme.surface
        val targetBg = when {
            selected -> primaryColor.copy(alpha = 0.16f).compositeOver(baseSurface)
            item.purchased -> MaterialTheme.colorScheme.surfaceVariant
            else -> baseSurface
        }
        val animatedBg by animateColorAsState(
            targetValue = targetBg,
            animationSpec = tween(durationMillis = 260),
            label = "wlCardBg",
        )
        // Dynamic corners — same pattern as SwipeableTransactionCard;
        // ×6 multiplier flattens the corner before the rail-through-corner
        // sliver becomes visible. For recurring items the left reveal is
        // disabled (leftRevealPx == 0); guard with takeIf to avoid NaN.
        val baseCorner = 12.dp
        val rightSwipeProgress = if (leftRevealPx > 0f) {
            ((offsetX.value / leftRevealPx) * 6f).coerceIn(0f, 1f)
        } else {
            0f
        }
        val leftSwipeProgress = ((-offsetX.value / rightRevealPx) * 6f).coerceIn(0f, 1f)
        val leftEdgeCorner = baseCorner * (1f - rightSwipeProgress)
        val rightEdgeCorner = baseCorner * (1f - leftSwipeProgress)
        val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = leftEdgeCorner,
            bottomStart = leftEdgeCorner,
            topEnd = rightEdgeCorner,
            bottomEnd = rightEdgeCorner,
        )
        Card(
            modifier = cardModifier,
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = animatedBg)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item.createdBy?.let { author ->
                    UserAvatar(
                        displayName = author.displayName,
                        avatarUrl = author.avatarUrl,
                        size = 30.dp
                    )
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (item.purchased) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (item.purchased) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CategoryLabel(
                            name = item.category,
                            category = categories.firstOrNull { it.name == item.category },
                            serverUrl = serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "·", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            frequencyLabel(item.frequency),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.purchased) {
                            Text(
                                "· куплено",
                                style = MaterialTheme.typography.labelSmall,
                                color = ColourPurchased.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    // Estimated cost
                    Text(
                        "${formatMoney(item.estimatedCost)} ₽",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (item.purchased) MaterialTheme.colorScheme.onSurfaceVariant
                        else primaryColor
                    )
                    // Monthly contribution (show for recurring and non-purchased once)
                    if (!item.purchased) {
                        val monthly = monthlyContribution(item)
                        if (item.frequency != "monthly" && item.frequency != "once" && item.frequency.isNotBlank()) {
                            Text(
                                "≈ ${formatMoney(monthly)} ₽/мес",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        SelectionOverlay(
            visible = selectionMode,
            selected = selected,
            primaryColor = primaryColor,
            onClick = { onSelectToggle(item.id) },
        )
    }
}

// ─── Wishlist interactive sheet (view + edit + reassign) ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistInteractiveSheet(
    item: WishlistItem,
    primaryColor: Color,
    categories: List<Category> = emptyList(),
    /** Powers the inline category icon next to the category row and the
     *  edit-mode dropdown items — resolves `custom:<id>` keys to URLs. */
    serverUrl: String = "",
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onSave: suspend (UpdateWishlistRequest) -> Unit,
    onGetUsers: suspend () -> List<UserInfo>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    /** Opening from a regular расход — overrides the «Куплено» status row
     *  with «Оплачено / Не оплачено» semantics derived from the forecast.
     *  null when the sheet was opened from a one-off wishlist item. */
    paidThisPeriod: Boolean? = null,
    /** Next due date string (YYYY-MM-DD) for recurring items. Surfaced
     *  alongside the status row so the user knows when the next payment
     *  rolls around. Empty = don't render. */
    nextDueDate: String = "",
) {
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var editName by remember { mutableStateOf(item.name) }
    var editCost by remember { mutableStateOf(item.estimatedCost.let { if (it == 0.0) "" else it.toInt().toString() }) }
    var editCategory by remember { mutableStateOf(item.category) }
    var editCatInput by remember { mutableStateOf(item.category) }
    var editFrequency by remember { mutableStateOf(item.frequency) }
    var editNotes by remember { mutableStateOf(item.notes ?: "") }
    var editDeposit by remember { mutableStateOf(normalizeDeposit(item.deposit)) }
    var catExpanded by remember { mutableStateOf(false) }
    var freqExpanded by remember { mutableStateOf(false) }

    val catFiltered = remember(editCatInput, categories) {
        if (editCatInput.isBlank()) categories else categories.filter { it.name.contains(editCatInput, ignoreCase = true) }
    }
    val catShowCreate = editCatInput.isNotBlank() && categories.none { it.name.equals(editCatInput.trim(), ignoreCase = true) }

    var showUserPicker by remember { mutableStateOf(false) }
    var users by remember { mutableStateOf<List<website.msdnna.budget_app.data.model.UserInfo>>(emptyList()) }
    var loadingUsers by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        BackHandler(enabled = isEditing) { isEditing = false }
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            AnimatedContent(
                targetState = isEditing,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220))) togetherWith
                        (fadeOut(tween(160)) + scaleOut(targetScale = 0.97f, animationSpec = tween(160)))
                },
                label = "wlDetailMode",
            ) { editing ->
                if (!editing) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        // ── View mode ──────────────────────────────────────────────
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${formatMoney(item.estimatedCost)} ₽",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.purchased) MaterialTheme.colorScheme.onSurfaceVariant else primaryColor
                                )
                            }
                            IconButton(onClick = { isEditing = true }) {
                                Icon(Icons.Default.Edit, "Редактировать", tint = primaryColor)
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        WishlistDetailRow(
                            label = "Категория",
                            value = item.category,
                            // Inline category icon — mirrors the transaction
                            // detail sheet (расходы / доходы) so all
                            // category references look the same.
                            valueContent = {
                                CategoryLabel(
                                    name = item.category,
                                    category = categories.firstOrNull { it.name == item.category },
                                    serverUrl = serverUrl,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                        )
                        WishlistDetailRow("Периодичность", frequencyLabel(item.frequency))
                        // «Ежемес. вклад» is informational only and applies to
                        // quarterly/yearly schedules — for monthly the value is
                        // identical to the price already shown above.
                        if (item.frequency == "quarterly" || item.frequency == "yearly") {
                            WishlistDetailRow("Ежемес. вклад", "≈ ${formatMoney(monthlyContribution(item))} ₽/мес")
                        }
                        // Status row semantics depend on what the sheet was opened
                        // from: a one-off wishlist row uses purchased flag («Куплено
                        // ✓ / Не куплено»); a recurring расход uses the forecast's
                        // paid_this_period («Оплачено / Не оплачено»). When opened
                        // for a recurring item without forecast context (e.g. from
                        // a transaction back-link offline), the status row is
                        // hidden — `purchased` is meaningless for recurring rows.
                        when {
                            paidThisPeriod != null -> {
                                WishlistDetailRow("Статус", if (paidThisPeriod) "Оплачено ✓" else "Не оплачено")
                                if (nextDueDate.isNotBlank()) {
                                    WishlistDetailRow("Следующая оплата", formatDueDate(nextDueDate))
                                }
                            }
                            item.frequency == "once" || item.frequency.isBlank() -> {
                                WishlistDetailRow("Статус", if (item.purchased) "Куплено ✓" else "Не куплено")
                            }
                        }
                        if (!item.notes.isNullOrBlank()) {
                            WishlistDetailRow("Заметки", item.notes)
                        }

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (item.createdBy != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    UserAvatar(displayName = item.createdBy.displayName, avatarUrl = item.createdBy.avatarUrl, size = 36.dp)
                                    Column {
                                        Text("Добавил", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(item.createdBy.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                }
                            } else {
                                Text(
                                    "Автор не назначен",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                showUserPicker = true
                                if (users.isEmpty()) {
                                    loadingUsers = true
                                    scope.launch {
                                        users = onGetUsers()
                                        loadingUsers = false
                                    }
                                }
                            }) { Text(if (item.createdBy != null) "Сменить" else "Назначить") }
                        }
                    } // close inner Column for view mode
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        // ── Edit mode ──────────────────────────────────────────────
                        Text("Редактировать", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = editName, onValueChange = { editName = it },
                            label = { Text("Название") },
                            modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                        )

                        OutlinedTextField(
                            value = editCost, onValueChange = { editCost = it },
                            label = { Text("Стоимость, ₽") },
                            modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                            ),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                        )

                        ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                            OutlinedTextField(
                                value = editCatInput,
                                onValueChange = {
                                    editCatInput = it
                                    catExpanded = true
                                },
                                label = { Text("Категория") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                            )
                            ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                                catFiltered.forEach { cat ->
                                    DropdownMenuItem(
                                        text = {
                                            CategoryLabel(
                                                name = cat.name,
                                                category = cat,
                                                serverUrl = serverUrl,
                                            )
                                        },
                                        onClick = {
                                            editCatInput = cat.name
                                            editCategory = cat.name
                                            catExpanded = false
                                        },
                                        trailingIcon = if (!cat.isDefault) {
                                            {
                                                IconButton(
                                                    onClick = {
                                                        scope.launch { onDeleteCategory(cat.id) }
                                                        if (editCategory == cat.name) {
                                                            editCategory = ""
                                                            editCatInput = ""
                                                        }
                                                        catExpanded = false
                                                    },
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        } else null
                                    )
                                }
                                if (catShowCreate) {
                                    DropdownMenuItem(
                                        text = { Text("Добавить: «${editCatInput.trim()}»", color = primaryColor) },
                                        onClick = {
                                            val n = editCatInput.trim()
                                            catExpanded = false
                                            scope.launch {
                                                val cat = onAddCategory(n)
                                                if (cat != null) {
                                                    editCatInput = cat.name
                                                    editCategory = cat.name
                                                } else {
                                                    editCategory = n
                                                    editCatInput = n
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        ExposedDropdownMenuBox(expanded = freqExpanded, onExpandedChange = { freqExpanded = it }) {
                            OutlinedTextField(
                                value = frequencyLabel(editFrequency), onValueChange = {}, readOnly = true,
                                label = { Text("Периодичность") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(freqExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                            )
                            ExposedDropdownMenu(expanded = freqExpanded, onDismissRequest = { freqExpanded = false }) {
                                FREQUENCIES.forEach { (key, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        editFrequency = key
                                        freqExpanded = false
                                    })
                                }
                            }
                        }

                        // «Куплено» live на карточке (свайп для wishlist-once;
                        // кнопка-действие на действиях)  — в форме редактирования
                        // тогл лишний, ставится из карточки одной кнопкой.

                        OutlinedTextField(
                            value = editNotes, onValueChange = { editNotes = it },
                            label = { Text("Заметки (необязательно)") },
                            modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                        )

                        Text("Счёт", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        DepositSegmented(value = editDeposit, onChange = { editDeposit = it })

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { isEditing = false }, modifier = Modifier.weight(1f)) {
                                Text("Отмена")
                            }
                            Button(
                                onClick = {
                                    val costD = editCost.replace(',', '.').toDoubleOrNull() ?: return@Button
                                    if (editName.isBlank()) return@Button
                                    saving = true
                                    scope.launch {
                                        // `purchased` намеренно не передаём — статус
                                        // меняется через swipe-actions карточки, не
                                        // в форме редактирования.
                                        onSave(
                                            UpdateWishlistRequest(
                                                name = editName,
                                                estimatedCost = costD,
                                                category = editCatInput.trim().ifBlank { editCategory },
                                                frequency = editFrequency,
                                                notes = editNotes.ifBlank { null },
                                                deposit = editDeposit,
                                            )
                                        )
                                        saving = false
                                        onSaved()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                enabled = !saving && editName.isNotBlank() && editCost.isNotBlank()
                            ) { Text(if (saving) "…" else "Сохранить", fontWeight = FontWeight.SemiBold) }
                        }
                    } // close inner Column for edit mode
                } // AnimatedContent branch
            } // AnimatedContent
        }
    }

    if (showUserPicker) {
        AlertDialog(
            onDismissRequest = { showUserPicker = false },
            title = { Text("Изменить автора") },
            text = {
                if (loadingUsers) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = primaryColor)
                    }
                } else {
                    LazyColumn {
                        items(users) { user ->
                            ListItem(
                                headlineContent = { Text(user.displayName) },
                                leadingContent = { UserAvatar(displayName = user.displayName, avatarUrl = user.avatarUrl, size = 32.dp) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        onSave(UpdateWishlistRequest(createdBy = user))
                                        onSaved()
                                    }
                                    showUserPicker = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showUserPicker = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun WishlistDetailRow(
    label: String,
    value: String,
    /** Override for the value column — used by the «Категория» row to render
     *  a `CategoryLabel` (icon + name) instead of plain text. When null, the
     *  function falls back to a plain `Text(value)`. */
    valueContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Box(modifier = Modifier.weight(0.55f)) {
            if (valueContent != null) {
                valueContent()
            } else {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ─── Add wishlist sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWishlistSheet(
    primaryColor: Color,
    categories: List<Category> = emptyList(),
    /** Resolves `custom:<id>` category icons for the dropdown items. */
    serverUrl: String = "",
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (CreateWishlistRequest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // `kind` is a UI-only switch driving which form branch shows; on save
    // we map it to the `frequency` field the backend understands.
    var kind by remember { mutableStateOf("wishlist") } // "wishlist" | "regular"
    var name by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var deposit by remember { mutableStateOf(DEPOSIT_DEFAULT) }
    // Default recurring frequency; only consulted when kind == "regular".
    var frequency by remember { mutableStateOf("monthly") }
    var catExpanded by remember { mutableStateOf(false) }
    var catInput by remember { mutableStateOf("") }
    var freqExpanded by remember { mutableStateOf(false) }

    // Recurring branch dropdown — `once` is implicit for the wishlist branch
    // and removed from the picker.
    val recurringFrequencies = remember {
        FREQUENCIES.filter { it.first != "once" }
    }

    val catFiltered = remember(catInput, categories) {
        if (catInput.isBlank()) categories else categories.filter { it.name.contains(catInput, ignoreCase = true) }
    }
    val catShowCreate = catInput.isNotBlank() && categories.none { it.name.equals(catInput.trim(), ignoreCase = true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title cross-fades between the two flows so the chip change
            // feels like a smooth scene swap rather than a hard re-render.
            AnimatedContent(
                targetState = kind,
                transitionSpec = {
                    (fadeIn(tween(200)) togetherWith fadeOut(tween(140))).using(
                        SizeTransform(clip = false)
                    )
                },
                label = "addSheetTitle",
            ) { k ->
                Text(
                    if (k == "regular") "Добавить регулярный расход" else "Добавить в список желаний",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Type selector (FilterChip pair, mirroring the Statistics period
            // pattern). Default «Желаемая покупка»; switching to «Регулярный
            // расход» reveals the frequency picker below.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == "wishlist",
                    onClick = { kind = "wishlist" },
                    label = { Text("Желаемая покупка") },
                )
                FilterChip(
                    selected = kind == "regular",
                    onClick = {
                        kind = "regular"
                        // If user toggled to regular but frequency is still
                        // "once" from a stale state, force a sane default.
                        if (frequency == "once") frequency = "monthly"
                    },
                    label = { Text("Регулярный расход") },
                )
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )

            OutlinedTextField(
                value = cost, onValueChange = { cost = it },
                label = { Text("Стоимость, ₽") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )

            ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                OutlinedTextField(
                    value = catInput,
                    onValueChange = {
                        catInput = it
                        catExpanded = true
                    },
                    label = { Text("Категория") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                )
                ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                    catFiltered.forEach { cat ->
                        DropdownMenuItem(
                            text = {
                                CategoryLabel(
                                    name = cat.name,
                                    category = cat,
                                    serverUrl = serverUrl,
                                )
                            },
                            onClick = {
                                catInput = cat.name
                                category = cat.name
                                catExpanded = false
                            },
                            trailingIcon = if (!cat.isDefault) {
                                {
                                    IconButton(
                                        onClick = {
                                            scope.launch { onDeleteCategory(cat.id) }
                                            if (category == cat.name) {
                                                category = ""
                                                catInput = ""
                                            }
                                            catExpanded = false
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                                    }
                                }
                            } else null
                        )
                    }
                    if (catShowCreate) {
                        DropdownMenuItem(
                            text = { Text("Добавить: «${catInput.trim()}»", color = primaryColor) },
                            onClick = {
                                val n = catInput.trim()
                                catExpanded = false
                                scope.launch {
                                    val cat = onAddCategory(n)
                                    if (cat != null) {
                                        catInput = cat.name
                                        category = cat.name
                                    } else {
                                        category = n
                                        catInput = n
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Periodicity picker (only meaningful for regular расход) slides
            // in/out vertically when the user flips the kind chips, so the
            // form animates rather than jumping.
            AnimatedVisibility(
                visible = kind == "regular",
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(160)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExposedDropdownMenuBox(expanded = freqExpanded, onExpandedChange = { freqExpanded = it }) {
                        OutlinedTextField(
                            value = frequencyLabel(frequency), onValueChange = {}, readOnly = true,
                            label = { Text("Периодичность") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(freqExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                        )
                        ExposedDropdownMenu(expanded = freqExpanded, onDismissRequest = { freqExpanded = false }) {
                            recurringFrequencies.forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    frequency = key
                                    freqExpanded = false
                                })
                            }
                        }
                    }

                    val hint = when (frequency) {
                        "monthly" -> "Полная стоимость учтётся в прогнозе на следующий месяц."
                        "quarterly" -> "Стоимость учтётся в прогнозе на месяц перед следующей оплатой (раз в квартал)."
                        "yearly" -> "Стоимость учтётся в прогнозе на месяц перед следующей оплатой (раз в год)."
                        else -> ""
                    }
                    if (hint.isNotEmpty()) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Notes (заметки) — applies to both kinds. The «Оплачено» flow on
            // a recurring item copies these into the expense's «Описание».
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Заметки (необязательно)") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )

            Text("Счёт", style = MaterialTheme.typography.labelMedium)
            DepositSegmented(value = deposit, onChange = { deposit = it })

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val costD = cost.replace(',', '.').toDoubleOrNull() ?: return@Button
                    if (name.isBlank()) return@Button
                    val freq = if (kind == "regular") frequency else "once"
                    onSave(
                        CreateWishlistRequest(
                            name = name, estimatedCost = costD,
                            category = catInput.trim().ifBlank { category },
                            frequency = freq,
                            deposit = deposit,
                            notes = notes.ifBlank { null },
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = name.isNotBlank() && cost.isNotBlank()
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}
