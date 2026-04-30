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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.CreateWishlistRequest
import website.msdnna.budget_app.data.model.ForecastData
import website.msdnna.budget_app.data.model.WishlistItem
import website.msdnna.budget_app.ui.components.*
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
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

private val WISHLIST_CATEGORIES = listOf(
    "Электроника", "Одежда", "Путешествия", "Образование", "Здоровье",
    "Развлечения", "Дом", "Спорт", "Красота", "Прочее"
)

private val ColourPurchased = Color(0xFF388E3C)   // right swipe: mark purchased
private val ColourWlDelete  = Color(0xFFE53935)   // left  swipe: delete

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun ForecastScreen(serverUrl: String, primaryColor: Color) {
    val expenseColor = LocalExpenseColor.current
    val service = remember(serverUrl) { RetrofitClient.getService(serverUrl) }
    val scope   = rememberCoroutineScope()

    var forecast    by remember { mutableStateOf<ForecastData?>(null) }
    var wishlist    by remember { mutableStateOf<List<WishlistItem>>(emptyList()) }
    var loading     by remember { mutableStateOf(true) }
    var error       by remember { mutableStateOf<String?>(null) }
    var loadKey     by remember { mutableIntStateOf(0) }
    var showAdd     by remember { mutableStateOf(false) }
    var detailItem  by remember { mutableStateOf<WishlistItem?>(null) }

    fun reload() { loadKey++ }

    LaunchedEffect(loadKey) {
        loading = true; error = null
        try {
            val f = service.getForecast()
            val w = service.getWishlist()
            forecast = f; wishlist = w; loading = false
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Ошибка загрузки"; loading = false
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true },
                containerColor = primaryColor, contentColor = Color.White) {
                Icon(Icons.Default.Add, "Добавить в список")
            }
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        when {
            loading -> LoadingView()
            error != null -> ErrorView(error!!, ::reload)
            else -> {
                val fc = forecast ?: ForecastData()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── Summary cards ──────────────────────────────────────
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryCard("Прогноз / мес",  fc.totalMonthly,    "", expenseColor, Modifier.weight(1f))
                            SummaryCard("Ср. за 3 мес",   fc.historicalAvg,   "", primaryColor, Modifier.weight(1f))
                        }
                    }
                    item {
                        SummaryCard("Список желаний / мес", fc.wishlistContrib, "", primaryColor, Modifier.fillMaxWidth())
                    }

                    // ── Breakdown by category ──────────────────────────────
                    if (fc.breakdown.isNotEmpty()) {
                        item {
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
                                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }

                    // ── Wishlist title ────────────────────────────────────
                    item {
                        Text("Список желаний", style = MaterialTheme.typography.titleMedium)
                    }

                    // ── Wishlist items with swipe ─────────────────────────
                    if (wishlist.isEmpty()) {
                        item { EmptyView("Список желаний пуст") }
                    } else {
                        items(wishlist, key = { it.id }) { item ->
                            SwipeableWishlistCard(
                                item = item,
                                primaryColor = primaryColor,
                                onTogglePurchased = {
                                    scope.launch {
                                        runCatching {
                                            service.updateWishlistItem(item.id, mapOf("purchased" to !item.purchased))
                                        }
                                        reload()
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        runCatching { service.deleteWishlistItem(item.id) }
                                        reload()
                                    }
                                },
                                onDetails = { detailItem = item }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        AddWishlistSheet(
            primaryColor = primaryColor,
            onDismiss = { showAdd = false },
            onSave = { req ->
                scope.launch {
                    runCatching { service.createWishlistItem(req) }
                    showAdd = false; reload()
                }
            }
        )
    }

    detailItem?.let { item ->
        WishlistInteractiveSheet(
            item = item,
            primaryColor = primaryColor,
            service = service,
            onDismiss = { detailItem = null },
            onSaved = { detailItem = null; reload() }
        )
    }
}

// ─── Swipeable wishlist card ──────────────────────────────────────────────────

@Composable
fun SwipeableWishlistCard(
    item: WishlistItem,
    primaryColor: Color,
    onTogglePurchased: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit = {}
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
        // ── Left background: "Куплено/Не куплено" (only for once items) ──
        if (!recurring) {
            Box(
                modifier = Modifier
                    .width(leftRevealDp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(if (item.purchased) Color(0xFF757575) else ColourPurchased)
                    .clickable { snapTo(0f); onTogglePurchased() },
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
                .clickable { snapTo(0f); onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Text("Удалить", color = Color.White, fontSize = 10.sp)
            }
        }

        // ── Foreground card ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
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
                )
                .clickable { onDetails() },
            colors = CardDefaults.cardColors(
                containerColor = if (item.purchased)
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
    }
}

// ─── Wishlist interactive sheet (view + edit + reassign) ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistInteractiveSheet(
    item: WishlistItem,
    primaryColor: Color,
    service: website.msdnna.budget_app.data.api.ApiService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    var saving    by remember { mutableStateOf(false) }

    var editName      by remember { mutableStateOf(item.name) }
    var editCost      by remember { mutableStateOf(item.estimatedCost.let { if (it == 0.0) "" else it.toInt().toString() }) }
    var editCategory  by remember { mutableStateOf(item.category) }
    var editFrequency by remember { mutableStateOf(item.frequency) }
    var editNotes     by remember { mutableStateOf(item.notes ?: "") }
    var editPurchased by remember { mutableStateOf(item.purchased) }
    var catExpanded   by remember { mutableStateOf(false) }
    var freqExpanded  by remember { mutableStateOf(false) }

    var showUserPicker  by remember { mutableStateOf(false) }
    var users           by remember { mutableStateOf<List<website.msdnna.budget_app.data.model.UserInfo>>(emptyList()) }
    var loadingUsers    by remember { mutableStateOf(false) }

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
                Divider()
                Spacer(Modifier.height(16.dp))

                WishlistDetailRow("Категория", item.category)
                WishlistDetailRow("Периодичность", frequencyLabel(item.frequency))
                if (!isRecurring(item.frequency) && !item.purchased) {
                    WishlistDetailRow("Ежемес. вклад", "≈ ${formatMoney(monthlyContribution(item))} ₽/мес")
                }
                WishlistDetailRow("Статус", if (item.purchased) "Куплено ✓" else "Не куплено")
                if (!item.notes.isNullOrBlank()) {
                    WishlistDetailRow("Заметки", item.notes!!)
                }

                Spacer(Modifier.height(4.dp))
                Divider()
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
                                runCatching { service.getUsers() }.onSuccess { users = it }
                                loadingUsers = false
                            }
                        }
                    }) { Text(if (item.createdBy != null) "Сменить" else "Назначить") }
                }
            } else {
                // ── Edit mode ──────────────────────────────────────────────
                Text("Редактировать", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = editName, onValueChange = { editName = it },
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
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
                        value = editCategory, onValueChange = {}, readOnly = true,
                        label = { Text("Категория") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                    )
                    ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                        WISHLIST_CATEGORIES.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat) }, onClick = { editCategory = cat; catExpanded = false })
                        }
                    }
                }

                ExposedDropdownMenuBox(expanded = freqExpanded, onExpandedChange = { freqExpanded = it }) {
                    OutlinedTextField(
                        value = frequencyLabel(editFrequency), onValueChange = {}, readOnly = true,
                        label = { Text("Периодичность") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(freqExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
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
                                runCatching {
                                    service.updateWishlistItemTyped(item.id, website.msdnna.budget_app.data.model.UpdateWishlistRequest(
                                        name = editName,
                                        estimatedCost = costD,
                                        category = editCategory,
                                        frequency = editFrequency,
                                        notes = editNotes.ifBlank { null },
                                        purchased = editPurchased
                                    ))
                                }
                                saving = false
                                onSaved()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        enabled = !saving && editName.isNotBlank() && editCost.isNotBlank()
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
                        CircularProgressIndicator(color = primaryColor)
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
                                            service.updateWishlistItemTyped(item.id, website.msdnna.budget_app.data.model.UpdateWishlistRequest(createdBy = user))
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
    onDismiss: () -> Unit,
    onSave: (CreateWishlistRequest) -> Unit
) {
    var name     by remember { mutableStateOf("") }
    var cost     by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Прочее") }
    var frequency by remember { mutableStateOf("once") }
    var catExpanded  by remember { mutableStateOf(false) }
    var freqExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Добавить в список желаний", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Название") },
                modifier = Modifier.fillMaxWidth().bringIntoViewOnFocus(), singleLine = true,
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
                    value = category, onValueChange = {}, readOnly = true,
                    label = { Text("Категория") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                )
                ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                    WISHLIST_CATEGORIES.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat) }, onClick = { category = cat; catExpanded = false })
                    }
                }
            }

            ExposedDropdownMenuBox(expanded = freqExpanded, onExpandedChange = { freqExpanded = it }) {
                OutlinedTextField(
                    value = frequencyLabel(frequency), onValueChange = {}, readOnly = true,
                    label = { Text("Периодичность") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(freqExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(focusedBorderColor = primaryColor)
                )
                ExposedDropdownMenu(expanded = freqExpanded, onDismissRequest = { freqExpanded = false }) {
                    FREQUENCIES.forEach { (key, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { frequency = key; freqExpanded = false })
                    }
                }
            }

            // Explanation based on frequency
            val hint = when (frequency) {
                "once"      -> "Учитывается в прогнозе каждый месяц до отметки «Куплено»"
                "monthly"   -> "Учитывается ежемесячно в прогнозе"
                "quarterly" -> "Стоимость / 3 добавляется к прогнозу каждый месяц"
                "yearly"    -> "Стоимость / 12 добавляется к прогнозу каждый месяц"
                else        -> ""
            }
            if (hint.isNotEmpty()) {
                Text(hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val costD = cost.replace(',', '.').toDoubleOrNull() ?: return@Button
                    if (name.isBlank()) return@Button
                    onSave(CreateWishlistRequest(
                        name = name, estimatedCost = costD, category = category,
                        frequency = frequency
                    ))
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = name.isNotBlank() && cost.isNotBlank()
            ) { Text("Сохранить", fontWeight = FontWeight.SemiBold) }
        }
    }
}
