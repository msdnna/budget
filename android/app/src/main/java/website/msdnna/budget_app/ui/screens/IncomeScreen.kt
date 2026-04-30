package website.msdnna.budget_app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalIncomeColor
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

val INCOME_CATEGORIES = listOf(
    "Зарплата", "Фриланс", "Инвестиции", "Бонус", "Подарок", "Аренда", "Прочее"
)

// Colours for swipe action backgrounds
private val ColourHide     = Color(0xFF1976D2)
private val ColourTemplate = Color(0xFFF59E0B)
private val ColourDelete   = Color(0xFFE53935)

@Composable
fun IncomeScreen(serverUrl: String, primaryColor: Color) {
    val service = remember(serverUrl) { RetrofitClient.getService(serverUrl) }
    val scope   = rememberCoroutineScope()

    var transactions by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var total        by remember { mutableIntStateOf(0) }
    var loading      by remember { mutableStateOf(true) }
    var error        by remember { mutableStateOf<String?>(null) }
    var page         by remember { mutableIntStateOf(1) }
    var filterCat    by remember { mutableStateOf("") }
    var loadKey      by remember { mutableIntStateOf(0) }
    var showAdd      by remember { mutableStateOf(false) }
    var template     by remember { mutableStateOf<Transaction?>(null) }
    var detailTx     by remember { mutableStateOf<Transaction?>(null) }

    fun reload() { loadKey++ }

    LaunchedEffect(loadKey, page, filterCat) {
        loading = true; error = null
        try {
            val resp = service.getTransactions(
                page = page, limit = 30, type = "income",
                category = filterCat.ifBlank { null }
            )
            transactions = resp.data.orEmpty()
            total = resp.total
            loading = false
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Ошибка загрузки"; loading = false
        }
    }

    val incomeColor = LocalIncomeColor.current

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { template = null; showAdd = true },
                containerColor = primaryColor, contentColor = Color.White
            ) { Icon(Icons.Default.Add, "Добавить доход") }
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                        readOnly = true, modifier = Modifier.menuAnchor().width(190.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor),
                        label = { Text("Категория") }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Все категории") }, onClick = {
                            filterCat = ""; expanded = false; page = 1
                        })
                        INCOME_CATEGORIES.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = {
                                filterCat = cat; expanded = false; page = 1
                            })
                        }
                    }
                }
                Text("Всего: $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when {
                loading -> LoadingView()
                error != null -> ErrorView(error!!, ::reload)
                transactions.isEmpty() -> EmptyView("Нет доходов")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(transactions, key = { it.id }) { t ->
                        SwipeableTransactionCard(
                            transaction = t,
                            amountColor = incomeColor,
                            amountPrefix = "+",
                            onDelete = {
                                scope.launch {
                                    runCatching { service.deleteTransaction(t.id) }
                                    reload()
                                }
                            },
                            onToggleHidden = {
                                scope.launch {
                                    runCatching { service.updateTransaction(t.id, mapOf("hidden" to !t.hidden)) }
                                    reload()
                                }
                            },
                            onCreateFromTemplate = {
                                template = t
                                showAdd = true
                            },
                            onDetails = { detailTx = t }
                        )
                    }
                    if (transactions.size < total) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { page++ }) { Text("Загрузить ещё") }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        AddIncomeSheet(
            primaryColor = primaryColor,
            template = template,
            onDismiss = { showAdd = false; template = null },
            onSave = { req ->
                scope.launch {
                    runCatching { service.createTransaction(req) }
                    showAdd = false; template = null; reload()
                }
            }
        )
    }

    detailTx?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            amountColor = incomeColor,
            amountPrefix = "+",
            categories = INCOME_CATEGORIES,
            service = service,
            onDismiss = { detailTx = null },
            onSaved = { detailTx = null; reload() }
        )
    }
}

// ─── Swipeable card ──────────────────────────────────────────────────────────

@Composable
fun SwipeableTransactionCard(
    transaction: Transaction,
    amountColor: Color,
    amountPrefix: String,
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

    fun snapTo(target: Float) = scope.launch {
        offsetX.animateTo(target, spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMedium
        ))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
    ) {
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
                .clickable { snapTo(0f); onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Удалить", color = Color.White, fontSize = 10.sp)
            }
        }

        // ── Foreground: the actual card ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
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
                )
                .clickable { onDetails() },
            colors = CardDefaults.cardColors(
                containerColor = if (transaction.hidden)
                    MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // User avatar
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
                            transaction.description!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

// ─── Transaction detail sheet (view + edit + reassign) ───────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    transaction: Transaction,
    amountColor: Color,
    amountPrefix: String,
    categories: List<String>,
    service: website.msdnna.budget_app.data.api.ApiService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var saving    by remember { mutableStateOf(false) }

    var editAmount   by remember { mutableStateOf(transaction.amount.let { if (it == 0.0) "" else it.toInt().toString() }) }
    var editDate     by remember { mutableStateOf(transaction.date.take(10)) }
    var editCategory by remember { mutableStateOf(transaction.category) }
    var editSource   by remember { mutableStateOf(transaction.source ?: "") }
    var editPurpose  by remember { mutableStateOf(transaction.purpose ?: "") }
    var editDesc     by remember { mutableStateOf(transaction.description ?: "") }
    var catExpanded  by remember { mutableStateOf(false) }

    var showUserPicker by remember { mutableStateOf(false) }
    var users          by remember { mutableStateOf<List<website.msdnna.budget_app.data.model.UserInfo>>(emptyList()) }
    var loadingUsers   by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                Divider()
                Spacer(Modifier.height(16.dp))

                DetailRow("Дата", formatDate(transaction.date))

                val subtitle = transaction.source ?: transaction.purpose
                if (!subtitle.isNullOrBlank()) {
                    val label = if (transaction.type == "income") "Источник" else "Назначение"
                    DetailRow(label, subtitle)
                }
                if (!transaction.description.isNullOrBlank()) {
                    DetailRow("Описание", transaction.description!!)
                }
                if (transaction.hidden) {
                    DetailRow("Статус", "Скрыто")
                }

                Spacer(Modifier.height(4.dp))
                Divider()
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
                                runCatching { service.getUsers() }.onSuccess { users = it }
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
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = amountColor)
                )

                OutlinedTextField(
                    value = editDate, onValueChange = { editDate = it },
                    label = { Text("Дата (ГГГГ-ММ-ДД)") },
                    modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = amountColor)
                )

                ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                    OutlinedTextField(
                        value = editCategory, onValueChange = {}, readOnly = true,
                        label = { Text("Категория") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = amountColor)
                    )
                    ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                        categories.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = { editCategory = cat; catExpanded = false })
                        }
                    }
                }

                if (transaction.type == "income") {
                    OutlinedTextField(
                        value = editSource, onValueChange = { editSource = it },
                        label = { Text("Источник") },
                        modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = amountColor)
                    )
                } else {
                    OutlinedTextField(
                        value = editPurpose, onValueChange = { editPurpose = it },
                        label = { Text("Назначение") },
                        modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = amountColor)
                    )
                }

                OutlinedTextField(
                    value = editDesc, onValueChange = { editDesc = it },
                    label = { Text("Описание (необязательно)") },
                    modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = amountColor)
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
                                runCatching {
                                    service.updateTransactionTyped(transaction.id, website.msdnna.budget_app.data.model.UpdateTransactionRequest(
                                        amount = amt,
                                        date = editDate,
                                        category = editCategory,
                                        source = editSource.ifBlank { null },
                                        purpose = editPurpose.ifBlank { null },
                                        description = editDesc.ifBlank { null }
                                    ))
                                }
                                saving = false
                                onSaved()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = amountColor),
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
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        runCatching {
                                            service.updateTransactionTyped(transaction.id, website.msdnna.budget_app.data.model.UpdateTransactionRequest(createdBy = user))
                                        }
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
    onDismiss: () -> Unit,
    onSave: (CreateTransactionRequest) -> Unit
) {
    val today   = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var amount  by remember { mutableStateOf(template?.amount?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }
    var category by remember { mutableStateOf(template?.category ?: "Зарплата") }
    var source  by remember { mutableStateOf(template?.source ?: "") }
    var desc    by remember { mutableStateOf(template?.description ?: "") }
    var catExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
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

            ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                OutlinedTextField(
                    value = category, onValueChange = {}, readOnly = true,
                    label = { Text("Категория") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                )
                ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                    INCOME_CATEGORIES.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat) }, onClick = { category = cat; catExpanded = false })
                    }
                }
            }

            OutlinedTextField(
                value = source, onValueChange = { source = it },
                label = { Text("Источник") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )
            OutlinedTextField(
                value = desc, onValueChange = { desc = it },
                label = { Text("Описание (необязательно)") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor)
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val amtD = amount.replace(',', '.').toDoubleOrNull() ?: return@Button
                    onSave(CreateTransactionRequest(
                        type = "income", amount = amtD, date = today,
                        category = category,
                        source = source.ifBlank { null },
                        description = desc.ifBlank { null }
                    ))
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = amount.isNotBlank()
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}
