package website.msdnna.budget_app.ui.screens

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.preferences.AppPreferences
import website.msdnna.budget_app.notifications.NotificationPrefs
import website.msdnna.budget_app.notifications.NotificationScheduler
import website.msdnna.budget_app.ui.components.MbLogo
import website.msdnna.budget_app.ui.theme.AppTheme
import website.msdnna.budget_app.ui.theme.AppThemes
import java.util.Calendar

private data class NavItem(val label: String, val icon: ImageVector, val route: String)

private val NAV_ITEMS = listOf(
    NavItem("Статистика", Icons.Default.BarChart,      "statistics"),
    NavItem("Доходы",     Icons.Default.TrendingUp,    "income"),
    NavItem("Расходы",    Icons.Default.TrendingDown,  "expenses"),
    NavItem("Прогноз",    Icons.Default.Lightbulb,     "forecast"),
    NavItem("Экспорт",    Icons.Default.FileDownload,  "export"),
)

private val PAGE_TITLES = mapOf(
    "statistics" to "Статистика",
    "income"     to "Доходы",
    "expenses"   to "Расходы",
    "forecast"   to "Прогноз",
    "export"     to "Экспорт",
)

@Composable
fun MainScreen(
    serverUrl: String,
    primaryColor: Color,
    isDark: Boolean = false,
    prefs: AppPreferences,
    activeTheme: AppTheme,
    displayName: String = "",
    avatarUrl: String? = null,
    onThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (Boolean) -> Unit = {},
    onResetServer: () -> Unit,
    onLogout: () -> Unit = {},
    onRequestNotifPermission: () -> Unit = {}
) {
    var currentRoute by remember { mutableStateOf("statistics") }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val notifPrefs by prefs.notifPrefs.collectAsStateWithLifecycle(
        initialValue = NotificationPrefs()
    )

    val now = Calendar.getInstance()
    val today = remember {
        val months = listOf("января","февраля","марта","апреля","мая","июня",
            "июля","августа","сентября","октября","ноября","декабря")
        "${now.get(Calendar.DAY_OF_MONTH)} ${months[now.get(Calendar.MONTH)]} ${now.get(Calendar.YEAR)}"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MbLogo(primaryColor = primaryColor, fontSize = 20.sp)
                        Text(
                            PAGE_TITLES[currentRoute] ?: "",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                actions = {
                    Text(
                        today,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp   // no tonal tinting → bar stays pure white
            ) {
                NAV_ITEMS.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick  = { currentRoute = item.route },
                        icon     = { Icon(item.icon, item.label) },
                        label    = { Text(item.label, fontSize = 10.sp) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor   = primaryColor,
                            selectedTextColor   = primaryColor,
                            // Color.Transparent renders black in this Compose version;
                            // Color(0x00FFFFFF) is truly transparent (alpha=0, white base)
                            indicatorColor      = Color(0x00FFFFFF),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (currentRoute) {
                "statistics" -> StatisticsScreen(serverUrl, primaryColor)
                "income"     -> IncomeScreen(serverUrl, primaryColor)
                "expenses"   -> ExpensesScreen(serverUrl, primaryColor)
                "forecast"   -> ForecastScreen(serverUrl, primaryColor)
                "export"     -> ExportScreen(serverUrl, primaryColor)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            primaryColor     = primaryColor,
            isDark           = isDark,
            activeTheme      = activeTheme,
            displayName      = displayName,
            notifPrefs       = notifPrefs,
            onThemeChange    = { theme ->
                onThemeChange(theme)
                scope.launch { prefs.setThemeKey(theme.key) }
            },
            onDarkModeChange = onDarkModeChange,
            onNotifPrefsChange = { np ->
                scope.launch {
                    prefs.setNotifPrefs(np)
                    NotificationScheduler.applyPrefs(context, np)
                }
                if (np.expensesEnabled || np.incomeEnabled) {
                    onRequestNotifPermission()
                }
            },
            onResetServer  = {
                showSettings = false
                scope.launch { prefs.clearServerUrl(); onResetServer() }
            },
            onLogout       = {
                showSettings = false
                onLogout()
            },
            onDismiss      = { showSettings = false }
        )
    }
}

@Composable
fun SettingsDialog(
    primaryColor: Color,
    isDark: Boolean = false,
    activeTheme: AppTheme,
    displayName: String = "",
    notifPrefs: NotificationPrefs = NotificationPrefs(),
    onThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (Boolean) -> Unit = {},
    onNotifPrefsChange: (NotificationPrefs) -> Unit = {},
    onResetServer: () -> Unit,
    onLogout: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Local mutable copy so changes reflect immediately in UI
    var np by remember(notifPrefs) { mutableStateOf(notifPrefs) }

    fun save(updated: NotificationPrefs) {
        np = updated
        onNotifPrefsChange(updated)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Logged-in user info
                if (displayName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                                .background(primaryColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }
                                    .take(2).joinToString(""),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text(displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Вы авторизованы", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Divider()
                }

                // Theme picker
                Text("Цвет темы", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppThemes.forEach { theme ->
                        Box(
                            modifier = Modifier.size(32.dp).clip(CircleShape)
                                .background(theme.primary).clickable { onThemeChange(theme) }
                        ) {
                            if (theme.key == activeTheme.key) {
                                Icon(Icons.Default.Check, null, tint = Color.White,
                                    modifier = Modifier.size(18.dp).align(Alignment.Center))
                            }
                        }
                    }
                }

                // Dark mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Тёмная тема", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = isDark,
                        onCheckedChange = onDarkModeChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor  = primaryColor,
                            checkedTrackColor  = primaryColor.copy(alpha = 0.4f)
                        )
                    )
                }

                Divider()

                // Notifications section
                Text("Уведомления", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Expenses notification
                NotifRow(
                    label       = "Напоминание о расходах",
                    sublabel    = "Ежедневно",
                    enabled     = np.expensesEnabled,
                    hour        = np.expensesHour,
                    minute      = np.expensesMinute,
                    dayOfMonth  = null,
                    primaryColor = primaryColor,
                    onToggle    = { save(np.copy(expensesEnabled = it)) },
                    onTimePick  = { h, m -> save(np.copy(expensesHour = h, expensesMinute = m)) },
                    onDayPick   = null,
                    onShowTimePicker = { h, m, onPick ->
                        TimePickerDialog(context, { _, hour, minute -> onPick(hour, minute) }, h, m, true).show()
                    }
                )

                // Income notification
                NotifRow(
                    label       = "Напоминание о доходах",
                    sublabel    = "Ежемесячно",
                    enabled     = np.incomeEnabled,
                    hour        = np.incomeHour,
                    minute      = np.incomeMinute,
                    dayOfMonth  = np.incomeDay,
                    primaryColor = primaryColor,
                    onToggle    = { save(np.copy(incomeEnabled = it)) },
                    onTimePick  = { h, m -> save(np.copy(incomeHour = h, incomeMinute = m)) },
                    onDayPick   = { d -> save(np.copy(incomeDay = d)) },
                    onShowTimePicker = { h, m, onPick ->
                        TimePickerDialog(context, { _, hour, minute -> onPick(hour, minute) }, h, m, true).show()
                    }
                )

                Divider()

                // Logout
                TextButton(
                    onClick = onLogout,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.ExitToApp, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Выйти из аккаунта")
                }

                // Change server
                TextButton(
                    onClick = onResetServer,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                ) {
                    Icon(Icons.Default.WifiOff, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Изменить сервер")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun NotifRow(
    label: String,
    sublabel: String,
    enabled: Boolean,
    hour: Int,
    minute: Int,
    dayOfMonth: Int?,
    primaryColor: Color,
    onToggle: (Boolean) -> Unit,
    onTimePick: (Int, Int) -> Unit,
    onDayPick: ((Int) -> Unit)?,
    onShowTimePicker: (Int, Int, (Int, Int) -> Unit) -> Unit
) {
    var showDayMenu by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.4f))
            )
        }

        if (enabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(sublabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Day picker (only for monthly)
                if (dayOfMonth != null && onDayPick != null) {
                    Box {
                        TimeChip(
                            text = "${dayOfMonth} числа",
                            primaryColor = primaryColor,
                            onClick = { showDayMenu = true }
                        )
                        DropdownMenu(
                            expanded = showDayMenu,
                            onDismissRequest = { showDayMenu = false }
                        ) {
                            (1..31).forEach { day ->
                                DropdownMenuItem(
                                    text = { Text("$day числа") },
                                    onClick = {
                                        onDayPick(day)
                                        showDayMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Time chip
                TimeChip(
                    text = "%02d:%02d".format(hour, minute),
                    primaryColor = primaryColor,
                    onClick = { onShowTimePicker(hour, minute, onTimePick) }
                )
            }
        }
    }
}

@Composable
private fun TimeChip(text: String, primaryColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(primaryColor.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = primaryColor, fontWeight = FontWeight.Medium)
    }
}
