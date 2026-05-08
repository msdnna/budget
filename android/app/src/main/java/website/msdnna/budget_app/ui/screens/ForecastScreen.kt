package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material3.*
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
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.CreateWishlistRequest
import website.msdnna.budget_app.data.model.RegularItem
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UpdateWishlistRequest
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.model.WishlistItem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.viewmodels.ForecastViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Edit

// "once" added; recurring items have no "Purchased" concept in UI
val FREQUENCIES = listOf(
    "once"      to "Разовая",
    "monthly"   to "Ежемесячно",
    "quarterly" to "Ежеквартально",
    "yearly"    to "Ежегодно",
)

private fun isRecurring(frequency: String) =
    frequency == "monthly" || frequency == "quarterly" || frequency == "yearly"

private fun frequencyLabel(freq: String): String =
    FREQUENCIES.find { it.first == freq }?.second ?: freq

private fun monthlyContribution(item: WishlistItem): Double = when (item.frequency) {
    "quarterly" -> item.estimatedCost / 3
    "yearly"    -> item.estimatedCost / 12
    else        -> item.estimatedCost   // once / monthly / empty
}

private val ColourPurchased = Color(0xFF388E3C)   // right swipe: mark purchased
private val ColourWlDelete  = Color(0xFFE53935)   // left  swipe: delete

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun ForecastScreen(
    serverUrl: String,
    primaryColor: Color,
    onSelectionCountChange: (Int) -> Unit = {},
) {
    val vm = viewModel<ForecastViewModel>(key = "forecast:$serverUrl", factory = ForecastViewModel.factory(serverUrl))
    val uiState    by vm.uiState.collectAsState()
    val categories by vm.categories.collectAsState()
    val expenseCategories by vm.expenseCategories.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()

    // Wishlist (one-off planned purchases) and regular expenses live in the
    // same `wishlist` collection on the backend, distinguished only by
    // `frequency`. The forecast screen renders them in two separate
    // sections so the user can tell wishes from obligations at a glance.
    val wishlistOneOff = remember(uiState.wishlist) {
        uiState.wishlist.filter { !isRecurring(it.frequency) }
    }

    val expenseColor = LocalExpenseColor.current
    var showAdd      by remember { mutableStateOf(false) }
    var detailItem   by remember { mutableStateOf<WishlistItem?>(null) }
    // When non-null, opens AddExpenseSheet prefilled from a recurring item.
    var payRegular   by remember { mutableStateOf<RegularItem?>(null) }

    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    LaunchedEffect(selectedIds.size) { onSelectionCountChange(selectedIds.size) }

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
            if (selectionMode) {
                val canPurchase = purchasableSelected.isNotEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canPurchase) {
                        FloatingActionButton(
                            onClick = {
                                vm.bulkSetPurchased(
                                    ids = purchasableSelected.map { it.id },
                                    targetPurchased = !allPurchased
                                )
                            },
                            containerColor = ColourPurchased,
                            contentColor   = Color.White,
                        ) {
                            Crossfade(
                                targetState = allPurchased,
                                animationSpec = tween(180),
                                label = "bulkPurchaseIcon",
                            ) { purchased ->
                                Icon(
                                    if (purchased) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle,
                                    contentDescription = if (purchased) "Снять отметку «куплено»" else "Отметить как купленное"
                                )
                            }
                        }
                    }
                    FloatingActionButton(
                        onClick = { vm.bulkDeleteSelected() },
                        containerColor = Color(0xFFE53935),
                        contentColor   = Color.White,
                    ) { Icon(Icons.Default.Delete, "Удалить выбранные") }
                }
            } else {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = primaryColor, contentColor = Color.White
                ) { Icon(Icons.Default.Add, "Добавить в список") }
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
                                    SummaryCard("Прогноз / мес",  fc.totalMonthly,    "", expenseColor, Modifier.weight(1f))
                                    SummaryCard("Ср. за 3 мес",   fc.historicalAvg,   "", primaryColor, Modifier.weight(1f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SummaryCard("Регулярные / мес", fc.regularContrib, "", expenseColor, Modifier.weight(1f))
                                    SummaryCard("Желания / мес",    wishlistOnlyContrib, "", primaryColor, Modifier.weight(1f))
                                }

                                if (fc.breakdown.isNotEmpty()) {
                                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text("Прогноз по категориям", style = MaterialTheme.typography.titleMedium)
                                            Spacer(Modifier.height(8.dp))
                                            fc.breakdown.sortedByDescending { it.amount }.forEach { stat ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(stat.category, style = MaterialTheme.typography.bodyMedium)
                                                    Text("${formatMoney(stat.amount)} ₽",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium)
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
                // Each item is its own LazyColumn item so the cards line up
                // with the same visual rhythm as wishlist rows below.
                val regular = uiState.forecast?.regularItems.orEmpty()
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
                            onMarkPaid   = { payRegular = regItem },
                            onCancelPaid = { vm.unlinkRegularPeriod(regItem.id) },
                            onDelete     = { vm.deleteWishlistItem(regItem.id) },
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
                            onLongPress       = vm::startSelection,
                            onSelectToggle    = vm::toggleSelection,
                            onTogglePurchased = vm::togglePurchased,
                            onDelete          = vm::deleteWishlistItem,
                            onDetails         = onShowDetails,
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
            onAddCategory    = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onDismiss = { showAdd = false },
            onSave = { req -> vm.createWishlistItem(req); showAdd = false }
        )
    }

    detailItem?.let { item ->
        WishlistInteractiveSheet(
            item = item,
            primaryColor = primaryColor,
            categories = categories,
            onAddCategory    = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onSave     = { req -> vm.updateWishlistItem(item.id, req) },
            onGetUsers = { vm.getUsers() },
            onDismiss  = { detailItem = null },
            onSaved    = { detailItem = null; vm.reload() }
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
                amount      = item.estimatedCost.takeIf { it > 0.0 } ?: item.monthlyCost,
                category    = item.category,
                purpose     = item.name,
                description = item.notes,
            ),
            categories = expenseCategories,
            onAddCategory    = { name -> vm.addExpenseCategory(name) },
            onDeleteCategory = { id -> vm.deleteExpenseCategory(id) },
            onDismiss = { payRegular = null },
            onSave = { req ->
                vm.markRegularPaid(req.copy(wishlistId = item.id))
                payRegular = null
            },
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

private val ColourRegularPaid   = Color(0xFF388E3C) // right action: «Оплачено»
private val ColourRegularCancel = Color(0xFF757575) // left action:  «Отменить»

private fun frequencyUnit(freq: String): String = when (freq) {
    "quarterly" -> "₽/кв"
    "yearly"    -> "₽/год"
    else        -> "₽/мес"
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
    onMarkPaid: () -> Unit,
    onCancelPaid: () -> Unit,
    onDelete: (id: String) -> Unit,
) {
    val scope   = rememberCoroutineScope()
    val density = LocalDensity.current
    val expense = LocalExpenseColor.current

    val payRevealDp    = 88.dp
    val cancelRevealDp = 88.dp
    val deleteRevealDp = 88.dp

    val leftRevealDp   = payRevealDp
    val rightRevealDp  = if (item.paidThisPeriod) cancelRevealDp + deleteRevealDp else deleteRevealDp

    val leftRevealPx   = with(density) { leftRevealDp.toPx() }
    val rightRevealPx  = with(density) { rightRevealDp.toPx() }

    val offsetX = remember(item.id) { Animatable(0f) }
    var pendingDelete by remember { mutableStateOf(false) }
    // Mirror the «Удалить» two-stage pattern for «Отменить»: first tap arms,
    // second tap commits. Both pendings reset whenever we snap back to 0.
    var pendingCancel by remember { mutableStateOf(false) }

    fun snapTo(target: Float) = scope.launch {
        offsetX.animateTo(target, spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ))
        if (target == 0f) {
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
        // ── Left background: «Оплачено» (revealed by right-swipe). ──
        Box(
            modifier = Modifier
                .width(leftRevealDp)
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .background(ColourRegularPaid)
                .clickable { snapTo(0f); onMarkPaid() },
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

        // ── Foreground card. ──
        val baseSurface = MaterialTheme.colorScheme.surface
        val targetBg = if (item.paidThisPeriod)
            MaterialTheme.colorScheme.surfaceVariant
        else baseSurface
        val animatedBg by animateColorAsState(
            targetValue = targetBg,
            animationSpec = tween(durationMillis = 220),
            label = "regBg",
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
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
                            offsetX.value >  leftRevealPx  * 0.35f -> snapTo(leftRevealPx)
                            else -> snapTo(0f)
                        }
                    }
                ),
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
                        Text(
                            item.category.ifBlank { frequencyLabel(item.frequency) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.category.isNotBlank()) {
                            Text("·", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onTogglePurchased: (id: String, currentPurchased: Boolean) -> Unit,
    onDelete: (id: String) -> Unit,
    onDetails: (WishlistItem) -> Unit = {}
) {
    val scope      = rememberCoroutineScope()
    val density    = LocalDensity.current
    val recurring  = isRecurring(item.frequency)

    // One-time items: right swipe → purchased; left swipe → delete
    // Recurring items: left swipe → delete only
    val leftRevealDp  = if (!recurring) 80.dp else 0.dp
    val rightRevealDp = 72.dp

    val leftRevealPx  = with(density) { leftRevealDp.toPx() }
    val rightRevealPx = with(density) { rightRevealDp.toPx() }

    val offsetX = remember(item.id) { Animatable(0f) }
    var pendingDelete by remember { mutableStateOf(false) }

    fun snapTo(target: Float) = scope.launch {
        offsetX.animateTo(target, spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ))
        if (target == 0f) pendingDelete = false
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode && offsetX.value != 0f) {
            offsetX.animateTo(0f, spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness    = Spring.StiffnessMedium
            ))
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
                        .clickable { snapTo(0f); onTogglePurchased(item.id, item.purchased) },
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

            // ── Right background: Delete ──
            Box(
                modifier = Modifier
                    .width(rightRevealDp)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
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
                            !recurring && offsetX.value > leftRevealPx  * 0.35f -> snapTo(leftRevealPx)
                            offsetX.value < -rightRevealPx * 0.35f -> snapTo(-rightRevealPx)
                            else -> snapTo(0f)
                        }
                    }
                ) else base
            }
            .combinedClickable(
                onClick     = { if (selectionMode) onSelectToggle(item.id) else onDetails(item) },
                onLongClick = { if (!selectionMode) onLongPress(item.id) }
            )

        // compositeOver keeps the bg opaque so swipe rails don't bleed through.
        // animateColorAsState is fine here for the purchased branch — that
        // flag flips at user-action frequency, not per-scroll-frame; the
        // selected branch stays a hard swap (SelectionOverlay cross-fades
        // on top of it anyway).
        val baseSurface = MaterialTheme.colorScheme.surface
        val targetBg = when {
            selected       -> primaryColor.copy(alpha = 0.16f).compositeOver(baseSurface)
            item.purchased -> MaterialTheme.colorScheme.surfaceVariant
            else           -> baseSurface
        }
        val animatedBg by animateColorAsState(
            targetValue = targetBg,
            animationSpec = tween(durationMillis = 260),
            label = "wlCardBg",
        )
        Card(
            modifier = cardModifier,
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
                        Text(
                            item.category,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Text(
                            frequencyLabel(item.frequency),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.purchased) {
                            Text("· куплено",
                                style = MaterialTheme.typography.labelSmall,
                                color = ColourPurchased.copy(alpha = 0.8f))
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
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onSave: suspend (UpdateWishlistRequest) -> Unit,
    onGetUsers: suspend () -> List<UserInfo>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var saving    by remember { mutableStateOf(false) }

    var editName      by remember { mutableStateOf(item.name) }
    var editCost      by remember { mutableStateOf(item.estimatedCost.let { if (it == 0.0) "" else it.toInt().toString() }) }
    var editCategory  by remember { mutableStateOf(item.category) }
    var editCatInput  by remember { mutableStateOf(item.category) }
    var editFrequency by remember { mutableStateOf(item.frequency) }
    var editNotes     by remember { mutableStateOf(item.notes ?: "") }
    var editPurchased by remember { mutableStateOf(item.purchased) }
    var catExpanded   by remember { mutableStateOf(false) }
    var freqExpanded  by remember { mutableStateOf(false) }

    val catFiltered = remember(editCatInput, categories) {
        if (editCatInput.isBlank()) categories else categories.filter { it.name.contains(editCatInput, ignoreCase = true) }
    }
    val catShowCreate = editCatInput.isNotBlank() && categories.none { it.name.equals(editCatInput.trim(), ignoreCase = true) }

    var showUserPicker  by remember { mutableStateOf(false) }
    var users           by remember { mutableStateOf<List<website.msdnna.budget_app.data.model.UserInfo>>(emptyList()) }
    var loadingUsers    by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
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

                WishlistDetailRow("Категория", item.category)
                WishlistDetailRow("Периодичность", frequencyLabel(item.frequency))
                if ((item.frequency == "quarterly" || item.frequency == "yearly") && !item.purchased) {
                    WishlistDetailRow("Ежемес. вклад", "≈ ${formatMoney(monthlyContribution(item))} ₽/мес")
                }
                WishlistDetailRow("Статус", if (item.purchased) "Куплено ✓" else "Не куплено")
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                )

                ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                    OutlinedTextField(
                        value = editCatInput,
                        onValueChange = { editCatInput = it; catExpanded = true },
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
                                text = { Text(cat.name) },
                                onClick = { editCatInput = cat.name; editCategory = cat.name; catExpanded = false },
                                trailingIcon = if (!cat.isDefault) {{
                                    IconButton(
                                        onClick = { scope.launch { onDeleteCategory(cat.id) }; if (editCategory == cat.name) { editCategory = ""; editCatInput = "" }; catExpanded = false },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                                    }
                                }} else null
                            )
                        }
                        if (catShowCreate) {
                            DropdownMenuItem(
                                text = { Text("Добавить: «${editCatInput.trim()}»", color = primaryColor) },
                                onClick = {
                                    val n = editCatInput.trim(); catExpanded = false
                                    scope.launch {
                                        val cat = onAddCategory(n)
                                        if (cat != null) { editCatInput = cat.name; editCategory = cat.name }
                                        else { editCategory = n; editCatInput = n }
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
                            DropdownMenuItem(text = { Text(label) }, onClick = { editFrequency = key; freqExpanded = false })
                        }
                    }
                }

                if (editFrequency == "once") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Куплено", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = editPurchased,
                            onCheckedChange = { editPurchased = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.4f))
                        )
                    }
                }

                OutlinedTextField(
                    value = editNotes, onValueChange = { editNotes = it },
                    label = { Text("Заметки (необязательно)") },
                    modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                )

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
                                onSave(UpdateWishlistRequest(
                                    name = editName,
                                    estimatedCost = costD,
                                    category = editCatInput.trim().ifBlank { editCategory },
                                    frequency = editFrequency,
                                    notes = editNotes.ifBlank { null },
                                    purchased = editPurchased
                                ))
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
private fun WishlistDetailRow(label: String, value: String) {
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
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.55f)
        )
    }
}

// ─── Add wishlist sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWishlistSheet(
    primaryColor: Color,
    categories: List<Category> = emptyList(),
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (CreateWishlistRequest) -> Unit
) {
    val scope    = rememberCoroutineScope()
    // `kind` is a UI-only switch driving which form branch shows; on save
    // we map it to the `frequency` field the backend understands.
    var kind     by remember { mutableStateOf("wishlist") } // "wishlist" | "regular"
    var name     by remember { mutableStateOf("") }
    var cost     by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    // Default recurring frequency; only consulted when kind == "regular".
    var frequency by remember { mutableStateOf("monthly") }
    var catExpanded  by remember { mutableStateOf(false) }
    var catInput     by remember { mutableStateOf("") }
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
            Text(
                if (kind == "regular") "Добавить регулярный расход" else "Добавить в список желаний",
                style = MaterialTheme.typography.titleLarge
            )

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
                    onValueChange = { catInput = it; catExpanded = true },
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
                            text = { Text(cat.name) },
                            onClick = { catInput = cat.name; category = cat.name; catExpanded = false },
                            trailingIcon = if (!cat.isDefault) {{
                                IconButton(
                                    onClick = { scope.launch { onDeleteCategory(cat.id) }; if (category == cat.name) { category = ""; catInput = "" }; catExpanded = false },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                                }
                            }} else null
                        )
                    }
                    if (catShowCreate) {
                        DropdownMenuItem(
                            text = { Text("Добавить: «${catInput.trim()}»", color = primaryColor) },
                            onClick = {
                                val n = catInput.trim(); catExpanded = false
                                scope.launch {
                                    val cat = onAddCategory(n)
                                    if (cat != null) { catInput = cat.name; category = cat.name }
                                    else { category = n; catInput = n }
                                }
                            }
                        )
                    }
                }
            }

            if (kind == "regular") {
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
                            DropdownMenuItem(text = { Text(label) }, onClick = { frequency = key; freqExpanded = false })
                        }
                    }
                }

                val hint = when (frequency) {
                    "monthly"   -> "Полная стоимость учтётся в прогнозе на следующий месяц."
                    "quarterly" -> "Стоимость учтётся в прогнозе на месяц перед следующей оплатой (раз в квартал)."
                    "yearly"    -> "Стоимость учтётся в прогнозе на месяц перед следующей оплатой (раз в год)."
                    else        -> ""
                }
                if (hint.isNotEmpty()) {
                    Text(hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val costD = cost.replace(',', '.').toDoubleOrNull() ?: return@Button
                    if (name.isBlank()) return@Button
                    val freq = if (kind == "regular") frequency else "once"
                    onSave(CreateWishlistRequest(
                        name = name, estimatedCost = costD,
                        category = catInput.trim().ifBlank { category },
                        frequency = freq
                    ))
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = name.isNotBlank() && cost.isNotBlank()
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}
