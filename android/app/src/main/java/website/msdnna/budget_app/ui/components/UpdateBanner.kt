package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import website.msdnna.budget_app.data.update.DownloadProgress

@Composable
fun UpdateBanner(
    latestVersion: String,
    primaryColor: Color,
    onClick: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Side and top match the inner screens' .padding(16.dp); bottom is 0
            // so the screen's own top padding provides the gap below — keeps top
            // and bottom visual margins symmetric.
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Обновите приложение",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Доступна версия v$latestVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Скрыть",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun MandatoryUpdateDialog(
    latestVersion: String,
    primaryColor: Color,
    progress: DownloadProgress?,
    canInstall: Boolean,
    onDownload: () -> Unit,
    onGrantInstallPermission: () -> Unit,
) {
    Dialog(
        onDismissRequest = { /* not dismissable */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    "Требуется обновление",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Текущая версия приложения больше не поддерживается сервером. " +
                        "Установите v$latestVersion, чтобы продолжить.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (progress) {
                    is DownloadProgress.Running -> {
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = primaryColor,
                                drawStopIndicator = {},
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = primaryColor,
                            )
                        }
                        Text(
                            "${(progress.fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is DownloadProgress.Failed -> {
                        Text(
                            "Ошибка загрузки: ${progress.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        ) { Text("Повторить") }
                    }
                    is DownloadProgress.Done, null -> {
                        if (!canInstall) {
                            Text(
                                "Разрешите установку приложений из этого источника в настройках.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = onGrantInstallPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            ) { Text("Открыть настройки") }
                        } else {
                            Button(
                                onClick = onDownload,
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            ) { Text("Скачать и установить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OptionalUpdateProgressDialog(
    latestVersion: String,
    primaryColor: Color,
    progress: DownloadProgress,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            // Allow dismiss only when not actively downloading.
            if (progress !is DownloadProgress.Running) onDismiss()
        },
        title = { Text("Обновление до v$latestVersion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (progress) {
                    is DownloadProgress.Running -> {
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = primaryColor,
                                drawStopIndicator = {},
                            )
                            Text(
                                "${(progress.fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = primaryColor,
                            )
                        }
                    }
                    is DownloadProgress.Failed -> {
                        Text(
                            "Ошибка: ${progress.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is DownloadProgress.Done -> {
                        Text(
                            "Загрузка завершена. Запуск установщика…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (progress !is DownloadProgress.Running) {
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}
