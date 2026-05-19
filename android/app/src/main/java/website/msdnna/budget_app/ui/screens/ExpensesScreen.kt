package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UpdateWishlistRequest
import website.msdnna.budget_app.data.model.UserInfo
import website.msdnna.budget_app.data.model.WishlistItem
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.data.repository.LimitsProgressRepository
import website.msdnna.budget_app.data.repository.WishlistRepository
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.viewmodels.ExpensesViewModel

@Composable
fun ExpensesScreen(
    serverUrl: String,
    primaryColor: Color,
    valuesHidden: Boolean = false,
    filtersVisible: Boolean = false,
    currentUserId: String = "",
    isAdmin: Boolean = false,
    onSelectionCountChange: (Int) -> Unit = {},
    onOpenDetailRequest: (String) -> Unit = {},
    onOpenCategoryLimits: () -> Unit = {},
) {
    val vm = viewModel<ExpensesViewModel>(key = "expenses:$serverUrl", factory = ExpensesViewModel.factory(serverUrl))
    val uiState by vm.uiState.collectAsState()
    val filterCats by vm.filterCats.collectAsState()
    val filterFrom by vm.filterFrom.collectAsState()
    val filterTo by vm.filterTo.collectAsState()
    val includeDetailed by vm.includeDetailed.collectAsState()
    val categories by vm.categories.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showAdd by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf<Transaction?>(null) }
    var detailTx by remember { mutableStateOf<Transaction?>(null) }
    var createDrForTx by remember { mutableStateOf<Transaction?>(null) }
    var createDrError by remember { mutableStateOf<String?>(null) }
    // When the user taps the wishlist back-link in the transaction detail
    // sheet, we open the wishlist edit sheet inline so they can review the
    // recurring schedule without leaving the расходы screen.
    var wishlistInfo by remember { mutableStateOf<WishlistItem?>(null) }
    val allWishlist by WishlistRepository.observeAll().collectAsState(emptyList())
    val wishlistCategories by CategoryRepository.wishlist.collectAsState()
    // Skip the initial composition; only animate-scroll on later flips.
    var includeDetailedSeen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val drItems by website.msdnna.budget_app.data.repository.DetailRequestStore.items.collectAsState()
    val myOpenParentIds = remember(drItems, currentUserId) {
        drItems.filter { it.status == "open" && it.assignee?.userId == currentUserId }
            .map { it.parentTransactionId }.toSet()
    }
    // Sort transactions so that ones with my open detail-request float to the top.
    val orderedTransactions = remember(uiState.transactions, myOpenParentIds) {
        if (myOpenParentIds.isEmpty()) uiState.transactions
        else uiState.transactions.sortedByDescending { it.id in myOpenParentIds }
    }
    LaunchedEffect(Unit) {
        website.msdnna.budget_app.data.repository.DetailRequestStore.refresh()
    }
    LaunchedEffect(includeDetailed) {
        if (includeDetailedSeen != null && includeDetailedSeen != includeDetailed) {
            // Newly appearing rows pin to the top; surface them so the user
            // sees the effect of the toggle without manually scrolling.
            listState.animateScrollToItem(0)
        }
        includeDetailedSeen = includeDetailed
    }

    // When detail-requests load AFTER transactions, the pinned (yellow) parent
    // rows are inserted at the head of `orderedTransactions`. LazyColumn keyed
    // items anchor on the first visible item, so the new rows end up scrolled
    // off-screen above the viewport. Force-scroll to 0 the first time pinned
    // items appear so the user sees the request highlighted.
    var pinnedSeenCount by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(myOpenParentIds.size, uiState.transactions.size) {
        val pinned = myOpenParentIds.size
        if (pinned > pinnedSeenCount && uiState.transactions.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
        pinnedSeenCount = pinned
    }

    val expenseColor = LocalExpenseColor.current

    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    LaunchedEffect(selectedIds.size) { onSelectionCountChange(selectedIds.size) }

    val selectedTxs = remember(selectedIds, uiState.transactions) {
        uiState.transactions.filter { it.id in selectedIds }
    }
    val allHidden = selectedTxs.isNotEmpty() && selectedTxs.all { it.hidden }

    // Hoisted out of `items {}` and remembered so identity is stable across
    // recompositions — method references on `vm` cover the id-typed callbacks.
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
        // Bulk-mode FAB swap snaps — see IncomeScreen comment for why no
        // outer animation. Inner icon Crossfade stays.
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
                ) { Icon(Icons.Default.Add, "Добавить расход") }
            }
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = {
                vm.reload()
                // Online-only state isn't covered by SyncWorker — force a
                // refresh here so the header badge clears after another
                // device closes a request.
                scope.launch {
                    website.msdnna.budget_app.data.repository.DetailRequestStore.refresh()
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // top=6 makes the first card sit 12dp below the AppBar (matches the
            // visual weight of inter-card gaps in the LazyColumn below).
            Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
                // Monthly limit card — read-only summary of current-month
                // spending vs total tracked limits. Tap navigates to the
                // limits editor for admins; non-admins see the card as
                // pure read-only (no chevron, no ripple).
                val limitState by LimitsProgressRepository.state.collectAsState()
                LaunchedEffect(serverUrl) {
                    if (serverUrl.isNotBlank()) LimitsProgressRepository.refresh(serverUrl)
                }
                LimitsSummaryCard(
                    limit = limitState?.totalLimit ?: 0.0,
                    spent = limitState?.totalSpent ?: 0.0,
                    percent = limitState?.totalPercent ?: 0.0,
                    period = limitState?.period.orEmpty(),
                    filterFromIso = filterFrom,
                    filterToIso = filterTo,
                    valuesHidden = valuesHidden,
                    expenseColor = expenseColor,
                    primaryColor = primaryColor,
                    isAdmin = isAdmin,
                    onTap = onOpenCategoryLimits,
                )

                // Filters card — collapsed by default; toggled by the header
                // FilterAlt button. See IncomeScreen for the same pattern.
                androidx.compose.animation.AnimatedVisibility(
                    modifier = Modifier
                        // Keep the filter card above the LazyColumn during item
                        // transitions — without zIndex, an item being removed by
                        // a filter toggle (e.g. closed-request parent disappearing)
                        // briefly bleeds through the card area.
                        .zIndex(1f)
                        .background(MaterialTheme.colorScheme.background),
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                // Whole row is the toggle — flat, no ripple (the
                                // small grey press tint behind the label looked
                                // like a bug, not a feature).
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { vm.setIncludeDetailed(!includeDetailed) },
                            ) {
                                Checkbox(
                                    checked = includeDetailed,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = primaryColor),
                                )
                                Text(
                                    "Показать закрытые запросы",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                when {
                    uiState.loading -> SkeletonTransactionList()
                    uiState.error != null -> ErrorView(uiState.error!!, { vm.reload() })
                    uiState.transactions.isEmpty() -> EmptyView("Нет расходов")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(orderedTransactions, key = { it.id }) { t ->
                            SwipeableTransactionCard(
                                modifier = Modifier.animateItem(),
                                transaction = t,
                                amountColor = expenseColor,
                                amountPrefix = "−",
                                primaryColor = primaryColor,
                                valuesHidden = valuesHidden,
                                selectionMode = selectionMode,
                                selected = t.id in selectedIds,
                                // Yellow tint when this expense has an open
                                // detail-request assigned to the current user.
                                highlightWarning = t.id in myOpenParentIds,
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
        AddExpenseSheet(
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
        // Resolve the detail-request id for child transactions so the sheet
        // can render a back-link row.
        val linkedDrId = remember(tx.id, tx.parentId, drItems) {
            if (tx.parentId.isNotBlank())
                drItems.firstOrNull { it.parentTransactionId == tx.parentId }?.id
            else null
        }
        // Same idea for wishlist linkage — look the source recurring item up
        // in the locally-cached wishlist so its name surfaces in the sheet.
        val linkedWl = remember(tx.id, tx.wishlistId, allWishlist) {
            if (tx.wishlistId.isNotBlank()) allWishlist.firstOrNull { it.id == tx.wishlistId }
            else null
        }
        TransactionDetailSheet(
            transaction = tx,
            amountColor = expenseColor,
            amountPrefix = "−",
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
            },
            onOpenDetailRequest = { id ->
                detailTx = null
                onOpenDetailRequest(id)
            },
            onCreateDetailRequest = {
                createDrForTx = tx
                detailTx = null
            },
            linkedDetailRequestId = linkedDrId,
            linkedWishlistName = linkedWl?.name,
            linkedWishlistLabel = if (linkedWl?.frequency == "once" || linkedWl?.frequency.isNullOrBlank())
                "Желаемая покупка" else "Регулярный расход",
            onOpenLinkedWishlist = if (linkedWl != null) {
                {
                    wishlistInfo = linkedWl
                    detailTx = null
                }
            } else null,
        )
    }

    wishlistInfo?.let { wl ->
        WishlistInteractiveSheet(
            item = wl,
            primaryColor = primaryColor,
            categories = wishlistCategories,
            onAddCategory = { name -> CategoryRepository.addCategory(serverUrl, "wishlist", name) },
            onDeleteCategory = { id -> CategoryRepository.deleteCategory(serverUrl, "wishlist", id) },
            onSave = { req: UpdateWishlistRequest ->
                WishlistRepository.update(
                    id = wl.id,
                    name = req.name,
                    estimatedCost = req.estimatedCost,
                    category = req.category,
                    frequency = req.frequency,
                    purchased = req.purchased,
                    notes = req.notes,
                    createdBy = req.createdBy,
                )
                Unit
            },
            onGetUsers = { vm.getUsers() },
            onDismiss = { wishlistInfo = null },
            onSaved = { wishlistInfo = null },
        )
    }

    createDrForTx?.let { tx ->
        AssigneePickerDialog(
            primaryColor = primaryColor,
            onGetUsers = { vm.getUsers() },
            onDismiss = {
                createDrForTx = null
                createDrError = null
            },
            onPick = { user ->
                scope.launch {
                    try {
                        val r = website.msdnna.budget_app.data.repository.DetailRequestStore
                            .create(tx.id, user.userId)
                        createDrForTx = null
                        // Refresh local Room copy of the parent so detail_request_id
                        // is reflected in the list immediately.
                        vm.reload()
                        onOpenDetailRequest(r.id)
                    } catch (e: Exception) {
                        createDrError = e.message ?: "Не удалось создать запрос. Возможно, нет соединения."
                    }
                }
            },
        )
    }

    createDrError?.let { err ->
        AlertDialog(
            onDismissRequest = { createDrError = null },
            title = { Text("Ошибка") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { createDrError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun AssigneePickerDialog(
    primaryColor: Color,
    onGetUsers: suspend () -> List<UserInfo>,
    onDismiss: () -> Unit,
    onPick: (UserInfo) -> Unit,
) {
    var users by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        users = onGetUsers()
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Назначить запрос") },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryColor)
                }
            } else {
                LazyColumn {
                    items(users) { user ->
                        ListItem(
                            headlineContent = { Text(user.displayName) },
                            leadingContent = {
                                website.msdnna.budget_app.ui.components.UserAvatar(
                                    displayName = user.displayName,
                                    avatarUrl = user.avatarUrl,
                                    size = 32.dp,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(user) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    primaryColor: Color,
    template: Transaction? = null,
    categories: List<Category> = emptyList(),
    /** Used by the dropdown rows to render the category icon next to its
     *  name. Empty string disables custom-icon rendering (the built-in
     *  glyph still works); pass the active server URL for full fidelity. */
    serverUrl: String = "",
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (CreateTransactionRequest) -> Unit,
    /** Optional override for the sheet header. Used by the «Оплачено» /
     *  «Куплено» flows on the forecast screen so the user can see WHICH
     *  scenario they're in: «Фиксация оплаты» vs «Фиксация покупки». */
    title: String? = null,
) {
    val scope = rememberCoroutineScope()
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var amount by remember { mutableStateOf(template?.amount?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }
    var date by remember { mutableStateOf(today) }
    var category by remember { mutableStateOf(template?.category ?: "") }
    var purpose by remember { mutableStateOf(template?.purpose ?: "") }
    var desc by remember { mutableStateOf(template?.description ?: "") }
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
                title
                    ?: if (template != null) "Создать по шаблону" else "Добавить расход",
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
                value = purpose, onValueChange = { purpose = it },
                label = { Text("Назначение") },
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

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val amtD = amount.replace(',', '.').toDoubleOrNull() ?: return@Button
                    val cat = catInput.trim().ifBlank { category }
                    onSave(
                        CreateTransactionRequest(
                            type = "expense", amount = amtD, date = date,
                            category = cat,
                            purpose = purpose.ifBlank { null },
                            description = desc.ifBlank { null }
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

/**
 * Read-only "Лимит расходов" card. Mirrors IncomeScreen's "Баланс на
 * начало месяца" placement. Shows the *current* calendar-month progress
 * regardless of the active period filter — the limit window is fixed
 * monthly by spec. When the table filter covers a different period, a
 * small "тек. месяц" pill nudges the user that the bar is fresh data
 * (not stale aggregation of the filtered range).
 *
 * Admin can tap to navigate to CategoryLimitsScreen for editing; for
 * non-admins the card is pure display (no chevron, no ripple).
 */
@Composable
private fun LimitsSummaryCard(
    limit: Double,
    spent: Double,
    percent: Double,
    period: String,
    filterFromIso: String?,
    filterToIso: String?,
    valuesHidden: Boolean,
    expenseColor: Color,
    primaryColor: Color,
    isAdmin: Boolean,
    onTap: () -> Unit,
) {
    if (limit <= 0) return
    val tint = when {
        percent >= 100.0 -> expenseColor
        percent >= 80.0 -> Color(0xFFF59E0B)
        else -> Color(0xFF22C55E)
    }
    val showCurrentPill = filteredRangeDiffersFromCurrentMonth(filterFromIso, filterToIso)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(if (isAdmin) Modifier.clickable(onClick = onTap) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Лимит расходов",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showCurrentPill) {
                        Box(
                            modifier = Modifier
                                .background(
                                    Color(0xFF22C55E).copy(alpha = 0.15f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "тек. месяц",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF15803D),
                            )
                        }
                    }
                    Text(
                        prettyPeriod(period),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Hidden state mirrors the SummaryCard pattern: tinted Box
                // placeholder (no shimmer — shimmer is reserved for "loading"
                // semantics) wrapped in a Crossfade so the swap is animated.
                // Both children pinned to 22dp so the row height doesn't
                // flicker when toggling visibility.
                androidx.compose.animation.Crossfade(
                    targetState = valuesHidden,
                    animationSpec = tween(220),
                    label = "limitHidden",
                ) { isHidden ->
                    if (isHidden) {
                        Box(
                            modifier = Modifier
                                .height(22.dp)
                                .width(140.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .background(tint.copy(alpha = 0.22f)),
                        )
                    } else {
                        Text(
                            "${formatMoneyShort(spent)} / ${formatMoneyShort(limit)} ₽",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = tint,
                            modifier = Modifier.height(22.dp),
                        )
                    }
                }
                Text(
                    "${kotlin.math.round(percent).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            OverlayProgress(
                fraction = (percent / 100.0).toFloat(),
                color = tint,
                trackColor = tint.copy(alpha = 0.16f),
                height = 8.dp,
            )
        }
    }
}

private fun filteredRangeDiffersFromCurrentMonth(fromIso: String?, toIso: String?): Boolean {
    if (fromIso.isNullOrBlank() && toIso.isNullOrBlank()) return false
    val cal = java.util.Calendar.getInstance()
    val curStart = cal.apply {
        set(java.util.Calendar.DAY_OF_MONTH, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val curEnd = java.util.Calendar.getInstance().apply {
        timeInMillis = curStart
        add(java.util.Calendar.MONTH, 1)
        add(java.util.Calendar.MILLISECOND, -1)
    }.timeInMillis
    val from = fromIso?.let { runCatching { parseIso(it) }.getOrNull() }
    val to = toIso?.let { runCatching { parseIso(it) }.getOrNull() }
    if (from != null && from < curStart) return true
    if (to != null && to > curEnd) return true
    return false
}

private fun parseIso(iso: String): Long {
    // Filters use yyyy-MM-dd strings (see DateRangePickerField). Other shapes
    // bubble up as parse failures → false from the caller (no pill shown).
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getDefault()
    return fmt.parse(iso)?.time ?: throw IllegalArgumentException(iso)
}

private fun prettyPeriod(yyyyMm: String): String {
    if (yyyyMm.length != 7 || yyyyMm[4] != '-') return yyyyMm
    val year = yyyyMm.substring(0, 4).toIntOrNull() ?: return yyyyMm
    val month = yyyyMm.substring(5, 7).toIntOrNull() ?: return yyyyMm
    val cal = java.util.Calendar.getInstance().apply { set(year, month - 1, 1) }
    val name = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.forLanguageTag("ru-RU"))
        .format(cal.time)
    return name.replaceFirstChar { it.lowercase() }
}

private fun formatMoneyShort(value: Double): String {
    val nf = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("ru-RU"))
    nf.maximumFractionDigits = 0
    return nf.format(value)
}
