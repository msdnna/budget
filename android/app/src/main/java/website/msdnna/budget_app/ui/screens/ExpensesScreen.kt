package website.msdnna.budget_app.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import java.text.SimpleDateFormat
import java.util.*

val EXPENSE_CATEGORIES = listOf(
    "Продукты", "Транспорт", "Жильё/ЖКХ", "Рестораны", "Развлечения",
    "Здоровье", "Образование", "Одежда", "Электроника", "Путешествия",
    "Связь", "Красота", "Спорт", "Прочее"
)

@Composable
fun ExpensesScreen(serverUrl: String, primaryColor: Color) {
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
                page = page, limit = 30, type = "expense",
                category = filterCat.ifBlank { null }
            )
            transactions = resp.data.orEmpty()
            total = resp.total
            loading = false
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Ошибка загрузки"; loading = false
        }
    }

    val expenseColor = LocalExpenseColor.current

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { template = null; showAdd = true },
                containerColor = primaryColor, contentColor = Color.White
            ) { Icon(Icons.Default.Add, "Добавить расход") }
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = filterCat.ifBlank { "Все категории" }, onValueChange = {},
                        readOnly = true, modifier = Modifier.menuAnchor().width(195.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor),
                        label = { Text("Категория") }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Все категории") }, onClick = {
                            filterCat = ""; expanded = false; page = 1
                        })
                        EXPENSE_CATEGORIES.forEach { cat ->
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
                transactions.isEmpty() -> EmptyView("Нет расходов")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(transactions, key = { it.id }) { t ->
                        SwipeableTransactionCard(
                            transaction = t,
                            amountColor = expenseColor,
                            amountPrefix = "−",
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
        AddExpenseSheet(
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
            amountColor = expenseColor,
            amountPrefix = "−",
            categories = EXPENSE_CATEGORIES,
            service = service,
            onDismiss = { detailTx = null },
            onSaved = { detailTx = null; reload() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    primaryColor: Color,
    template: Transaction? = null,
    onDismiss: () -> Unit,
    onSave: (CreateTransactionRequest) -> Unit
) {
    val today    = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    var amount   by remember { mutableStateOf(template?.amount?.let { if (it == 0.0) "" else it.toInt().toString() } ?: "") }
    var category by remember { mutableStateOf(template?.category ?: "Продукты") }
    var purpose  by remember { mutableStateOf(template?.purpose ?: "") }
    var desc     by remember { mutableStateOf(template?.description ?: "") }
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

            ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                OutlinedTextField(
                    value = category, onValueChange = {}, readOnly = true,
                    label = { Text("Категория") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                )
                ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                    EXPENSE_CATEGORIES.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat) }, onClick = { category = cat; catExpanded = false })
                    }
                }
            }

            OutlinedTextField(
                value = purpose, onValueChange = { purpose = it },
                label = { Text("Назначение") },
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
                        type = "expense", amount = amtD, date = today,
                        category = category,
                        purpose = purpose.ifBlank { null },
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
