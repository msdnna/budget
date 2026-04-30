package website.msdnna.budget_app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import website.msdnna.budget_app.ui.components.MbLogo
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.LoginRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    primaryColor: Color,
    savedServerUrl: String?,
    serverHistory: List<String> = emptyList(),
    onAuthenticated: (serverUrl: String, token: String, displayName: String, avatarUrl: String?) -> Unit
) {
    val scope = rememberCoroutineScope()

    val savedHost = savedServerUrl?.removePrefix("https://")?.removePrefix("http://") ?: ""
    val savedSsl  = savedServerUrl?.startsWith("https://") ?: false

    var step             by remember { mutableStateOf(if (!savedServerUrl.isNullOrBlank()) 2 else 1) }
    var serverHost       by remember { mutableStateOf(savedHost) }
    var useHttps         by remember { mutableStateOf(savedSsl) }
    var login            by remember { mutableStateOf("") }
    var password         by remember { mutableStateOf("") }
    var error            by remember { mutableStateOf<String?>(null) }
    var loading          by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val passwordFocus = remember { FocusRequester() }

    val fullServerUrl by remember { derivedStateOf { "${if (useHttps) "https" else "http"}://${serverHost.trim()}" } }

    fun connectStep() {
        val host = serverHost.trim()
        if (host.isBlank()) { error = "Введите адрес сервера"; return }
        val url = "${if (useHttps) "https" else "http"}://$host"
        error = null; loading = true
        scope.launch {
            try {
                val health = RetrofitClient.getService(url).health()
                if (!health.ok || health.app != "msdnna-budget") {
                    loading = false
                    error = "Сервер не является msdnna Budget"
                    return@launch
                }
            } catch (e: Exception) {
                loading = false
                val msg = e.message ?: ""
                val isNetworkError = msg.contains("Unable to resolve", true) ||
                    msg.contains("Failed to connect", true) ||
                    msg.contains("timeout", true) ||
                    msg.contains("Connection refused", true) ||
                    msg.contains("ECONNREFUSED", true)
                error = when {
                    isNetworkError -> buildString {
                        append("Не удалось подключиться к серверу")
                        if (msg.isNotBlank()) { append("\n"); append(msg.lines().first().take(120)) }
                    }
                    msg.contains("404") || msg.contains("Not Found", true) ->
                        "Сервер не является msdnna Budget"
                    else -> msg.lines().firstOrNull()?.take(120) ?: "Ошибка подключения"
                }
                return@launch
            }
            serverHost = host
            loading = false
            error = null
            step = 2
        }
    }

    fun loginStep() {
        if (login.isBlank()) { error = "Введите логин"; return }
        if (password.isBlank()) { error = "Введите пароль"; return }
        error = null; loading = true
        scope.launch {
            try {
                val service = RetrofitClient.getService(fullServerUrl)
                val resp = service.login(LoginRequest(login.trim(), password))
                loading = false
                onAuthenticated(fullServerUrl, resp.token, resp.displayName, resp.avatarUrl)
            } catch (e: Exception) {
                loading = false
                error = when {
                    e.message?.contains("401") == true ||
                    e.message?.contains("Неверный") == true -> "Неверный логин или пароль"
                    else -> e.message?.lines()?.firstOrNull()?.take(120) ?: "Ошибка входа"
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(32.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MbLogo(primaryColor = primaryColor, fontSize = 80.sp)

                Spacer(Modifier.height(40.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(28.dp))

                if (step == 1) {
                    // ── Step 1: Server URL ────────────────────────────
                    Text("Подключение к серверу", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Введите адрес вашего сервера семейного бюджета",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(28.dp))

                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded && serverHistory.isNotEmpty(),
                        onExpandedChange = { if (serverHistory.isNotEmpty()) dropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = serverHost,
                            onValueChange = { serverHost = it; error = null },
                            label = { Text("Адрес сервера") },
                            placeholder = { Text("192.168.1.100:8080") },
                            isError = error != null,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true,
                            enabled = !loading,
                            trailingIcon = if (serverHistory.isNotEmpty()) {
                                { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { connectStep() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor, focusedLabelColor = primaryColor)
                        )
                        if (serverHistory.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                serverHistory.forEach { histUrl ->
                                    val histHost = histUrl.removePrefix("https://").removePrefix("http://")
                                    val histSsl  = histUrl.startsWith("https://")
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(histHost)
                                                Text(
                                                    if (histSsl) "HTTPS" else "HTTP",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            serverHost = histHost
                                            useHttps = histSsl
                                            dropdownExpanded = false
                                            error = null
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Использовать SSL (HTTPS)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = useHttps,
                            onCheckedChange = { useHttps = it },
                            enabled = !loading,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = primaryColor,
                                checkedTrackColor = primaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }

                    ErrorBlock(error)
                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = ::connectStep,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        enabled = !loading
                    ) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Проверка соединения…", fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Подключиться", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    // ── Step 2: Login ─────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (savedServerUrl.isNullOrBlank()) {
                            IconButton(onClick = { step = 1; error = null }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("Вход в аккаунт", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        if (savedServerUrl.isNullOrBlank()) Spacer(Modifier.width(32.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(fullServerUrl, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it; error = null },
                        label = { Text("Логин") },
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor, focusedLabelColor = primaryColor)
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Пароль") },
                        isError = error != null,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { loginStep() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor, focusedLabelColor = primaryColor)
                    )

                    ErrorBlock(error)
                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = ::loginStep,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        enabled = !loading
                    ) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Вход…", fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Войти", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("msdnnaBudget", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ErrorBlock(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Text(error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
