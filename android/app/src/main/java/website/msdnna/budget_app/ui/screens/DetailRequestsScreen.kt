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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.DetailRequest
import website.msdnna.budget_app.data.repository.DetailRequestStore
import website.msdnna.budget_app.ui.components.UserAvatar
import website.msdnna.budget_app.ui.components.formatMoney

/**
 * List of detail-requests with two tabs:
 *  - Назначенные мне (assignee_id == me): default; "open" filter is implicit
 *    via [openOnly] when entering from the header badge.
 *  - Закрытые (status == closed) — only when [showClosed] is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailRequestsScreen(
    primaryColor: Color,
    currentUserId: String,
    showAll: Boolean = false,                   // when false: only open requests assigned to me
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val items by DetailRequestStore.items.collectAsStateWithLifecycle()
    val loading by DetailRequestStore.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }   // 0=open, 1=closed (only when showAll)

    LaunchedEffect(Unit) {
        DetailRequestStore.refresh()
    }
    BackHandler(onBack = onClose)

    val visible = remember(items, tab, showAll, currentUserId) {
        if (!showAll) {
            items.filter { it.status == "open" && it.assignee?.userId == currentUserId }
        } else {
            val targetStatus = if (tab == 0) "open" else "closed"
            items.filter {
                it.status == targetStatus &&
                    (it.assignee?.userId == currentUserId || it.creator?.userId == currentUserId)
            }
        }.sortedByDescending { it.createdAt }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (showAll) "Запросы на детализацию" else "Открытые запросы") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    },
                    actions = {
                        TextButton(onClick = { scope.launch { DetailRequestStore.refresh() } }) {
                            Text("Обновить")
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (showAll) {
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = primaryColor,
                    ) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text("Открытые") },
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text("Закрытые") },
                        )
                    }
                }

                if (loading && visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = primaryColor)
                    }
                } else if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (showAll && tab == 1) "Нет закрытых запросов" else "Нет открытых запросов",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.id }) { r ->
                            DetailRequestRow(r, primaryColor, currentUserId, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRequestRow(
    r: DetailRequest,
    primaryColor: Color,
    currentUserId: String,
    onOpen: (String) -> Unit,
) {
    val isAssignee = r.assignee?.userId == currentUserId
    val containerBg = if (r.status == "open" && isAssignee)
        Color(0x33F0A020) else MaterialTheme.colorScheme.surface
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(r.id) },
        colors = CardDefaults.cardColors(containerColor = containerBg),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UserAvatar(
                displayName = r.assignee?.displayName.orEmpty(),
                avatarUrl = r.assignee?.avatarUrl,
                size = 36.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "${formatMoney(r.targetAmount)} ₽",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Исполнитель: ${r.assignee?.displayName.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.creator != null && r.creator.userId != r.assignee?.userId) {
                    Text(
                        "Создал: ${r.creator.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (r.status == "open") Color(0xFFF0A020) else Color(0xFF888888))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    if (r.status == "open") "открыт" else "закрыт",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
