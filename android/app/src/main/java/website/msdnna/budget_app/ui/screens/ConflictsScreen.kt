package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.AppContainer
import website.msdnna.budget_app.data.db.CategoryEntity
import website.msdnna.budget_app.data.db.TransactionEntity
import website.msdnna.budget_app.data.db.WishlistEntity
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.data.model.WishlistItem

private val gson = Gson()

private sealed class ConflictItem {
    abstract val id: String
    abstract val entity: String
    abstract val title: String
    abstract val localFields: List<Pair<String, String>>
    abstract val serverFields: List<Pair<String, String>>

    data class Tx(val local: TransactionEntity, val server: Transaction?) : ConflictItem() {
        override val id = local.id
        override val entity = "transaction"
        override val title = "Транзакция · ${local.category}"
        override val localFields = listOf(
            "Сумма" to "%.2f ₽".format(local.amount),
            "Дата" to formatDate(local.date),
            "Категория" to local.category,
            "Описание" to local.description.orEmpty(),
            "Скрыта" to if (local.hidden) "да" else "нет",
            "Версия" to local.version.toString(),
            "Изменил" to (local.lastModifiedByName ?: "—"),
        )
        override val serverFields = if (server == null) emptyList()
        else if (server.deletedAt != null) listOf(
            "Состояние" to "удалена",
            "Кем" to (server.lastModifiedBy?.displayName ?: "—"),
            "Версия" to server.version.toString(),
        ) else listOf(
            "Сумма" to "%.2f ₽".format(server.amount),
            "Дата" to formatDate(server.date),
            "Категория" to server.category,
            "Описание" to server.description.orEmpty(),
            "Скрыта" to if (server.hidden) "да" else "нет",
            "Версия" to server.version.toString(),
            "Изменил" to (server.lastModifiedBy?.displayName ?: "—"),
        )
    }

    data class Wl(val local: WishlistEntity, val server: WishlistItem?) : ConflictItem() {
        override val id = local.id
        override val entity = "wishlist"
        override val title = "Вишлист · ${local.name}"
        override val localFields = listOf(
            "Название" to local.name,
            "Стоимость" to "%.2f ₽".format(local.estimatedCost),
            "Категория" to local.category,
            "Куплено" to if (local.purchased) "да" else "нет",
            "Версия" to local.version.toString(),
            "Изменил" to (local.lastModifiedByName ?: "—"),
        )
        override val serverFields = if (server == null) emptyList()
        else if (server.deletedAt != null) listOf(
            "Состояние" to "удалён",
            "Кем" to (server.lastModifiedBy?.displayName ?: "—"),
            "Версия" to server.version.toString(),
        ) else listOf(
            "Название" to server.name,
            "Стоимость" to "%.2f ₽".format(server.estimatedCost),
            "Категория" to server.category,
            "Куплено" to if (server.purchased) "да" else "нет",
            "Версия" to server.version.toString(),
            "Изменил" to (server.lastModifiedBy?.displayName ?: "—"),
        )
    }

    data class Cat(val local: CategoryEntity, val server: Category?) : ConflictItem() {
        override val id = local.id
        override val entity = "category"
        override val title = "Категория · ${local.name}"
        override val localFields = listOf(
            "Раздел" to local.section,
            "Имя" to local.name,
            "Версия" to local.version.toString(),
        )
        override val serverFields = if (server == null) emptyList()
        else if (server.deletedAt != null) listOf(
            "Состояние" to "удалена",
            "Версия" to server.version.toString(),
        ) else listOf(
            "Раздел" to server.section,
            "Имя" to server.name,
            "Версия" to server.version.toString(),
        )
    }
}

/**
 * Trim a date down to YYYY-MM-DD regardless of whether it came from a local
 * mutation (`"2026-05-03"`) or a server pull (`"2026-05-03T00:00:00Z"`), so
 * the conflict diff doesn't show two different formats for the same day.
 */
private fun formatDate(s: String): String =
    if (s.length >= 10 && s[4] == '-' && s[7] == '-') s.substring(0, 10) else s

@Composable
fun ConflictsScreen(serverUrl: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onClose)

    val txConflicts by AppContainer.db.transactions().observeConflicts()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val wlConflicts by AppContainer.db.wishlist().observeConflicts()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val catConflicts by AppContainer.db.categories().observeConflicts()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val items: List<ConflictItem> = remember(txConflicts, wlConflicts, catConflicts) {
        buildList {
            txConflicts.forEach { e ->
                val s = parseServerPayload<Transaction>(e.serverPayload)
                add(ConflictItem.Tx(e, s))
            }
            wlConflicts.forEach { e ->
                val s = parseServerPayload<WishlistItem>(e.serverPayload)
                add(ConflictItem.Wl(e, s))
            }
            catConflicts.forEach { e ->
                val s = parseServerPayload<Category>(e.serverPayload)
                add(ConflictItem.Cat(e, s))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Конфликты синхронизации") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Конфликтов нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { "${it.entity}:${it.id}" }) { item ->
                    ConflictCard(
                        item = item,
                        onKeepLocal = {
                            scope.launch {
                                AppContainer.syncEngine.resolveKeepLocal(serverUrl, item.entity, item.id)
                            }
                        },
                        onKeepServer = {
                            scope.launch {
                                AppContainer.syncEngine.resolveKeepServer(item.entity, item.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(item: ConflictItem, onKeepLocal: () -> Unit, onKeepServer: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FieldsColumn(label = "Моя версия", fields = item.localFields, modifier = Modifier.weight(1f))
                FieldsColumn(label = "Версия с сервера", fields = item.serverFields, modifier = Modifier.weight(1f))
            }

            // "Take server" is the safer/non-destructive default (it doesn't
            // overwrite work made by another user), so it lives on the right
            // as the filled primary action. "Keep mine" force-pushes local
            // over the server version — kept as the outlined secondary button.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onKeepLocal, modifier = Modifier.weight(1f)) {
                    Text("Оставить мою")
                }
                Button(onClick = onKeepServer, modifier = Modifier.weight(1f)) {
                    Text("Принять серверную")
                }
            }
        }
    }
}

@Composable
private fun FieldsColumn(label: String, fields: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (fields.isEmpty()) {
            Text(
                "—", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            fields.forEach { (k, v) ->
                Text("$k: $v", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private inline fun <reified T> parseServerPayload(json: String?): T? {
    if (json.isNullOrBlank()) return null
    return runCatching { gson.fromJson(json, T::class.java) }.getOrNull()
}
