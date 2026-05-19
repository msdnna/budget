package website.msdnna.budget_app.ui.screens

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import website.msdnna.budget_app.BuildConfig
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.discovery.DiscoveredServer
import website.msdnna.budget_app.data.discovery.ServerDiscovery
import website.msdnna.budget_app.data.model.LoginRequest
import website.msdnna.budget_app.ui.components.MbLogo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    primaryColor: Color,
    savedServerUrl: String?,
    serverHistory: List<String> = emptyList(),
    onAuthenticated: (
        serverUrl: String,
        token: String,
        refreshToken: String,
        userId: String,
        displayName: String,
        avatarUrl: String?,
        isAdmin: Boolean,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val savedHost = savedServerUrl?.removePrefix("https://")?.removePrefix("http://") ?: ""
    val savedSsl = savedServerUrl?.startsWith("https://") ?: false

    var step by remember { mutableStateOf(if (!savedServerUrl.isNullOrBlank()) 2 else 1) }
    var serverHost by remember { mutableStateOf(savedHost) }
    var useHttps by remember { mutableStateOf(savedSsl) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Discovery state — local-network scan results.
    var discoveryActive by remember { mutableStateOf(false) }
    var discoveryFinished by remember { mutableStateOf(false) }
    val discovered = remember { mutableStateListOf<DiscoveredServer>() }
    var discoveryJob by remember { mutableStateOf<Job?>(null) }

    fun startDiscovery() {
        discoveryJob?.cancel()
        discovered.clear()
        discoveryFinished = false
        discoveryActive = true
        discoveryJob = scope.launch {
            try {
                ServerDiscovery.discover(context).collect { server ->
                    // Avoid duplicates if both http and https probes match (rare but possible).
                    if (discovered.none { it.url == server.url }) discovered += server
                }
            } finally {
                discoveryActive = false
                discoveryFinished = true
            }
        }
    }

    fun cancelDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discoveryActive = false
    }

    DisposableEffect(Unit) {
        onDispose { discoveryJob?.cancel() }
    }

    val passwordFocus = remember { FocusRequester() }

    val fullServerUrl by remember { derivedStateOf { "${if (useHttps) "https" else "http"}://${serverHost.trim()}" } }

    fun connectStep() {
        val host = serverHost.trim()
        if (host.isBlank()) {
            error = "Введите адрес сервера"
            return
        }
        val url = "${if (useHttps) "https" else "http"}://$host"
        error = null
        loading = true
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
                        if (msg.isNotBlank()) {
                            append("\n")
                            append(msg.lines().first().take(120))
                        }
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
        if (login.isBlank()) {
            error = "Введите логин"
            return
        }
        if (password.isBlank()) {
            error = "Введите пароль"
            return
        }
        error = null
        loading = true
        scope.launch {
            try {
                val service = RetrofitClient.getService(fullServerUrl)
                val resp = service.login(LoginRequest(login.trim(), password))
                loading = false
                onAuthenticated(
                    fullServerUrl,
                    resp.token,
                    resp.refreshToken,
                    resp.userId,
                    resp.displayName,
                    resp.avatarUrl,
                    resp.isAdmin,
                )
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
    ) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp).align(Alignment.Center),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(32.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MbLogo(primaryColor = primaryColor, size = 100.dp)

                Spacer(Modifier.height(40.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(28.dp))

                androidx.compose.animation.AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (
                                androidx.compose.animation.slideInHorizontally(
                                    animationSpec = androidx.compose.animation.core.tween(280),
                                    initialOffsetX = { it / 4 }
                                ) + androidx.compose.animation.fadeIn(
                                    animationSpec = androidx.compose.animation.core.tween(280)
                                )
                                ) togetherWith (
                                androidx.compose.animation.slideOutHorizontally(
                                    animationSpec = androidx.compose.animation.core.tween(220),
                                    targetOffsetX = { -it / 4 }
                                ) + androidx.compose.animation.fadeOut(
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                )
                                )
                        } else {
                            (
                                androidx.compose.animation.slideInHorizontally(
                                    animationSpec = androidx.compose.animation.core.tween(280),
                                    initialOffsetX = { -it / 4 }
                                ) + androidx.compose.animation.fadeIn(
                                    animationSpec = androidx.compose.animation.core.tween(280)
                                )
                                ) togetherWith (
                                androidx.compose.animation.slideOutHorizontally(
                                    animationSpec = androidx.compose.animation.core.tween(220),
                                    targetOffsetX = { it / 4 }
                                ) + androidx.compose.animation.fadeOut(
                                    animationSpec = androidx.compose.animation.core.tween(220)
                                )
                                )
                        }
                    },
                    label = "connectStep",
                ) { currentStep ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (currentStep == 1) {
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
                                    onValueChange = {
                                        serverHost = it
                                        error = null
                                    },
                                    label = { Text("Адрес сервера") },
                                    placeholder = { Text("192.168.1.100:8080") },
                                    isError = error != null,
                                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                                    singleLine = true,
                                    enabled = !loading,
                                    trailingIcon = if (serverHistory.isNotEmpty()) {
                                        { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) }
                                    } else null,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { connectStep() }),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor, focusedLabelColor = primaryColor)
                                )
                                if (serverHistory.isNotEmpty()) {
                                    ExposedDropdownMenu(
                                        expanded = dropdownExpanded,
                                        onDismissRequest = { dropdownExpanded = false }
                                    ) {
                                        serverHistory.forEach { histUrl ->
                                            val histHost = histUrl.removePrefix("https://").removePrefix("http://")
                                            val histSsl = histUrl.startsWith("https://")
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

                            Spacer(Modifier.height(12.dp))
                            DiscoverySection(
                                primaryColor = primaryColor,
                                active = discoveryActive,
                                finished = discoveryFinished,
                                results = discovered,
                                enabled = !loading,
                                onStart = ::startDiscovery,
                                onCancel = ::cancelDiscovery,
                                onPick = { srv ->
                                    serverHost = srv.host
                                    useHttps = srv.ssl
                                    error = null
                                },
                            )

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
                                // Back button is always available so the user can change the server
                                // address even after a logout (logout doesn't clear the saved URL).
                                IconButton(onClick = {
                                    step = 1
                                    error = null
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Изменить сервер", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "Вход в аккаунт", style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.width(32.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                fullServerUrl, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(24.dp))

                            OutlinedTextField(
                                value = login,
                                onValueChange = {
                                    login = it
                                    error = null
                                },
                                label = { Text("Логин") },
                                isError = error != null,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !loading,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor, focusedLabelColor = primaryColor)
                            )
                            Spacer(Modifier.height(12.dp))

                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    error = null
                                },
                                label = { Text("Пароль") },
                                isError = error != null,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                                singleLine = true,
                                enabled = !loading,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { loginStep() }),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor, focusedLabelColor = primaryColor)
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
                    } // Column wrapper
                } // AnimatedContent

                Spacer(Modifier.height(16.dp))
            }
        }
        Text(
            "msdnnaBudget · v${BuildConfig.VERSION_NAME}",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun DiscoverySection(
    primaryColor: Color,
    active: Boolean,
    finished: Boolean,
    results: List<DiscoveredServer>,
    enabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onPick: (DiscoveredServer) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = if (active) onCancel else onStart,
            // heightIn(min) instead of a hard height so the button can grow
            // by one line if a future label outgrows the row instead of
            // clipping the second line under the bottom border.
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor),
        ) {
            if (active) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = primaryColor,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Сканирование… Отменить",
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            } else {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (finished && results.isEmpty()) "Ничего не найдено — повторить"
                    else "Поиск в локальной сети",
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        if (active || results.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                results.forEach { srv ->
                    DiscoveredServerCard(srv = srv, primaryColor = primaryColor, onClick = { onPick(srv) })
                }
                if (active && results.isEmpty()) {
                    Text(
                        "Поиск серверов в подсети…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveredServerCard(
    srv: DiscoveredServer,
    primaryColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(srv.host, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (srv.ssl) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (srv.ssl) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (srv.ssl) "HTTPS" else "HTTP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (srv.apiVersion.isNotBlank()) {
                        Text(
                            " · API v${srv.apiVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            Icon(
                Icons.Default.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp).padding(top = 2.dp)
            )
            Text(
                error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
