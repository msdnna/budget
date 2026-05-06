package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.UpdateTransactionRequest
import website.msdnna.budget_app.data.model.UserInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.viewmodels.ExpensesViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ExpensesScreen(
    serverUrl: String,
    primaryColor: Color,
    valuesHidden: Boolean = false,
    onSelectionCountChange: (Int) -> Unit = {},
) {
    val vm = viewModel<ExpensesViewModel>(key = "expenses:$serverUrl", factory = ExpensesViewModel.factory(serverUrl))
    val uiState    by vm.uiState.collectAsState()
    val filterCats by vm.filterCats.collectAsState()
    val categories by vm.categories.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()

    val scope = rememberCoroutineScope()
    var showAdd      by remember { mutableStateOf(false) }
    var template     by remember { mutableStateOf<Transaction?>(null) }
    var detailTx     by remember { mutableStateOf<Transaction?>(null) }

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
        { tx -> template = tx; showAdd = true }
    }
    val onShowDetails: (Transaction) -> Unit = remember {
        { tx -> detailTx = tx }
    }

    Scaffold(
        // FAB swap snaps without animation — AnimatedContent's cross-fade made
        // the FAB shadows overlap at intermediate alphas, leaving a muddy
        // residue.
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
                ) { Icon(Icons.Default.Add, "Добавить расход") }
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
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryFilterField(
                    selected = filterCats,
                    categories = categories,
                    primaryColor = primaryColor,
                    onToggle = { vm.toggleFilterCategory(it) },
                    onClear = { vm.clearFilterCategories() },
                    onDelete = { id -> scope.launch { vm.deleteCategory(id) } },
                    modifier = Modifier.weight(1f),
                )
                Text("Всего: ${uiState.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when {
                uiState.loading -> SkeletonTransactionList()
                uiState.error != null -> ErrorView(uiState.error!!, { vm.reload() })
                uiState.transactions.isEmpty() -> EmptyView("Нет расходов")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(uiState.transactions, key = { it.id }) { t ->
                        SwipeableTransactionCard(
                            modifier = Modifier.animateItem(),
                            transaction = t,
                            amountColor = expenseColor,
                            amountPrefix = "−",
                            primaryColor = primaryColor,
                            valuesHidden = valuesHidden,
                            selectionMode = selectionMode,
                            selected = t.id in selectedIds,
                            onLongPress    = vm::startSelection,
                            onSelectToggle = vm::toggleSelection,
                            onDelete             = vm::deleteTransaction,
                            onToggleHidden       = vm::toggleHidden,
                            onCreateFromTemplate = onCreateTemplate,
                            onDetails            = onShowDetails,
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
            onAddCategory    = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onDismiss = { showAdd = false; template = null },
            onSave = { req -> vm.createTransaction(req); showAdd = false; template = null }
        )
    }

    detailTx?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            amountColor = expenseColor,
            amountPrefix = "−",
            primaryColor = primaryColor,
            categories = categories,
            onAddCategory    = { name -> vm.addCategory(name) },
            onDeleteCategory = { id -> vm.deleteCategory(id) },
            onSave           = { req -> vm.updateTransaction(tx.id, req) },
            onGetUsers       = { vm.getUsers() },
            onDismiss = { detailTx = null },
            onSaved   = { detailTx = null; vm.reload() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    primaryColor: Color,
    template: Transaction? = null,
    categories: List<Category> = emptyList(),
    onAddCategory: suspend (String) -> Category? = { null },
    onDeleteCategory: suspend (String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (CreateTransactionRequest) -> Unit
) {
    val scope    = rememberCoroutineScope()
    val today    = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var amount   by remember { mutableStateOf(template?.amount?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }
    var date     by remember { mutableStateOf(today) }
    var category by remember { mutableStateOf(template?.category ?: "") }
    var purpose  by remember { mutableStateOf(template?.purpose ?: "") }
    var desc     by remember { mutableStateOf(template?.description ?: "") }
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
                if (template != null) "Создать по шаблону" else "Добавить расход",
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
                    onSave(CreateTransactionRequest(
                        type = "expense", amount = amtD, date = date,
                        category = cat,
                        purpose = purpose.ifBlank { null },
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
