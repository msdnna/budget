package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import website.msdnna.budget_app.notifications.NotificationFrequency
import website.msdnna.budget_app.notifications.NotificationPrefs

@Composable
fun NotificationsScreen(
    primaryColor: Color,
    notifPrefs: NotificationPrefs,
    onNotifPrefsChange: (NotificationPrefs) -> Unit,
    onClose: () -> Unit,
) {
    var np by remember(notifPrefs) { mutableStateOf(notifPrefs) }
    var timePickerTarget by remember { mutableStateOf<TimePickerTarget?>(null) }

    fun save(updated: NotificationPrefs) {
        np = updated
        onNotifPrefsChange(updated)
    }

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
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            NotifRow(
                label = "Напоминание о расходах",
                enabled = np.expensesEnabled,
                frequency = np.expensesFrequency,
                hour = np.expensesHour,
                minute = np.expensesMinute,
                dayOfWeek = np.expensesDayOfWeek,
                dayOfMonth = np.expensesDayOfMonth,
                primaryColor = primaryColor,
                onToggle = { save(np.copy(expensesEnabled = it)) },
                onFrequency = { save(np.copy(expensesFrequency = it)) },
                onDayOfWeek = { d -> save(np.copy(expensesDayOfWeek = d)) },
                onDayOfMonth = { d -> save(np.copy(expensesDayOfMonth = d)) },
                onShowTimePicker = {
                    timePickerTarget = TimePickerTarget(np.expensesHour, np.expensesMinute) { h, m ->
                        save(np.copy(expensesHour = h, expensesMinute = m))
                    }
                }
            )

            HorizontalDivider()

            NotifRow(
                label = "Напоминание о доходах",
                enabled = np.incomeEnabled,
                frequency = np.incomeFrequency,
                hour = np.incomeHour,
                minute = np.incomeMinute,
                dayOfWeek = np.incomeDayOfWeek,
                dayOfMonth = np.incomeDayOfMonth,
                primaryColor = primaryColor,
                onToggle = { save(np.copy(incomeEnabled = it)) },
                onFrequency = { save(np.copy(incomeFrequency = it)) },
                onDayOfWeek = { d -> save(np.copy(incomeDayOfWeek = d)) },
                onDayOfMonth = { d -> save(np.copy(incomeDayOfMonth = d)) },
                onShowTimePicker = {
                    timePickerTarget = TimePickerTarget(np.incomeHour, np.incomeMinute) { h, m ->
                        save(np.copy(incomeHour = h, incomeMinute = m))
                    }
                }
            )
        }
    }

    timePickerTarget?.let { target ->
        ThemedTimePickerDialog(
            initialHour = target.hour,
            initialMinute = target.minute,
            primaryColor = primaryColor,
            onConfirm = { h, m ->
                target.onPick(h, m)
                timePickerTarget = null
            },
            onDismiss = { timePickerTarget = null }
        )
    }
}

private data class TimePickerTarget(
    val hour: Int,
    val minute: Int,
    val onPick: (Int, Int) -> Unit,
)

private fun frequencyLabel(f: NotificationFrequency) = when (f) {
    NotificationFrequency.DAILY -> "Ежедневно"
    NotificationFrequency.WEEKLY -> "Еженедельно"
    NotificationFrequency.MONTHLY -> "Ежемесячно"
    NotificationFrequency.QUARTERLY -> "Ежеквартально"
}

private val DAYS_OF_WEEK_RU = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

// Calendar.MONDAY=2, TUESDAY=3, …, SUNDAY=1. Map index 0..6 → Calendar value.
private val DAY_OF_WEEK_VALUES = listOf(2, 3, 4, 5, 6, 7, 1)

private fun dayOfWeekLabel(value: Int): String {
    val idx = DAY_OF_WEEK_VALUES.indexOf(value).takeIf { it >= 0 } ?: 0
    return DAYS_OF_WEEK_RU[idx]
}

@Composable
private fun NotifRow(
    label: String,
    enabled: Boolean,
    frequency: NotificationFrequency,
    hour: Int,
    minute: Int,
    dayOfWeek: Int,
    dayOfMonth: Int,
    primaryColor: Color,
    onToggle: (Boolean) -> Unit,
    onFrequency: (NotificationFrequency) -> Unit,
    onDayOfWeek: (Int) -> Unit,
    onDayOfMonth: (Int) -> Unit,
    onShowTimePicker: () -> Unit
) {
    var showFrequencyMenu by remember { mutableStateOf(false) }
    var showDayOfMonthDialog by remember { mutableStateOf(false) }
    var showDayOfWeekDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = primaryColor,
                    checkedTrackColor = primaryColor.copy(alpha = 0.4f),
                )
            )
        }

        if (enabled) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 4.dp).fillMaxWidth()
            ) {
                Box {
                    Chip(
                        text = frequencyLabel(frequency),
                        primaryColor = primaryColor,
                        onClick = { showFrequencyMenu = true }
                    )
                    DropdownMenu(
                        expanded = showFrequencyMenu,
                        onDismissRequest = { showFrequencyMenu = false }
                    ) {
                        NotificationFrequency.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(frequencyLabel(f)) },
                                onClick = {
                                    onFrequency(f)
                                    showFrequencyMenu = false
                                }
                            )
                        }
                    }
                }

                when (frequency) {
                    NotificationFrequency.WEEKLY -> {
                        Chip(
                            text = dayOfWeekLabel(dayOfWeek),
                            primaryColor = primaryColor,
                            onClick = { showDayOfWeekDialog = true }
                        )
                    }
                    NotificationFrequency.MONTHLY,
                    NotificationFrequency.QUARTERLY -> {
                        Chip(
                            text = "$dayOfMonth числа",
                            primaryColor = primaryColor,
                            onClick = { showDayOfMonthDialog = true }
                        )
                    }
                    NotificationFrequency.DAILY -> Unit
                }

                Chip(
                    text = "%02d:%02d".format(hour, minute),
                    primaryColor = primaryColor,
                    onClick = onShowTimePicker
                )
            }
        }
    }

    if (showDayOfMonthDialog) {
        DayOfMonthPickerDialog(
            current = dayOfMonth,
            primaryColor = primaryColor,
            onPick = {
                onDayOfMonth(it)
                showDayOfMonthDialog = false
            },
            onDismiss = { showDayOfMonthDialog = false }
        )
    }

    if (showDayOfWeekDialog) {
        DayOfWeekPickerDialog(
            current = dayOfWeek,
            primaryColor = primaryColor,
            onPick = {
                onDayOfWeek(it)
                showDayOfWeekDialog = false
            },
            onDismiss = { showDayOfWeekDialog = false }
        )
    }
}

@Composable
private fun Chip(text: String, primaryColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(primaryColor.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, style = MaterialTheme.typography.bodySmall,
            color = primaryColor, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DayOfMonthPickerDialog(
    current: Int,
    primaryColor: Color,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите число") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items((1..31).toList()) { day ->
                        DayCell(
                            text = day.toString(),
                            selected = day == current,
                            primaryColor = primaryColor,
                            onClick = { onPick(day) }
                        )
                    }
                }
                Text(
                    "В месяцах с меньшим числом дней уведомление придёт в последний день.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun DayOfWeekPickerDialog(
    current: Int,
    primaryColor: Color,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите день недели") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(DAY_OF_WEEK_VALUES) { value ->
                    DayCell(
                        text = dayOfWeekLabel(value),
                        selected = value == current,
                        primaryColor = primaryColor,
                        onClick = { onPick(value) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemedTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    primaryColor: Color,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    val onPrimary = Color.White
    val containerSelected = primaryColor
    val containerUnselected = primaryColor.copy(alpha = 0.12f)

    val timePickerColors = TimePickerDefaults.colors(
        clockDialColor = primaryColor.copy(alpha = 0.10f),
        clockDialSelectedContentColor = onPrimary,
        clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
        selectorColor = primaryColor,
        containerColor = MaterialTheme.colorScheme.surface,
        periodSelectorBorderColor = primaryColor,
        periodSelectorSelectedContainerColor = containerSelected,
        periodSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surface,
        periodSelectorSelectedContentColor = onPrimary,
        periodSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
        timeSelectorSelectedContainerColor = containerSelected,
        timeSelectorUnselectedContainerColor = containerUnselected,
        timeSelectorSelectedContentColor = onPrimary,
        timeSelectorUnselectedContentColor = primaryColor,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время уведомления") },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = state, colors = timePickerColors)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(state.hour, state.minute) },
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor),
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor),
            ) { Text("Отмена") }
        }
    )
}

@Composable
private fun DayCell(text: String, selected: Boolean, primaryColor: Color, onClick: () -> Unit) {
    val bg = if (selected) primaryColor else primaryColor.copy(alpha = 0.10f)
    val fg = if (selected) Color.White else primaryColor
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = fg, fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
