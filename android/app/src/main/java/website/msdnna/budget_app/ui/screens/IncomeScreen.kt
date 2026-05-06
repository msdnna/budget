package website.msdnna.budget_app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UpdateTransactionRequest
import website.msdnna.budget_app.data.model.UserInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalIncomeColor
import website.msdnna.budget_app.ui.viewmodels.IncomeViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// Colours for swipe action backgrounds
private val ColourHide     = Color(0xFF1976D2)
private val ColourTemplate = Color(0xFFF59E0B)
private val ColourDelete   = Color(0xFFE53935)

@Composable
fun IncomeScreen(
    serverUrl: String,
    primaryColor: Color,
    valuesHidden: Boolean = false,
    onSelectionCountChange: (Int) -> Unit = {},
) {
    val vm = viewModel<IncomeViewModel>(key = "income:$serverUrl", factory = IncomeViewModel.factory(serverUrl))
    val uiState    by vm.uiState.collectAsState()
    val filterCat  by vm.filterCat.collectAsState()
    val categories by vm.categories.collectAsState()
    val ibYear     by vm.ibYear.collectAsState()
    val ibMonth    by vm.ibMonth.collectAsState()
    val ibRecord   by vm.ibRecord.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()

    val scope = rememberCoroutineScope()
    var showAdd      by remember { mutableStateOf(false) }
    var template     by remember { mutableStateOf<Transaction?>(null) }
    var detailTx     by remember { mutableStateOf<Transaction?>(null) }
    var showIbForm   by remember { mutableStateOf(false) }

    val incomeColor = LocalIncomeColor.current

    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    LaunchedEffect(selectedIds.size) { onSelectionCountChange(selectedIds.size) }

    val selectedTxs = remember(selectedIds, uiState.transactions) {
        uiState.transactions.filter { it.id in selectedIds }
    }
    val allHidden = selectedTxs.isNotEmpty() && selectedTxs.all { it.hidden }

    Scaffold(
        // FAB swap snaps with no transition: AnimatedContent's cross-fade
        // composed both FABs simultaneously, and their elevation shadows
        // overlapped at intermediate alphas — visibly muddy. A snap is the
        // cleanest workaround.
        floatingActionButton = {
            if (selectionMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton(
                        onClick = { vm.bulkSetHiddenSelected(targetHidden = !allHidden) },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor   = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Icon(
                            if (allHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (allHidden) "Показать выбранные" else "Скрыть выбранные"
                        )
                    }
                    FloatingActionButton(
                        onClick = { vm.bulkDeleteSelected() },
                        containerColor = Color(0xFFE53935),
                        contentColor   = Color.White,
                    ) { Icon(Icons.Default.Delete, "Удалить выбранные") }
                }
            } else {
                FloatingActionButton(
                    onClick = { template = null; showAdd = true },
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
        Column(Modifier.fillMaxSize()) {
            // Initial balance card
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Баланс на начало месяца",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Month navigator
                            IconButton(onClick = { vm.ibNavigateBack() }, modifier = Modifier.size(24.dp)) { Text("‹") }
                            Text(
                                "${monthName(ibMonth)} $ibYear",
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(onClick = { vm.ibNavigateForward() }, modifier = Modifier.size(24.dp)) { Text("›") }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (ibRecord != null) {
                            if (valuesHidden) {
                                Box(
                                    Modifier.height(18.dp).width(70.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .background(primaryColor.copy(alpha = 0.22f))
                                )
                            } else {
                                Text(
                                    "${formatMoney(ibRecord!!.amount)} ₽",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                            }
                        } else {
                            Text("Не задан",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = { showIbForm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(if (ibRecord != null) "Изменить" else "Задать", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Category filter
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = filterCat.ifBlank { "Все категории" }, onValueChange = {},
                        readOnly = true, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).width(190.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor),
                        label = { Text("Категория") }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Все категории") }, onClick = {
                            vm.setFilter(""); expanded = false
                        })
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = { vm.setFilter(cat.name); expanded = false },
                                trailingIcon = if (!cat.isDefault) {{
                                    IconButton(onClick = { scope.launch { vm.deleteCategory(cat.id) }; expanded = false }, modifier = Modifier.size(20.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                                    }
                                }} else null
                            )
                        }
                    }
                }
                Text("Всего: ${uiState.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            onLongPress    = { vm.startSelection(t.id) },
                            onSelectToggle = { vm.toggleSelection(t.id) },
                            onDelete             = { vm.deleteTransaction(t.id) },
                            onToggleHidden       = { vm.toggleHidden(t.id, t.hidden) },
                            onCreateFromTemplate = { template = t; showAdd = true },
                            onDetails            = { detailTx = t }
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
            onAddCategory    = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onDismiss = { showAdd = false; template = null },
            onSave = { req -> vm.createTransaction(req); showAdd = false; template = null }
        )
    }

    detailTx?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            amountColor = incomeColor,
            amountPrefix = "+",
            primaryColor = primaryColor,
            categories = categories,
            onAddCategory    = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onSave     = { req -> vm.updateTransaction(tx.id, req) },
            onGetUsers = { vm.getUsers() },
            onDismiss  = { detailTx = null },
            onSaved    = { detailTx = null; vm.reload() }
        )
    }

    if (showIbForm) {
        AddInitialBalanceSheet(
            primaryColor = primaryColor,
            currentAmount = ibRecord?.amount,
            onDismiss = { showIbForm = false },
            onSave = { amount -> vm.saveInitialBalance(amount); showIbForm = false }
        )
    }
}

// ─── Swipeable card ──────────────────────────────────────────────────────────

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
    onLongPress: () -> Unit = {},
    onSelectToggle: () -> Unit = {},
    onDelete: () -> Unit,
    onToggleHidden: () -> Unit,
    onCreateFromTemplate: () -> Unit,
    onDetails: () -> Unit = {}
) {
    val scope   = rememberCoroutineScope()
    val density = LocalDensity.current

    // Reveal widths
    val leftRevealDp  = 144.dp   // right-swipe → Hide + Template
    val rightRevealDp = 76.dp    // left-swipe  → Delete

    val leftRevealPx  = with(density) { leftRevealDp.toPx() }
    val rightRevealPx = with(density) { rightRevealDp.toPx() }

    val offsetX = remember(transaction.id) { Animatable(0f) }
    var pendingDelete by remember { mutableStateOf(false) }

    fun snapTo(target: Float) = scope.launch {
        offsetX.animateTo(target, spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ))
        if (target == 0f) pendingDelete = false
    }

    // When entering selection mode, retract any open swipe rails.
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
                        .clickable { snapTo(0f); onToggleHidden() },
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
                        .clickable { snapTo(0f); onCreateFromTemplate() },
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
                            onDelete()
                        } else {
                            pendingDelete = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(
                        if (pendingDelete) "Подтвердить?" else "Удалить",
                        color = Color.White, fontSize = 10.sp
                    )
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
                            offsetX.value > leftRevealPx  * 0.35f -> snapTo(leftRevealPx)
                            offsetX.value < -rightRevealPx * 0.35f -> snapTo(-rightRevealPx)
                            else -> snapTo(0f)
                        }
                    }
                ) else base
            }
            .combinedClickable(
                onClick     = { if (selectionMode) onSelectToggle() else onDetails() },
                onLongClick = { if (!selectionMode) onLongPress() }
            )

        // Animate the card background tint smoothly between normal/selected.
        // We `compositeOver` the surface so the resulting color is opaque —
        // a translucent containerColor would let the swipe rails behind the
        // card bleed through during the deselect → "selectionMode flips
        // false" transition (rails get composed again, fade hasn't finished).
        val baseSurface = MaterialTheme.colorScheme.surface
        val targetBg = when {
            selected           -> primaryColor.copy(alpha = 0.16f).compositeOver(baseSurface)
            transaction.hidden -> MaterialTheme.colorScheme.surfaceVariant
            else               -> baseSurface
        }
        val animatedBg by androidx.compose.animation.animateColorAsState(
            targetValue = targetBg,
            animationSpec = tween(180),
            label = "cardBg"
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
                transaction.createdBy?.let { author ->
                    UserAvatar(
                        displayName = author.displayName,
                        avatarUrl = author.avatarUrl,
                        size = 30.dp
                    )
                }

                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            formatDate(transaction.date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            transaction.category,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (transaction.hidden) {
                            Text("скрыто",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
                if (valuesHidden) {
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

        SelectionOverlay(
            visible = selectionMode,
            selected = selected,
            primaryColor = primaryColor,
            onClick = onSelectToggle,
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
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onSave: suspend (UpdateTransactionRequest) -> Unit,
    onGetUsers: suspend () -> List<UserInfo>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var saving    by remember { mutableStateOf(false) }

    var editAmount   by remember { mutableStateOf(transaction.amount.let { if (it == 0.0) "" else it.toInt().toString() }) }
    var editDate     by remember { mutableStateOf(transaction.date.take(10)) }
    var editCategory by remember { mutableStateOf(transaction.category) }
    var editCatInput by remember { mutableStateOf(transaction.category) }
    var editSource   by remember { mutableStateOf(transaction.source ?: "") }
    var editPurpose  by remember { mutableStateOf(transaction.purpose ?: "") }
    var editDesc     by remember { mutableStateOf(transaction.description ?: "") }
    var catExpanded  by remember { mutableStateOf(false) }

    val catFiltered = remember(editCatInput, categories) {
        if (editCatInput.isBlank()) categories
        else categories.filter { it.name.contains(editCatInput, ignoreCase = true) }
    }
    val catShowCreate = editCatInput.isNotBlank() && categories.none { it.name.equals(editCatInput.trim(), ignoreCase = true) }

    var showUserPicker by remember { mutableStateOf(false) }
    var users          by remember { mutableStateOf<List<website.msdnna.budget_app.data.model.UserInfo>>(emptyList()) }
    var loadingUsers   by remember { mutableStateOf(false) }

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
            if (!isEditing) {
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
                        Text(
                            text = transaction.category,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { isEditing = true }) {
                        Icon(Icons.Default.Edit, "Редактировать", tint = amountColor)
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
            } else {
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
                                    val name = editCatInput.trim()
                                    catExpanded = false
                                    scope.launch {
                                        val cat = onAddCategory(name)
                                        if (cat != null) { editCatInput = cat.name; editCategory = cat.name }
                                        else { editCategory = name; editCatInput = name }
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
                                onSave(UpdateTransactionRequest(
                                    amount = amt,
                                    date = editDate,
                                    category = editCatInput.trim().ifBlank { editCategory },
                                    source = editSource.ifBlank { null },
                                    purpose = editPurpose.ifBlank { null },
                                    description = editDesc.ifBlank { null }
                                ))
                                saving = false
                                onSaved()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        enabled = !saving && editAmount.isNotBlank()
                    ) { Text(if (saving) "…" else "Сохранить", fontWeight = FontWeight.SemiBold) }
                }
            }
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
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (CreateTransactionRequest) -> Unit
) {
    val scope   = rememberCoroutineScope()
    val today   = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var amount  by remember { mutableStateOf(template?.amount?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }
    var date    by remember { mutableStateOf(today) }
    var category by remember { mutableStateOf(template?.category ?: "") }
    var source  by remember { mutableStateOf(template?.source ?: "") }
    var desc    by remember { mutableStateOf(template?.description ?: "") }
    var catExpanded by remember { mutableStateOf(false) }
    var catInput    by remember { mutableStateOf(template?.category ?: "") }

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
                    onValueChange = { catInput = it; catExpanded = true },
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
                    if (showCreate) {
                        DropdownMenuItem(
                            text = { Text("Добавить: «${catInput.trim()}»", color = primaryColor) },
                            onClick = {
                                val name = catInput.trim()
                                catExpanded = false
                                scope.launch {
                                    val cat = onAddCategory(name)
                                    if (cat != null) { catInput = cat.name; category = cat.name }
                                    else { category = name; catInput = name }
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

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val amtD = amount.replace(',', '.').toDoubleOrNull() ?: return@Button
                    val cat = catInput.trim().ifBlank { category }
                    onSave(CreateTransactionRequest(
                        type = "income", amount = amtD, date = date,
                        category = cat,
                        source = source.ifBlank { null },
                        description = desc.ifBlank { null }
                    ))
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = amount.isNotBlank() && catInput.isNotBlank()
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ─── Add / edit initial balance sheet ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInitialBalanceSheet(
    primaryColor: Color,
    currentAmount: Double?,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var amount by remember { mutableStateOf(currentAmount?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .imePadding()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Баланс на начало месяца", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = amount, onValueChange = { amount = it },
                label = { Text("Сумма, ₽") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val amtD = amount.replace(',', '.').toDoubleOrNull() ?: return@Button
                    onSave(amtD)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = amount.isNotBlank()
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}
