package website.msdnna.budget_app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.security.BiometricHelper
import website.msdnna.budget_app.ui.components.MbLogo

private const val PIN_LENGTH = 4

@Composable
fun PinSetupScreen(
    primaryColor: Color,
    title: String = "Установка PIN-кода",
    subtitle: String = "Создайте 4-значный PIN-код для защиты приложения",
    showBiometricOption: Boolean = true,
    showSkip: Boolean = true,
    onSkip: () -> Unit = {},
    onCancel: (() -> Unit)? = null,
    onPinSet: (pin: String, enableBiometric: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val biometricAvailable = remember { BiometricHelper.isAvailable(context) }

    var step by remember { mutableStateOf(1) } // 1 = enter, 2 = confirm
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var enableBiometric by remember { mutableStateOf(showBiometricOption && biometricAvailable) }
    var error by remember { mutableStateOf<String?>(null) }
    // Holds the loader animation between full-PIN entry and the actual
    // step / save transition so the user gets visible feedback.
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val current = if (step == 1) first else second

    fun setDigit(d: String) {
        if (verifying) return
        if (current.length >= PIN_LENGTH) return
        error = null
        if (step == 1) {
            first += d
            if (first.length == PIN_LENGTH) {
                verifying = true
                scope.launch {
                    delay(520)
                    verifying = false
                    step = 2
                }
            }
        } else {
            second += d
            if (second.length == PIN_LENGTH) {
                verifying = true
                scope.launch {
                    delay(620)
                    verifying = false
                    if (first == second) {
                        onPinSet(first, enableBiometric && biometricAvailable)
                    } else {
                        error = "PIN-коды не совпадают"
                        first = ""
                        second = ""
                        step = 1
                    }
                }
            }
        }
    }

    fun backspace() {
        if (verifying) return
        error = null
        if (step == 1 && first.isNotEmpty()) first = first.dropLast(1)
        else if (step == 2) {
            if (second.isNotEmpty()) second = second.dropLast(1)
            else {
                step = 1
                first = first.dropLast(1)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            MbLogo(primaryColor = primaryColor, size = 70.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (
                        slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 4 }) +
                            fadeIn(animationSpec = tween(260))
                        ) togetherWith
                        (
                            slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it / 4 }) +
                                fadeOut(animationSpec = tween(220))
                            )
                },
                label = "pinSetupStep",
            ) { s ->
                Text(
                    if (s == 1) subtitle else "Повторите PIN-код для подтверждения",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(28.dp))

            AnimatedPinDotsRow(
                length = PIN_LENGTH,
                filled = current.length,
                primaryColor = primaryColor,
                error = error != null,
                verifying = verifying,
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

            Spacer(Modifier.height(20.dp))

            SetupKeypad(
                primaryColor = primaryColor,
                onDigit = ::setDigit,
                onBackspace = ::backspace,
            )

            if (showBiometricOption && biometricAvailable) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 320.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = primaryColor,
                        )
                        Text("Использовать биометрию", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = enableBiometric,
                        onCheckedChange = { enableBiometric = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = primaryColor,
                            checkedTrackColor = primaryColor.copy(alpha = 0.4f),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("Отмена") }
                }
                if (showSkip) {
                    TextButton(onClick = onSkip) {
                        Text("Пропустить", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupKeypad(
    primaryColor: Color,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "back"),
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
                            "" -> Spacer(Modifier.size(72.dp))
                            "back" -> Surface(
                                onClick = onBackspace,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(72.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Backspace,
                                        contentDescription = "Стереть",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            else -> Surface(
                                onClick = { onDigit(key) },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(72.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
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
}
