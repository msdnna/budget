package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.CreateTransactionRequest
import website.msdnna.budget_app.data.model.DetailRequestView
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.data.repository.DetailRequestStore
import website.msdnna.budget_app.ui.components.UserAvatar
import website.msdnna.budget_app.ui.components.formatDate
import website.msdnna.budget_app.ui.components.formatMoney

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailRequestScreen(
    requestId: String,
    primaryColor: Color,
    valuesHidden: Boolean,
    currentUserId: String,
    serverUrl: String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<DetailRequestView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    val categories by CategoryRepository.observeBySection("expense").collectAsState(initial = emptyList())

    suspend fun reload() {
        loading = true
        try {
            view = DetailRequestStore.get(requestId)
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    LaunchedEffect(requestId) { reload() }
    BackHandler(onBack = onClose)

    val req = view?.request
    val parent = view?.parent
    val children = view?.children.orEmpty()
    val totalChildren = children.sumOf { it.amount }
    val targetAmount = req?.targetAmount ?: 0.0
    val isOpen = req?.status == "open"
    val isAssignee = req?.assignee?.userId == currentUserId
    val isCreator = req?.creator?.userId == currentUserId

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (isOpen) "Запрос на детализацию" else "Запрос (закрыт)") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    },
                )
            },
            floatingActionButton = {
                if (isOpen && isAssignee) {
                    FloatingActionButton(
                        onClick = { showAddSheet = true },
                        containerColor = primaryColor,
                        contentColor = Color.White,
                    ) { Icon(Icons.Default.Add, "Добавить расход") }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (loading && view == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = primaryColor)
                    }
                } else if (error != null && view == null) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
                    }
                } else if (view != null) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Progress card
                        item {
                            ProgressCard(
                                target = targetAmount,
                                total = totalChildren,
                                count = children.size,
                                primaryColor = primaryColor,
                                valuesHidden = valuesHidden,
                            )
                        }
                        // Meta about parent / assignee
                        item {
                            MetaCard(view!!, currentUserId)
                        }
                        // Children header
                        item {
                            Text(
                                "Расходы по запросу",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        if (children.isEmpty()) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                ) {
                                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (isOpen && isAssignee) "Нажмите +, чтобы добавить расход"
                                            else "Расходы не добавлены",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else {
                            items(children, key = { it.id }) { c ->
                                ChildRow(
                                    tx = c,
                                    canDelete = isOpen && isAssignee,
                                    onDelete = {
                                        scope.launch {
                                            try {
                                                RetrofitClient.getService(serverUrl).deleteTransaction(c.id)
                                                reload()
                                            } catch (_: Exception) { /* ignored */ }
                                        }
                                    },
                                    valuesHidden = valuesHidden,
                                )
                            }
                        }

                        // Actions
                        if (isOpen) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isCreator) {
                                        OutlinedButton(
                                            onClick = { showCancelConfirm = true },
                                            modifier = Modifier.weight(1f),
                                            enabled = !busy,
                                        ) { Text("Отменить запрос") }
                                    }
                                    if (isAssignee) {
                                        Button(
                                            onClick = { showCloseConfirm = true },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                            enabled = !busy && children.isNotEmpty(),
                                        ) {
                                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Готово", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddExpenseSheet(
            primaryColor = primaryColor,
            template = null,
            categories = categories,
            onAddCategory = { name ->
                CategoryRepository.addCategory(serverUrl = serverUrl, section = "expense", name = name)
            },
            onDeleteCategory = { id -> CategoryRepository.deleteCategory(serverUrl = serverUrl, section = "expense", id = id) },
            onDismiss = { showAddSheet = false },
            onSave = { req2 ->
                showAddSheet = false
                scope.launch {
                    busy = true
                    try {
                        DetailRequestStore.addChild(requestId, req2)
                        reload()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Завершить запрос?") },
            text = { Text("Исходная транзакция будет заменена внесёнными расходами в статистике. Действие нельзя отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirm = false
                    scope.launch {
                        busy = true
                        try {
                            DetailRequestStore.close(requestId)
                            onClose()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showCloseConfirm = false }) { Text("Отмена") } },
        )
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Отменить запрос?") },
            text = { Text("Все добавленные расходы по запросу будут удалены, исходная транзакция вернётся в обычное состояние.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    scope.launch {
                        busy = true
                        try {
                            DetailRequestStore.cancel(requestId)
                            onClose()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Отменить") }
            },
            dismissButton = { TextButton(onClick = { showCancelConfirm = false }) { Text("Назад") } },
        )
    }
}

@Composable
private fun ProgressCard(
    target: Double,
    total: Double,
    count: Int,
    primaryColor: Color,
    valuesHidden: Boolean,
) {
    val pct = if (target > 0.0) (total / target).coerceIn(0.0, 1.0).toFloat() else 0f
    val overshoot = (total - target).coerceAtLeast(0.0)
    val remainder = (target - total).coerceAtLeast(0.0)
    val barColor = when {
        overshoot > 0 -> Color(0xFFF0A020)
        pct >= 1.0f -> Color(0xFF18A058)
        else -> primaryColor
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "Прогресс",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (valuesHidden) {
                    Box(
                        Modifier
                            .height(20.dp).width(120.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(barColor.copy(alpha = 0.22f)),
                    )
                } else {
                    Text(
                        "${formatMoney(total)} / ${formatMoney(target)} ₽",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Транзакций: $count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (overshoot > 0) {
                    Text(
                        "+${formatMoney(overshoot)} ₽ сверх",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF0A020),
                    )
                } else if (remainder > 0) {
                    Text(
                        "Остаток в баланс: ${formatMoney(remainder)} ₽",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaCard(view: DetailRequestView, currentUserId: String) {
    val req = view.request ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            view.parent?.let { p ->
                Text(
                    "Транзакция: ${p.category} · ${formatMoney(p.amount)} ₽",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                p.purpose?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Дата: ${formatDate(p.date)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UserAvatar(
                    displayName = req.assignee?.displayName.orEmpty(),
                    avatarUrl = req.assignee?.avatarUrl,
                    size = 24.dp,
                )
                Text(
                    "Исполнитель: ${req.assignee?.displayName.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (req.creator != null && req.creator.userId != req.assignee?.userId) {
                Text(
                    "Создал: ${req.creator.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChildRow(
    tx: Transaction,
    canDelete: Boolean,
    onDelete: () -> Unit,
    valuesHidden: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatDate(tx.date), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tx.category, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium)
                }
                val sub = tx.purpose ?: tx.description
                if (!sub.isNullOrBlank()) {
                    Text(sub, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (valuesHidden) {
                Box(
                    Modifier
                        .height(18.dp).width(60.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33D03050)),
                )
            } else {
                Text(
                    "−${formatMoney(tx.amount)} ₽",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD03050),
                )
            }
            if (canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
