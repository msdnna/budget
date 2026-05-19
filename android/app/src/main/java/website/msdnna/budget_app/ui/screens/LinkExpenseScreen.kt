package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.ui.components.CategoryLabel
import website.msdnna.budget_app.ui.components.formatMoney

/**
 * Overlay screen for picking an existing expense to attach to a wishlist or
 * recurring item. Mirrors the web `LinkExistingExpenseModal.vue` flow: only
 * shows expenses with no wishlist link, no parent, no closed-DR involvement
 * (server-side filter via `?unlinked=true`).
 *
 * Two-tap confirmation per row (mirrors the rest of the destructive/state-
 * altering actions in the app) — first tap arms; second tap fires the link.
 * On success we call [onLinked] and pop back; the parent invalidates its
 * forecast cache so the wishlist row reflects the new link immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkExpenseScreen(
    serverUrl: String,
    wishlistId: String,
    wishlistName: String,
    primaryColor: Color,
    onLinked: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val candidates = remember { MutableStateFlow<List<Transaction>>(emptyList()) }
    val candidatesList by candidates.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // pending = id armed for the second-tap commit; null otherwise. busy =
    // id currently being POST'd. Both are mutually exclusive but kept as
    // separate vars so the UI can show distinct states (warning vs spinner).
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val expenseCats by CategoryRepository.expense.collectAsState()

    suspend fun load() {
        loading = true
        error = null
        try {
            val resp = RetrofitClient.getService(serverUrl).getTransactions(
                page = 1,
                limit = 100,
                type = "expense",
                unlinked = true,
            )
            candidates.value = resp.data ?: emptyList()
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить расходы"
            candidates.value = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(serverUrl, wishlistId) { load() }
    BackHandler(onBack = onClose)

    val filtered = run {
        val q = query.trim().lowercase()
        if (q.isEmpty()) candidatesList
        else candidatesList.filter { tx ->
            (tx.purpose ?: "").lowercase().contains(q) ||
                (tx.description ?: "").lowercase().contains(q) ||
                tx.category.lowercase().contains(q) ||
                tx.amount.toInt().toString().contains(q)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Привязать к «$wishlistName»") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "Выберите существующий расход. Его категория и связь с " +
                        "прогнозом будут выставлены автоматически.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Поиск: назначение, категория, сумма…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                )
                when {
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = primaryColor)
                    }
                    error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = { scope.launch { load() } },
                                modifier = Modifier.padding(top = 12.dp),
                            ) { Text("Повторить") }
                        }
                    }
                    filtered.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            if (query.isNotBlank()) "Ничего не найдено"
                            else "Нет несвязанных расходов",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.id }) { tx ->
                            LinkExpenseRow(
                                tx = tx,
                                primaryColor = primaryColor,
                                expenseCats = expenseCats,
                                serverUrl = serverUrl,
                                pending = pending == tx.id,
                                busy = busy == tx.id,
                                disabled = (busy != null && busy != tx.id),
                                onClick = {
                                    if (busy != null) return@LinkExpenseRow
                                    if (pending == tx.id) {
                                        // Second tap = commit.
                                        scope.launch {
                                            busy = tx.id
                                            pending = null
                                            try {
                                                RetrofitClient.getService(serverUrl)
                                                    .linkWishlistToExpense(wishlistId, tx.id)
                                                onLinked()
                                                onClose()
                                            } catch (e: Exception) {
                                                error = e.message ?: "Не удалось привязать"
                                            } finally {
                                                busy = null
                                            }
                                        }
                                    } else {
                                        pending = tx.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkExpenseRow(
    tx: Transaction,
    primaryColor: Color,
    expenseCats: List<Category>,
    serverUrl: String,
    pending: Boolean,
    busy: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    val containerBg = when {
        pending -> Color(0x33F0A020) // amber tint matches the 2-tap pattern
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !disabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerBg),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatDate(tx.date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${formatMoney(tx.amount)} ₽",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tx.category.isNotBlank()) {
                        CategoryLabel(
                            name = tx.category,
                            category = expenseCats.firstOrNull { it.name == tx.category },
                            serverUrl = serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val label = listOfNotNull(tx.purpose, tx.description)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (label.isNotBlank()) {
                        Text(
                            "· $label",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Button(
                onClick = onClick,
                enabled = !disabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pending) Color(0xFFF0A020) else primaryColor,
                ),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    AnimatedContent(
                        targetState = pending,
                        transitionSpec = {
                            (fadeIn(tween(140)) + scaleIn(initialScale = 0.85f, animationSpec = tween(140))) togetherWith
                                (fadeOut(tween(120)) + scaleOut(targetScale = 0.85f, animationSpec = tween(120)))
                        },
                        label = "linkConfirm",
                    ) { p ->
                        if (p) {
                            Text("Подтвердить?")
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp))
                                Text(" Привязать")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(iso: String): String {
    if (iso.isBlank()) return ""
    // YYYY-MM-DDTHH:MM:SSZ → DD.MM.YYYY ; tolerate "YYYY-MM-DD" too.
    val datePart = iso.substringBefore('T')
    val parts = datePart.split("-")
    if (parts.size != 3) return iso
    return "${parts[2]}.${parts[1]}.${parts[0]}"
}
