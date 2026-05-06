package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Read-only text field that opens a themed Material3 calendar picker on tap.
 * `value` is an ISO date "yyyy-MM-dd"; the field displays "dd.MM.yyyy".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    primaryColor: Color,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    val display = remember(value) { displayDate(value) }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = "Выбрать дату",
                    tint = primaryColor,
                    modifier = Modifier.size(20.dp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                disabledBorderColor       = MaterialTheme.colorScheme.outline,
                disabledLabelColor        = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTextColor         = MaterialTheme.colorScheme.onSurface,
                disabledTrailingIconColor = primaryColor,
                disabledContainerColor    = Color.Transparent,
            ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { showPicker = true }
        )
    }

    if (showPicker) {
        ThemedDatePickerDialog(
            initialIsoDate = value,
            primaryColor = primaryColor,
            onConfirm = { iso ->
                onChange(iso)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemedDatePickerDialog(
    initialIsoDate: String,
    primaryColor: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMillis = remember(initialIsoDate) { isoToUtcMillis(initialIsoDate) }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        initialDisplayedMonthMillis = initialMillis,
    )

    val onPrimary = Color.White
    val surface = MaterialTheme.colorScheme.surface
    val pickerColors = DatePickerDefaults.colors(
        containerColor              = surface,
        titleContentColor           = MaterialTheme.colorScheme.onSurfaceVariant,
        headlineContentColor        = MaterialTheme.colorScheme.onSurface,
        weekdayContentColor         = MaterialTheme.colorScheme.onSurfaceVariant,
        subheadContentColor         = MaterialTheme.colorScheme.onSurface,
        navigationContentColor      = MaterialTheme.colorScheme.onSurface,
        yearContentColor            = MaterialTheme.colorScheme.onSurface,
        currentYearContentColor     = primaryColor,
        selectedYearContentColor    = onPrimary,
        selectedYearContainerColor  = primaryColor,
        dayContentColor             = MaterialTheme.colorScheme.onSurface,
        selectedDayContentColor     = onPrimary,
        selectedDayContainerColor   = primaryColor,
        todayContentColor           = primaryColor,
        todayDateBorderColor        = primaryColor,
        dividerColor                = MaterialTheme.colorScheme.outlineVariant,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ms = state.selectedDateMillis
                    if (ms != null) onConfirm(utcMillisToIso(ms)) else onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor),
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor),
            ) { Text("Отмена") }
        },
        tonalElevation = 0.dp,
        colors = pickerColors,
    ) {
        DatePicker(state = state, showModeToggle = false, colors = pickerColors)
    }
}

private fun displayDate(iso: String): String {
    if (iso.length < 10) return ""
    val parts = iso.take(10).split("-")
    if (parts.size != 3) return ""
    return "${parts[2]}.${parts[1]}.${parts[0]}"
}

private fun isoToUtcMillis(iso: String): Long {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return try {
        if (iso.length >= 10) fmt.parse(iso.take(10))?.time ?: todayUtcMillis()
        else todayUtcMillis()
    } catch (_: Exception) { todayUtcMillis() }
}

private fun utcMillisToIso(ms: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return fmt.format(java.util.Date(ms))
}

private fun todayUtcMillis(): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
