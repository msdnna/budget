package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.db.NotificationHistoryEntity
import website.msdnna.budget_app.data.repository.NotificationHistoryRepository

/**
 * In-app bell-history feed (full-screen overlay). Lists newest-first
 * limit-overflow alerts (server-sourced) and reminder fires (local).
 * "Прочитать все" marks every row locally AND POSTs to
 * /notifications/read-all so the server-side per-user state stays in
 * sync when the user opens the bell on multiple devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsHistoryScreen(
    primaryColor: Color,
    serverUrl: String?,
    onClose: () -> Unit,
) {
    val rows by NotificationHistoryRepository.observeRecent()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unreadCount by NotificationHistoryRepository.observeUnreadCount()
        .collectAsStateWithLifecycle(initialValue = 0)
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    TextButton(
                        enabled = unreadCount > 0,
                        onClick = {
                            scope.launch {
                                NotificationHistoryRepository.markAllRead(serverUrl)
                            }
                        },
                    ) {
                        Text("Прочитать все", color = primaryColor)
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    NotificationRow(row = row, primaryColor = primaryColor)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Уведомлений пока нет",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotificationRow(row: NotificationHistoryEntity, primaryColor: Color) {
    val (icon, tint) = iconFor(row.type)
    val unreadBg = if (row.readLocal) Color.Transparent
    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(unreadBg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.readLocal) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
            )
            if (row.body.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    row.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(row.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                // outline reads as a placeholder tint in M3; onSurfaceVariant
                // keeps the date readable on both themes without competing
                // with the primary body text above.
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun iconFor(type: String): Pair<ImageVector, Color> = when (type) {
    "global_limit_exceeded" -> Icons.Default.PriorityHigh to Color(0xFFEF4444)
    "category_limit_exceeded" -> Icons.Default.WarningAmber to Color(0xFFEF4444)
    else -> Icons.Default.NotificationsActive to Color(0xFF6366F1)
}
