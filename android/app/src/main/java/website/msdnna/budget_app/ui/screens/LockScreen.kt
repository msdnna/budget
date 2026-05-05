package website.msdnna.budget_app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.api.RetrofitClient
import website.msdnna.budget_app.data.model.LoginRequest
import website.msdnna.budget_app.data.security.BiometricHelper
import website.msdnna.budget_app.data.security.PinSecurity
import website.msdnna.budget_app.ui.components.MbLogo

private const val PIN_LENGTH = 4

@Composable
fun LockScreen(
    primaryColor: Color,
    pinSalt: String,
    pinHash: String,
    biometricEnabled: Boolean,
    serverUrl: String,
    onUnlocked: () -> Unit,
    onPinReset: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var biometricAttempted by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }

    fun submit(pin: String) {
        if (pin.length != PIN_LENGTH) return
        if (PinSecurity.verify(pin, pinSalt, pinHash)) {
            onUnlocked()
        } else {
            error = "Неверный PIN-код"
            scope.launch {
                delay(800)
                entered = ""
            }
        }
    }

    fun launchBiometric() {
        if (activity == null) return
        if (!BiometricHelper.isAvailable(context)) return
        BiometricHelper.authenticate(
            activity = activity,
            title = "Вход в msdnna Budget",
            subtitle = "Подтвердите вход биометрией",
            negativeButton = "Использовать PIN",
            onSuccess = { onUnlocked() },
            onError = { msg -> error = msg?.toString() },
            onCancel = { /* fall back to PIN */ },
        )
    }

    LaunchedEffect(Unit) {
        if (biometricEnabled && !biometricAttempted) {
            biometricAttempted = true
            launchBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            MbLogo(primaryColor = primaryColor, fontSize = 64.sp)
            Spacer(Modifier.height(24.dp))
            Text(
                "Введите PIN-код",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            PinDots(
                length = PIN_LENGTH,
                filled = entered.length,
                primaryColor = primaryColor,
                error = error != null,
            )

            Spacer(Modifier.height(12.dp))
            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.height(24.dp))

            PinKeypad(
                primaryColor = primaryColor,
                showBiometric = biometricEnabled && BiometricHelper.isAvailable(context),
                onDigit = { d ->
                    if (entered.length < PIN_LENGTH) {
                        error = null
                        entered += d
                        if (entered.length == PIN_LENGTH) submit(entered)
                    }
                },
                onBackspace = {
                    if (entered.isNotEmpty()) {
                        error = null
                        entered = entered.dropLast(1)
                    }
                },
                onBiometric = { launchBiometric() },
            )

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { showRecovery = true }) {
                Text("Забыли PIN?", color = primaryColor)
            }
        }
    }

    if (showRecovery) {
        ForgotPinDialog(
            primaryColor = primaryColor,
            serverUrl = serverUrl,
            onDismiss = { showRecovery = false },
            onRecovered = {
                showRecovery = false
                onPinReset()
            },
        )
    }
}

@Composable
private fun PinDots(length: Int, filled: Int, primaryColor: Color, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(length) { i ->
            val isFilled = i < filled
            val color = when {
                error -> MaterialTheme.colorScheme.error
                isFilled -> primaryColor
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isFilled || error) color else Color.Transparent),
            ) {
                if (!isFilled && !error) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinKeypad(
    primaryColor: Color,
    showBiometric: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("bio", "0", "back"),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 280.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        when (key) {
                            "bio" -> {
                                if (showBiometric) {
                                    KeypadButton(onClick = onBiometric, primaryColor = primaryColor) {
                                        Icon(
                                            Icons.Default.Fingerprint,
                                            contentDescription = "Биометрия",
                                            tint = primaryColor,
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.size(72.dp))
                                }
                            }
                            "back" -> KeypadButton(onClick = onBackspace, primaryColor = primaryColor) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Стереть",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            else -> KeypadButton(onClick = { onDigit(key) }, primaryColor = primaryColor) {
                                Text(
                                    key,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    primaryColor: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun ForgotPinDialog(
    primaryColor: Color,
    serverUrl: String,
    onDismiss: () -> Unit,
    onRecovered: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val passwordFocus = remember { FocusRequester() }

    fun recover() {
        if (login.isBlank()) { error = "Введите логин"; return }
        if (password.isBlank()) { error = "Введите пароль"; return }
        error = null; loading = true
        scope.launch {
            try {
                RetrofitClient.getService(serverUrl).login(LoginRequest(login.trim(), password))
                loading = false
                onRecovered()
            } catch (e: Exception) {
                loading = false
                error = when {
                    e.message?.contains("401") == true -> "Неверный логин или пароль"
                    else -> e.message?.lines()?.firstOrNull()?.take(120) ?: "Ошибка проверки"
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("Сброс PIN-кода") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Подтвердите права на аккаунт — введите логин и пароль. " +
                        "После проверки PIN и биометрия будут сброшены, и вы сможете задать новый PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it; error = null },
                    label = { Text("Логин") },
                    isError = error != null,
                    singleLine = true,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        focusedLabelColor = primaryColor,
                    ),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Пароль") },
                    isError = error != null,
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { recover() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryColor,
                        focusedLabelColor = primaryColor,
                    ),
                )
                if (error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::recover, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = primaryColor,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Сбросить PIN", color = primaryColor)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Отмена") }
        },
    )
}
