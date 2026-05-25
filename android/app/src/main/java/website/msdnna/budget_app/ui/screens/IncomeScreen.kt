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
import androidx.compose.material.icons.automirrored.filled.Assignment
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UpdateTransactionRequest
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalIncomeColor
import website.msdnna.budget_app.ui.viewmodels.IncomeViewModel

// Colours for swipe action backgrounds
private val ColourHide = Color(0xFF1976D2)
private val ColourTemplate = Color(0xFFF59E0B)
private val ColourDelete = Color(0xFFE53935)

@Composable
fun IncomeScreen(
    serverUrl: String,
    primaryColor: Color,
    valuesHidden: Boolean = false,
    filtersVisible: Boolean = false,
    onSelectionCountChange: (Int) -> Unit = {},
) {
    val vm = viewModel<IncomeViewModel>(key = "income:$serverUrl", factory = IncomeViewModel.factory(serverUrl))
    val uiState by vm.uiState.collectAsState()
    val filterCats by vm.filterCats.collectAsState()
    val filterFrom by vm.filterFrom.collectAsState()
    val filterTo by vm.filterTo.collectAsState()
    val categories by vm.categories.collectAsState()
    val ibYear by vm.ibYear.collectAsState()
    val ibMonth by vm.ibMonth.collectAsState()
    val ibByDeposit by vm.ibByDeposit.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()

    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf<Transaction?>(null) }
    var detailTx by remember { mutableStateOf<Transaction?>(null) }
    var showIbForm by remember { mutableStateOf(false) }

    val incomeColor = LocalIncomeColor.current

    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    LaunchedEffect(selectedIds.size) { onSelectionCountChange(selectedIds.size) }

    val selectedTxs = remember(selectedIds, uiState.transactions) {
        uiState.transactions.filter { it.id in selectedIds }
    }
    val allHidden = selectedTxs.isNotEmpty() && selectedTxs.all { it.hidden }

    // Stable `Transaction`-typed callbacks hoisted out of `items {}` so the
    // LazyColumn passes the same identity to every row across recompositions
    // (method-reference style for VM ops handles the id-typed callbacks).
    val onCreateTemplate: (Transaction) -> Unit = remember {
        { tx ->
            template = tx
            showAdd = true
        }
    }
    val onShowDetails: (Transaction) -> Unit = remember {
        { tx -> detailTx = tx }
    }

    Scaffold(
        // Bulk-mode FAB swap snaps without animation. Tried AnimatedContent
        // with scale+fade and pure sequenced fade — both produced a hard
        // rectangular shadow silhouette around the FAB during the
        // transition (Compose layer-rasterization artifact). The inner
        // icon Crossfade for "Скрыть/Показать" stays — it's a single-FAB
        // content swap with no shadow involvement.
        floatingActionButton = {
            if (selectionMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton(
                        onClick = { vm.bulkSetHiddenSelected(targetHidden = !allHidden) },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Crossfade(
                            targetState = allHidden,
                            animationSpec = tween(180),
                            label = "bulkHideIcon",
                        ) { hidden ->
                            Icon(
                                if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (hidden) "Показать выбранные" else "Скрыть выбранные"
                            )
                        }
                    }
                    FloatingActionButton(
                        onClick = { vm.bulkDeleteSelected() },
                        containerColor = Color(0xFFE53935),
                        contentColor = Color.White,
                    ) { Icon(Icons.Default.Delete, "Удалить выбранные") }
                }
            } else {
                FloatingActionButton(
                    onClick = {
                        template = null
                        showAdd = true
                    },
                    containerColor = primaryColor, contentColor = Color.White
                ) { Icon(Icons.Default.Add, "Добавить доход") }
            }
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { vm.reload() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // top=6 makes the first card sit 12dp below the AppBar (matches the
            // visual weight of inter-card gaps in the LazyColumn below).
            Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
                // Initial balance card
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Top row: title + month navigator on the left, edit
                        // button on the right. The per-deposit amounts go on
                        // the second row inside the same column (see below).
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Баланс на начало месяца",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IconButton(
                                    onClick = { vm.ibNavigateBack() },
                                    modifier = Modifier.size(24.dp),
                                ) { Text("‹") }
                                Text(
                                    "${monthName(ibMonth)} $ibYear",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                IconButton(
                                    onClick = { vm.ibNavigateForward() },
                                    modifier = Modifier.size(24.dp),
                                ) { Text("›") }
                            }
                            // Per-deposit summary rows. Same layout as the
                            // web Income view (1.40.0) — each scope owns its
                            // own row, missing scopes show "Не задан". Eyes-
                            // closed mode (valuesHidden) replaces digits with
                            // a coloured pill so the layout stays stable.
                            Column(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                DEPOSITS.forEach { meta ->
                                    val record = ibByDeposit[meta.value]
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = meta.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            meta.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (record == null) {
                                            Text(
                                                "Не задан",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else if (valuesHidden) {
                                            Box(
                                                Modifier.height(14.dp).width(60.dp)
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                    .background(primaryColor.copy(alpha = 0.22f)),
                                            )
                                        } else {
                                            Text(
                                                "${formatMoney(record.amount)} ₽",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = primaryColor,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { showIbForm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Crossfade(
                                targetState = ibByDeposit.isNotEmpty(),
                                animationSpec = tween(180),
                                label = "ibBtn",
                            ) { hasIb ->
                                Text(if (hasIb) "Изменить" else "Задать", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Filters card — collapsed by default; toggled by the header
                // FilterAlt button. Animates open/closed with shrink/expand so the
                // list below slides up to fill the freed space.
                androidx.compose.animation.AnimatedVisibility(
                    visible = filtersVisible,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DateRangePickerField(
                                fromIso = filterFrom,
                                toIso = filterTo,
                                primaryColor = primaryColor,
                                onChange = { f, t -> vm.setDateRange(f, t) },
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryFilterField(
                                    selected = filterCats,
                                    categories = categories,
                                    primaryColor = primaryColor,
                                    serverUrl = serverUrl,
                                    onToggle = { vm.toggleFilterCategory(it) },
                                    onClear = { vm.clearFilterCategories() },
                                    onDelete = { id -> scope.launch { vm.deleteCategory(id) } },
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "Всего: ${uiState.total}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Deposit-scope chip row — mirrors Stats/Forecast
                            // so the user reaches «Все / Карта / Наличные»
                            // through the same affordance everywhere.
                            val filterDeposit by vm.filterDeposit.collectAsState()
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DepositScopeChip(
                                    selected = filterDeposit == null,
                                    label = "Все счета",
                                    icon = null,
                                    primaryColor = primaryColor,
                                    onClick = { vm.setFilterDeposit(null) },
                                )
                                DEPOSITS.forEach { meta ->
                                    DepositScopeChip(
                                        selected = filterDeposit == meta.value,
                                        label = meta.label,
                                        icon = meta.icon,
                                        primaryColor = primaryColor,
                                        onClick = { vm.setFilterDeposit(meta.value) },
                                    )
                                }
                            }
                        }
                    }
                }

                when {
                    uiState.loading -> SkeletonTransactionList()
                    uiState.error != null -> ErrorView(uiState.error!!, { vm.reload() })
                    uiState.transactions.isEmpty() -> EmptyView("Нет доходов")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(uiState.transactions, key = { it.id }) { t ->
                            SwipeableTransactionCard(
                                modifier = Modifier.animateItem(),
                                transaction = t,
                                amountColor = incomeColor,
                                amountPrefix = "+",
                                primaryColor = primaryColor,
                                valuesHidden = valuesHidden,
                                selectionMode = selectionMode,
                                selected = t.id in selectedIds,
                                categories = categories,
                                serverUrl = serverUrl,
                                onLongPress = vm::startSelection,
                                onSelectToggle = vm::toggleSelection,
                                onDelete = vm::deleteTransaction,
                                onToggleHidden = vm::toggleHidden,
                                onCreateFromTemplate = onCreateTemplate,
                                onDetails = onShowDetails,
                            )
                        }
                        if (uiState.transactions.size < uiState.total) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = { vm.loadMore() }) { Text("Загрузить ещё") }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        } // PullToRefreshBox
    }

    if (showAdd) {
        AddIncomeSheet(
            primaryColor = primaryColor,
            template = template,
            categories = categories,
            serverUrl = serverUrl,
            onAddCategory = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onDismiss = {
                showAdd = false
                template = null
            },
            onSave = { req ->
                vm.createTransaction(req)
                showAdd = false
                template = null
            }
        )
    }

    detailTx?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            amountColor = incomeColor,
            amountPrefix = "+",
            primaryColor = primaryColor,
            categories = categories,
            serverUrl = serverUrl,
            onAddCategory = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onSave = { req -> vm.updateTransaction(tx.id, req) },
            onGetUsers = { vm.getUsers() },
            onDismiss = { detailTx = null },
            onSaved = {
                detailTx = null
                vm.reload()
            }
        )
    }

    if (showIbForm) {
        AddInitialBalanceSheet(
            primaryColor = primaryColor,
            currentByDeposit = ibByDeposit.mapValues { it.value.amount },
            onDismiss = { showIbForm = false },
            onSave = { perDeposit ->
                perDeposit.forEach { (deposit, amount) ->
                    val prev = ibByDeposit[deposit]?.amount
                    // Skip untouched tabs so we don't bump updated_at on
                    // records the user didn't intend to edit.
                    if (amount != null && amount != prev) {
                        vm.saveInitialBalance(amount, deposit)
                    }
                }
                showIbForm = false
            },
        )
    }
}

// ─── Swipeable card ──────────────────────────────────────────────────────────

/**
 * Callbacks take ids / the transaction itself rather than no-arg lambdas so
 * callers can pass stable `vm::method` references — closure-style lambdas
 * created inside `items {}` allocate a fresh identity on every list emit and
 * forced needless card recompositions during scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableTransactionCard(
    transaction: Transaction,
    amountColor: Color,
    amountPrefix: String,
    modifier: Modifier = Modifier,
    primaryColor: Color = amountColor,
    valuesHidden: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    /** When true, paint the card background in a warning (yellow) tint —
     *  used by the assignee's expense list to surface open detail-requests. */
    highlightWarning: Boolean = false,
    /** Category metadata for the inline icon next to the category name.
     *  Caller passes the same list it feeds the add/edit sheet — the card
     *  picks the matching entry by `name`. Defaults to empty so the icon
     *  silently falls back to plain text when metadata isn't wired. */
    categories: List<Category> = emptyList(),
    /** Resolves `custom:<id>` icon keys to a downloadable URL. */
    serverUrl: String = "",
    onLongPress: (id: String) -> Unit = {},
    onSelectToggle: (id: String) -> Unit = {},
    onDelete: (id: String) -> Unit,
    onToggleHidden: (id: String, currentHidden: Boolean) -> Unit,
    onCreateFromTemplate: (Transaction) -> Unit,
    onDetails: (Transaction) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Reveal widths
    val leftRevealDp = 144.dp // right-swipe → Hide + Template
    val rightRevealDp = 76.dp // left-swipe  → Delete

    val leftRevealPx = with(density) { leftRevealDp.toPx() }
    val rightRevealPx = with(density) { rightRevealDp.toPx() }

    val offsetX = remember(transaction.id) { Animatable(0f) }
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

    // When entering selection mode, retract any open swipe rails.
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
        // Swipe rails are only visible when not in selection mode.
        if (!selectionMode) {
            // ── Left background: Hide/Show + Template (revealed by right swipe) ──
            Row(
                modifier = Modifier
                    .width(leftRevealDp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
            ) {
                // Hide / Show
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(ColourHide)
                        .clickable {
                            snapTo(0f)
                            onToggleHidden(transaction.id, transaction.hidden)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (transaction.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            null, tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                        Text(
                            if (transaction.hidden) "Показать" else "Скрыть",
                            color = Color.White, fontSize = 10.sp
                        )
                    }
                }
                // Template
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(ColourTemplate)
                        .clickable {
                            snapTo(0f)
                            onCreateFromTemplate(transaction)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ContentCopy, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("Шаблон", color = Color.White, fontSize = 10.sp)
                    }
                }
            }

            // ── Right background: Delete (revealed by left swipe) ──
            Box(
                modifier = Modifier
                    .width(rightRevealDp)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .background(ColourDelete)
                    .clickable {
                        if (pendingDelete) {
                            snapTo(0f)
                            onDelete(transaction.id)
                        } else {
                            pendingDelete = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    AnimatedContent(
                        targetState = pendingDelete,
                        transitionSpec = {
                            (fadeIn(tween(160)) + scaleIn(initialScale = 0.85f, animationSpec = tween(160))) togetherWith
                                (fadeOut(tween(140)) + scaleOut(targetScale = 0.85f, animationSpec = tween(140)))
                        },
                        label = "deleteConfirm",
                    ) { pending ->
                        Text(
                            if (pending) "Подтвердить?" else "Удалить",
                            color = Color.White, fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // ── Foreground: the actual card ──
        val cardModifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .let { base ->
                if (!selectionMode) base.draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val clamped = (offsetX.value + delta).coerceIn(-rightRevealPx, leftRevealPx)
                            offsetX.snapTo(clamped)
                        }
                    },
                    onDragStopped = {
                        when {
                            offsetX.value > leftRevealPx * 0.35f -> snapTo(leftRevealPx)
                            offsetX.value < -rightRevealPx * 0.35f -> snapTo(-rightRevealPx)
                            else -> snapTo(0f)
                        }
                    }
                ) else base
            }
            .combinedClickable(
                onClick = { if (selectionMode) onSelectToggle(transaction.id) else onDetails(transaction) },
                onLongClick = { if (!selectionMode) onLongPress(transaction.id) }
            )

        // `compositeOver` keeps the container opaque so swipe rails behind the
        // card don't bleed through. The selected branch stays a hard swap
        // (the SelectionOverlay already cross-fades on top); the hidden
        // branch animates because hidden flips at user-action frequency, not
        // per-scroll-frame, so the per-card Animatable cost is acceptable.
        val baseSurface = MaterialTheme.colorScheme.surface
        val hiddenBg = MaterialTheme.colorScheme.surfaceVariant
        val warningBg = Color(0xFFF0A020).copy(alpha = 0.18f).compositeOver(baseSurface)
        val targetBg = when {
            selected -> primaryColor.copy(alpha = 0.16f).compositeOver(baseSurface)
            highlightWarning -> warningBg
            transaction.hidden -> hiddenBg
            else -> baseSurface
        }
        val animatedBg by animateColorAsState(
            targetValue = targetBg,
            animationSpec = tween(durationMillis = 260),
            label = "cardBg",
        )
        // Dynamic corner shape: the edge that meets the revealed action rail
        // straightens to 0dp as the swipe progresses, so the card visually
        // "docks" against the action panel instead of leaving a rounded
        // notch. The opposite edge stays fully rounded throughout.
        // The ×6 multiplier saturates corner progress within the first ~17%
        // of swipe; below that threshold the card's still-rounded corner
        // would cut into the (already exposed) rail's color and expose a
        // hairline sliver of the rail through the corner cut. Reaching 0dp
        // before the rail is meaningfully revealed eliminates the artifact.
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
            modifier = cardModifier,
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = animatedBg)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                transaction.createdBy?.let { author ->
                    UserAvatar(
                        displayName = author.displayName,
                        avatarUrl = author.avatarUrl,
                        size = 30.dp
                    )
                }

                Column(Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatDate(transaction.date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DepositChip(
                            value = transaction.deposit,
                            iconSize = 14.dp,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CategoryLabel(
                            name = transaction.category,
                            category = categories.firstOrNull { it.name == transaction.category },
                            serverUrl = serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        if (transaction.hidden) {
                            Text(
                                "скрыто",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    val subtitle = transaction.source ?: transaction.purpose
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!transaction.description.isNullOrBlank()) {
                        Text(
                            transaction.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // AnimatedContent (not Crossfade) so we can pin both children
                // to CenterEnd: the placeholder (60dp box) and the Text have
                // different widths, and Crossfade's default TopStart alignment
                // made the placeholder visibly slide in from the left during
                // the fade. CenterEnd anchors both to the same right edge.
                AnimatedContent(
                    targetState = valuesHidden,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                    },
                    contentAlignment = Alignment.CenterEnd,
                    label = "txAmount",
                ) { hidden ->
                    if (hidden) {
                        Box(
                            modifier = Modifier
                                .height(18.dp)
                                .width(60.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(amountColor.copy(alpha = 0.22f))
                        )
                    } else {
                        Text(
                            "$amountPrefix${formatMoney(transaction.amount)} ₽",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = amountColor
                        )
                    }
                }
            }
        }

        SelectionOverlay(
            visible = selectionMode,
            selected = selected,
            primaryColor = primaryColor,
            onClick = { onSelectToggle(transaction.id) },
        )
    }
}

// ─── Transaction detail sheet (view + edit + reassign) ───────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    transaction: Transaction,
    amountColor: Color,
    amountPrefix: String,
    primaryColor: Color = amountColor,
    categories: List<Category> = emptyList(),
    /** Resolves `custom:<id>` icon keys to a downloadable URL for the
     *  inline category icon next to the category name (both view and
     *  edit modes). Empty disables custom-icon fetching. */
    serverUrl: String = "",
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onSave: suspend (UpdateTransactionRequest) -> Unit,
    onGetUsers: suspend () -> List<UserInfo>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    /** Optional: open the detail-request linked to this transaction (if any). */
    onOpenDetailRequest: ((String) -> Unit)? = null,
    /** Optional: start the "create detail-request" flow at parent level. */
    onCreateDetailRequest: (() -> Unit)? = null,
    /** When the transaction is a child of a detail-request, the id of that
     *  request — surfaced as a "back-link" row in view mode. */
    linkedDetailRequestId: String? = null,
    /** When the transaction is linked to a wishlist item, this is its
     *  display name. Tapping the row should open the wishlist detail
     *  sheet via [onOpenLinkedWishlist]. */
    linkedWishlistName: String? = null,
    /** Row-label for the linked wishlist back-link — defaults to
     *  «Регулярный расход»; pass «Желаемая покупка» for one-off items. */
    linkedWishlistLabel: String = "Регулярный расход",
    onOpenLinkedWishlist: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var editAmount by remember { mutableStateOf(transaction.amount.let { if (it == 0.0) "" else it.toInt().toString() }) }
    var editDate by remember { mutableStateOf(transaction.date.take(10)) }
    var editCategory by remember { mutableStateOf(transaction.category) }
    var editCatInput by remember { mutableStateOf(transaction.category) }
    var editSource by remember { mutableStateOf(transaction.source ?: "") }
    var editPurpose by remember { mutableStateOf(transaction.purpose ?: "") }
    var editDesc by remember { mutableStateOf(transaction.description ?: "") }
    var editDeposit by remember { mutableStateOf(normalizeDeposit(transaction.deposit)) }
    var catExpanded by remember { mutableStateOf(false) }

    val catFiltered = remember(editCatInput, categories) {
        if (editCatInput.isBlank()) categories
        else categories.filter { it.name.contains(editCatInput, ignoreCase = true) }
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
                    if (targetState) {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220))) togetherWith
                            (fadeOut(tween(160)) + scaleOut(targetScale = 0.97f, animationSpec = tween(160)))
                    } else {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220))) togetherWith
                            (fadeOut(tween(160)) + scaleOut(targetScale = 0.97f, animationSpec = tween(160)))
                    }
                },
                label = "txDetailMode",
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
                                Text(
                                    text = "$amountPrefix${formatMoney(transaction.amount)} ₽",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = amountColor
                                )
                                Spacer(Modifier.height(4.dp))
                                CategoryLabel(
                                    name = transaction.category,
                                    category = categories.firstOrNull { it.name == transaction.category },
                                    serverUrl = serverUrl,
                                    style = MaterialTheme.typography.titleMedium,
                                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Detail-request action: open existing or create new.
                                // Only shown when callbacks are wired (i.e., from
                                // ExpensesScreen) and the transaction itself isn't a
                                // child of another request.
                                if (transaction.parentId.isBlank()) {
                                    if (transaction.detailRequestId.isNotBlank() && onOpenDetailRequest != null) {
                                        IconButton(onClick = { onOpenDetailRequest(transaction.detailRequestId) }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Assignment,
                                                contentDescription = if (transaction.detailRequestStatus == "open") {
                                                    "Открыть запрос на детализацию"
                                                } else {
                                                    "Закрытый запрос на детализацию"
                                                },
                                                tint = if (transaction.detailRequestStatus == "open") {
                                                    Color(0xFFF0A020)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    } else if (onCreateDetailRequest != null) {
                                        IconButton(onClick = { onCreateDetailRequest() }) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Assignment,
                                                contentDescription = "Создать запрос на детализацию",
                                                tint = primaryColor,
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { isEditing = true }) {
                                    // Pencil tinted with primary, not amountColor —
                                    // matches the wishlist (Forecast) edit affordance
                                    // and stays neutral relative to expense/income hue.
                                    Icon(Icons.Default.Edit, "Редактировать", tint = primaryColor)
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        DetailRow("Дата", formatDate(transaction.date))

                        val subtitle = transaction.source ?: transaction.purpose
                        if (!subtitle.isNullOrBlank()) {
                            val label = if (transaction.type == "income") "Источник" else "Назначение"
                            DetailRow(label, subtitle)
                        }
                        if (!transaction.description.isNullOrBlank()) {
                            DetailRow("Описание", transaction.description)
                        }
                        if (transaction.hidden) {
                            DetailRow("Статус", "Скрыто")
                        }
                        // Back-link from a child to its detail-request. Uses the same
                        // two-column layout as DetailRow so typography and column
                        // alignment match the surrounding "Дата" / "Назначение" rows;
                        // the link itself is a plain primary-tinted Text (not a
                        // TextButton — the button's internal padding broke the column
                        // baseline and it looked like a foreign chip).
                        if (transaction.parentId.isNotBlank() && linkedDetailRequestId != null && onOpenDetailRequest != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "Запрос на детализацию",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.4f),
                                )
                                Text(
                                    text = "Открыть запрос",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryColor,
                                    modifier = Modifier
                                        .weight(0.6f)
                                        .clickable { onOpenDetailRequest(linkedDetailRequestId) },
                                )
                            }
                        }
                        // Back-link to the recurring wishlist item this transaction
                        // fulfilled (set when user taps «Оплачено» on a regular
                        // expense). Mirror the detail-request row layout so column
                        // baselines align.
                        if (transaction.wishlistId.isNotBlank() && linkedWishlistName != null && onOpenLinkedWishlist != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    linkedWishlistLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.4f),
                                )
                                Text(
                                    text = linkedWishlistName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryColor,
                                    modifier = Modifier
                                        .weight(0.6f)
                                        .clickable { onOpenLinkedWishlist() },
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (transaction.createdBy != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    UserAvatar(displayName = transaction.createdBy.displayName, avatarUrl = transaction.createdBy.avatarUrl, size = 36.dp)
                                    Column {
                                        Text("Добавил", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(transaction.createdBy.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
                            }) { Text(if (transaction.createdBy != null) "Сменить" else "Назначить") }
                        }
                    } // close inner Column for view mode
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        // ── Edit mode ──────────────────────────────────────────────
                        Text("Редактировать", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = editAmount, onValueChange = { editAmount = it },
                            label = { Text("Сумма, ₽") },
                            modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                        )

                        DateField(
                            value = editDate,
                            onChange = { editDate = it },
                            label = "Дата",
                            primaryColor = primaryColor,
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
                                            val name = editCatInput.trim()
                                            catExpanded = false
                                            scope.launch {
                                                val cat = onAddCategory(name)
                                                if (cat != null) {
                                                    editCatInput = cat.name
                                                    editCategory = cat.name
                                                } else {
                                                    editCategory = name
                                                    editCatInput = name
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (transaction.type == "income") {
                            OutlinedTextField(
                                value = editSource, onValueChange = { editSource = it },
                                label = { Text("Источник") },
                                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                            )
                        } else {
                            OutlinedTextField(
                                value = editPurpose, onValueChange = { editPurpose = it },
                                label = { Text("Назначение") },
                                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
                            )
                        }

                        OutlinedTextField(
                            value = editDesc, onValueChange = { editDesc = it },
                            label = { Text("Описание (необязательно)") },
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
                                    val amt = editAmount.replace(',', '.').toDoubleOrNull() ?: return@Button
                                    saving = true
                                    scope.launch {
                                        onSave(
                                            UpdateTransactionRequest(
                                                amount = amt,
                                                date = editDate,
                                                category = editCatInput.trim().ifBlank { editCategory },
                                                source = editSource.ifBlank { null },
                                                purpose = editPurpose.ifBlank { null },
                                                description = editDesc.ifBlank { null },
                                                deposit = editDeposit,
                                            )
                                        )
                                        saving = false
                                        onSaved()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                enabled = !saving && editAmount.isNotBlank()
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
                        CircularProgressIndicator(color = amountColor)
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
                                        onSave(UpdateTransactionRequest(createdBy = user))
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
private fun DetailRow(label: String, value: String) {
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
            modifier = Modifier.weight(0.4f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f)
        )
    }
}

// ─── Add / template sheet ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddIncomeSheet(
    primaryColor: Color,
    template: Transaction? = null,
    categories: List<Category> = emptyList(),
    /** Powers the per-row category icon next to the dropdown item label —
     *  needed to resolve `custom:<id>` icon keys to a downloadable URL. */
    serverUrl: String = "",
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (CreateTransactionRequest) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var amount by remember { mutableStateOf(template?.amount?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }
    var date by remember { mutableStateOf(today) }
    var category by remember { mutableStateOf(template?.category ?: "") }
    var source by remember { mutableStateOf(template?.source ?: "") }
    var desc by remember { mutableStateOf(template?.description ?: "") }
    var deposit by remember { mutableStateOf(normalizeDeposit(template?.deposit)) }
    var catExpanded by remember { mutableStateOf(false) }
    var catInput by remember { mutableStateOf(template?.category ?: "") }

    val filtered = remember(catInput, categories) {
        if (catInput.isBlank()) categories
        else categories.filter { it.name.contains(catInput, ignoreCase = true) }
    }
    val showCreate = catInput.isNotBlank() && categories.none { it.name.equals(catInput.trim(), ignoreCase = true) }

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
                if (template != null) "Создать по шаблону" else "Добавить доход",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedTextField(
                value = amount, onValueChange = { amount = it },
                label = { Text("Сумма, ₽") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )

            DateField(
                value = date,
                onChange = { date = it },
                label = "Дата",
                primaryColor = primaryColor,
            )

            ExposedDropdownMenuBox(
                expanded = catExpanded,
                onExpandedChange = { catExpanded = it }
            ) {
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
                    filtered.forEach { cat ->
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
                    if (showCreate) {
                        DropdownMenuItem(
                            text = { Text("Добавить: «${catInput.trim()}»", color = primaryColor) },
                            onClick = {
                                val name = catInput.trim()
                                catExpanded = false
                                scope.launch {
                                    val cat = onAddCategory(name)
                                    if (cat != null) {
                                        catInput = cat.name
                                        category = cat.name
                                    } else {
                                        category = name
                                        catInput = name
                                    }
                                }
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = source, onValueChange = { source = it },
                label = { Text("Источник") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )
            OutlinedTextField(
                value = desc, onValueChange = { desc = it },
                label = { Text("Описание (необязательно)") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )

            Text("Счёт", style = MaterialTheme.typography.labelMedium)
            DepositSegmented(value = deposit, onChange = { deposit = it })

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val amtD = amount.replace(',', '.').toDoubleOrNull() ?: return@Button
                    val cat = catInput.trim().ifBlank { category }
                    onSave(
                        CreateTransactionRequest(
                            type = "income", amount = amtD, date = date,
                            category = cat,
                            source = source.ifBlank { null },
                            description = desc.ifBlank { null },
                            deposit = deposit,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = amount.isNotBlank() && catInput.isNotBlank()
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ─── Add / edit initial balance sheet ────────────────────────────────────────

/**
 * Tabbed initial-balance editor. Each deposit scope gets its own input;
 * unchanged tabs are skipped by the caller so we don't bump updated_at on
 * untouched records. Mirrors the web 1.40.0 layout (Income view tabs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInitialBalanceSheet(
    primaryColor: Color,
    currentByDeposit: Map<String, Double>,
    onDismiss: () -> Unit,
    onSave: (Map<String, Double?>) -> Unit,
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    // One mutable amount string per deposit so tab-switching preserves
    // in-flight edits. Initialised from existing records (formatted as int
    // when the cents are zero — matches the legacy single-record behaviour).
    val amounts = remember {
        mutableStateMapOf<String, String>().apply {
            DEPOSITS.forEach { meta ->
                val cur = currentByDeposit[meta.value]
                put(
                    meta.value,
                    cur?.let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() }
                        ?: "",
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .imePadding()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Баланс на начало месяца", style = MaterialTheme.typography.titleLarge)

            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = primaryColor,
            ) {
                DEPOSITS.forEachIndexed { index, meta ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(meta.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(meta.label, maxLines = 1, softWrap = false)
                            }
                        },
                    )
                }
            }

            val activeMeta = DEPOSITS[selectedTabIndex]
            val activeAmount = amounts[activeMeta.value].orEmpty()
            OutlinedTextField(
                value = activeAmount,
                onValueChange = { amounts[activeMeta.value] = it },
                label = { Text("Сумма на ${activeMeta.label.lowercase()} (₽)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor),
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val parsed = DEPOSITS.associate { meta ->
                        meta.value to amounts[meta.value]
                            .orEmpty()
                            .replace(',', '.')
                            .toDoubleOrNull()
                    }
                    onSave(parsed)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}
