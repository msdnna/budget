package website.msdnna.budget_app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.TelegramLinkInitResponse
import website.msdnna.budget_app.data.model.TelegramLinkStatus

/**
 * Settings → Telegram. Per-user binding screen — mirrors the web
 * `SettingsTelegramView.vue` layout:
 *
 *  - Loading state: spinner.
 *  - Linked: show `@username` + linked-at timestamp + Unlink button.
 *  - Not linked, no pending code: "Сгенерировать код" CTA.
 *  - Pending code: large mono-font 6-char code + live countdown (TTL 5 min,
 *    enforced server-side by repository/telegram_repo.go). When the timer
 *    hits zero we clear the pending UI so the user has to regenerate.
 *
 * `botUsername` defaults to `msdnna_budget_bot` (single-tenant family
 * install). If we ever ship per-instance bot handles, expose it via
 * AppPreferences instead of hard-coding here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramScreen(
    serverUrl: String,
    primaryColor: Color,
    onClose: () -> Unit,
    botUsername: String = "msdnna_budget_bot",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var loading by remember { mutableStateOf(true) }
    var generating by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<TelegramLinkStatus?>(null) }
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var pendingExpiresAt by remember { mutableStateOf<OffsetDateTime?>(null) }
    var countdownSec by remember { mutableStateOf(0L) }

    BackHandler(onBack = onClose)

    fun showError(msg: String) {
        scope.launch { snackbarHost.showSnackbar(msg) }
    }

    suspend fun refreshStatus() {
        loading = true
        try {
            status = RetrofitClient.getService(serverUrl).getTelegramLink()
        } catch (_: Exception) {
            showError("Не удалось загрузить статус привязки")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(serverUrl) { refreshStatus() }

    // Live countdown — re-runs while pendingExpiresAt is non-null. Decoupled
    // from the request scope so a slow network call doesn't pause it.
    LaunchedEffect(pendingExpiresAt) {
        val target = pendingExpiresAt ?: return@LaunchedEffect
        while (true) {
            val left = ChronoUnit.SECONDS.between(OffsetDateTime.now(), target)
            countdownSec = left.coerceAtLeast(0)
            if (countdownSec <= 0L) {
                pendingCode = null
                pendingExpiresAt = null
                break
            }
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Telegram") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = primaryColor)
                }
                return@Column
            }

            val curStatus = status
            when {
                curStatus?.linked == true -> LinkedState(
                    status = curStatus,
                    unlinking = unlinking,
                    onUnlink = {
                        unlinking = true
                        scope.launch {
                            try {
                                RetrofitClient.getService(serverUrl).deleteTelegramLink()
                                status = TelegramLinkStatus(linked = false)
                                pendingCode = null
                                pendingExpiresAt = null
                            } catch (_: Exception) {
                                showError("Не удалось отвязать")
                            } finally {
                                unlinking = false
                            }
                        }
                    },
                )

                pendingCode != null -> PendingCodeState(
                    code = pendingCode!!,
                    countdownSec = countdownSec,
                    generating = generating,
                    primaryColor = primaryColor,
                    onCopy = { copyToClipboard(context, "/link ${pendingCode!!}") },
                    onRegenerate = {
                        generating = true
                        scope.launch {
                            generateCode(serverUrl)?.let { resp ->
                                pendingCode = resp.code
                                pendingExpiresAt = parseExpires(resp.expiresAt)
                            } ?: showError("Не удалось сгенерировать код")
                            generating = false
                        }
                    },
                    onCancel = {
                        pendingCode = null
                        pendingExpiresAt = null
                    },
                    onRefreshStatus = {
                        scope.launch { refreshStatus() }
                    },
                )

                else -> UnlinkedState(
                    botUsername = botUsername,
                    generating = generating,
                    primaryColor = primaryColor,
                    onGenerate = {
                        generating = true
                        scope.launch {
                            generateCode(serverUrl)?.let { resp ->
                                pendingCode = resp.code
                                pendingExpiresAt = parseExpires(resp.expiresAt)
                            } ?: showError("Не удалось сгенерировать код")
                            generating = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LinkedState(
    status: TelegramLinkStatus,
    unlinking: Boolean,
    onUnlink: () -> Unit,
) {
    Text(
        "Аккаунт привязан к боту. Можно писать в свободной форме — например, " +
            "«продукты магнит 2300» — и подтверждать кнопкой в Telegram.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Telegram", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = status.telegramUsername?.let { "@$it" }
                        ?: status.telegramUserId.takeIf { it > 0 }?.let { "id $it" }
                        ?: "—",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Привязан", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatLinkedAt(status.linkedAt))
            }
        }
    }
    OutlinedButton(
        enabled = !unlinking,
        onClick = onUnlink,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        if (unlinking) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text("Отвязать")
    }
}

@Composable
private fun UnlinkedState(
    botUsername: String,
    generating: Boolean,
    primaryColor: Color,
    onGenerate: () -> Unit,
) {
    Text(
        "Привяжите ваш аккаунт к боту, чтобы добавлять транзакции из Telegram в " +
            "свободной форме. Бот распознаёт сумму, категорию и контрагента.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "1. Найдите бота в Telegram: @$botUsername\n" +
            "2. Нажмите «Сгенерировать код» ниже.\n" +
            "3. Отправьте боту команду /link <КОД>.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(
        onClick = onGenerate,
        enabled = !generating,
        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
    ) {
        if (generating) {
            CircularProgressIndicator(
                strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = Color.White,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text("Сгенерировать код")
    }
}

@Composable
private fun PendingCodeState(
    code: String,
    countdownSec: Long,
    generating: Boolean,
    primaryColor: Color,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit,
    onRefreshStatus: () -> Unit,
) {
    Text(
        "Отправьте боту команду:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("/link", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                code,
                fontSize = 36.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                "Истекает через ${formatCountdown(countdownSec)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopy) { Text("Скопировать") }
        OutlinedButton(onClick = onRegenerate, enabled = !generating) {
            if (generating) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("Новый код")
        }
        TextButton(onClick = onCancel) { Text("Отмена") }
    }
    Text(
        "После того как бот ответит «✅ Привязка выполнена», нажмите ниже — " +
            "и появится отвязка.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onRefreshStatus) { Text("Обновить статус") }
}

// ─── helpers ────────────────────────────────────────────────────────────

private suspend fun generateCode(serverUrl: String): TelegramLinkInitResponse? =
    try {
        RetrofitClient.getService(serverUrl).initTelegramLink()
    } catch (_: Exception) {
        null
    }

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("telegram link command", text))
}

private fun parseExpires(iso: String): OffsetDateTime? =
    try {
        OffsetDateTime.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }

private fun formatCountdown(sec: Long): String {
    if (sec <= 0) return "истёк"
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

private fun formatLinkedAt(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        val dt = OffsetDateTime.parse(iso)
        dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    } catch (_: DateTimeParseException) {
        iso
    }
}
