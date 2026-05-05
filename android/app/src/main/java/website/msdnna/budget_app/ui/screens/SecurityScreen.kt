package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.preferences.AppPreferences
import website.msdnna.budget_app.data.security.AppLock
import website.msdnna.budget_app.data.security.BiometricHelper
import website.msdnna.budget_app.data.security.PinSecurity

@Composable
fun SecurityScreen(
    primaryColor: Color,
    prefs: AppPreferences,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pinHash         by prefs.pinHash.collectAsStateWithLifecycle(initialValue = null)
    val pinSalt         by prefs.pinSalt.collectAsStateWithLifecycle(initialValue = null)
    val biometricEnabled by prefs.biometricEnabled.collectAsStateWithLifecycle(initialValue = false)
    val lockTimeoutSec   by prefs.lockTimeoutSec.collectAsStateWithLifecycle(initialValue = 60)
    val hasPin = !pinHash.isNullOrBlank() && !pinSalt.isNullOrBlank()
    val biometricAvailable = remember { BiometricHelper.isAvailable(context) }

    var showPinSetup by remember { mutableStateOf(false) }
    var showPinDisableVerify by remember { mutableStateOf(false) }
    var showPinChangeVerify by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Защита приложения") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // PIN toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        Text("PIN-код", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (hasPin) "Включён" else "Не задан",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = hasPin,
                    onCheckedChange = { enable ->
                        if (enable) showPinSetup = true
                        else showPinDisableVerify = true
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = primaryColor,
                        checkedTrackColor = primaryColor.copy(alpha = 0.4f),
                    ),
                )
            }

            if (hasPin) {
                HorizontalDivider()

                // Change PIN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPinChangeVerify = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Изменить PIN", style = MaterialTheme.typography.bodyLarge)
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (biometricAvailable) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column {
                                Text("Биометрия", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Быстрый вход без ввода PIN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { enable ->
                                scope.launch { prefs.setBiometricEnabled(enable) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = primaryColor,
                                checkedTrackColor = primaryColor.copy(alpha = 0.4f),
                            ),
                        )
                    }
                }

                HorizontalDivider()

                // Lock timeout
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Блокировка после сворачивания", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Через какое время приложение блокируется в фоне",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val timeoutOptions = listOf(
                        0    to "сразу",
                        30   to "30 сек",
                        60   to "1 мин",
                        300  to "5 мин",
                        1800 to "30 мин",
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        timeoutOptions.forEach { (secs, label) ->
                            FilterChip(
                                selected = lockTimeoutSec == secs,
                                onClick  = { scope.launch { prefs.setLockTimeoutSec(secs) } },
                                label    = { Text(label) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryColor,
                                    selectedLabelColor     = Color.White,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPinSetup) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showPinSetup = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                PinSetupScreen(
                    primaryColor = primaryColor,
                    showSkip = false,
                    onCancel = { showPinSetup = false },
                    onPinSet = { pin, enableBio ->
                        val stored = PinSecurity.hash(pin)
                        scope.launch {
                            prefs.setPin(stored.hashBase64, stored.saltBase64)
                            prefs.setBiometricEnabled(enableBio)
                            prefs.setPinSetupPrompted(true)
                            AppLock.unlock()
                        }
                        showPinSetup = false
                    },
                )
            }
        }
    }

    if (showPinDisableVerify && pinHash != null && pinSalt != null) {
        PinVerifyDialog(
            primaryColor = primaryColor,
            title = "Отключение PIN-кода",
            pinHash = pinHash!!,
            pinSalt = pinSalt!!,
            onVerified = {
                showPinDisableVerify = false
                scope.launch {
                    prefs.clearPinAndBiometric()
                    AppLock.unlock()
                }
            },
            onDismiss = { showPinDisableVerify = false },
        )
    }

    if (showPinChangeVerify && pinHash != null && pinSalt != null) {
        PinVerifyDialog(
            primaryColor = primaryColor,
            title = "Изменение PIN-кода",
            pinHash = pinHash!!,
            pinSalt = pinSalt!!,
            onVerified = {
                showPinChangeVerify = false
                showPinSetup = true
            },
            onDismiss = { showPinChangeVerify = false },
        )
    }
}
