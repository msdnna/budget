package website.msdnna.budget_app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tile-grid month / year picker matching the web `TilePeriodPicker.vue`.
 * The popup shows a 4×3 grid:
 *   - type=Month → 12 months of the current cursor year
 *   - type=Year  → decade view (10 years + leading/trailing muted year)
 *
 * Header arrows step the cursor: ±1 year (Month) or ±10 years (Year).
 *
 * Two flavours:
 *   - [TilePeriodPicker]: self-contained — own trigger field + popup.
 *   - [TilePeriodPickerPopup]: controlled popup only; useful when the trigger
 *     is custom (e.g. a chip on the Statistics screen).
 */
enum class TilePickerType { MONTH, YEAR }

@Composable
fun TilePeriodPicker(
    type: TilePickerType,
    year: Int,
    month: Int, // 1..12; ignored when type == YEAR
    primaryColor: Color,
    onSelect: (year: Int, month: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    val label = if (type == TilePickerType.MONTH)
        "${MONTHS_FULL[(month - 1).coerceIn(0, 11)]} $year"
    else
        year.toString()

    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = "Выбрать период",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        TilePeriodPickerPopup(
            open = open,
            type = type,
            year = year,
            month = month,
            primaryColor = primaryColor,
            onSelect = { y, m ->
                onSelect(y, m)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

/**
 * Controlled popup. Render anywhere; toggle via [open]. The popup positions
 * itself below its anchor — wrap the trigger in a [Box] and place this inside
 * for the popup to anchor correctly.
 */
@Composable
fun TilePeriodPickerPopup(
    open: Boolean,
    type: TilePickerType,
    year: Int,
    month: Int,
    primaryColor: Color,
    onSelect: (year: Int, month: Int) -> Unit,
    onDismiss: () -> Unit,
    /** Vertical gap between the trigger and the popup. Tuned by eye to look
     *  like a small breathing space without the panel floating away. */
    anchorGap: Dp = 8.dp,
    /** Override the full anchor offset; defaults to "trigger height + gap"
     *  computed from a typical 38dp trigger Surface. */
    anchorOffset: IntOffset? = null,
) {
    val density = LocalDensity.current
    val resolvedOffset = anchorOffset ?: with(density) {
        IntOffset(0, (38.dp + anchorGap).roundToPx())
    }

    var cursorYear by remember(open, year) { mutableStateOf(year) }

    val now = Calendar.getInstance()
    val todayYear = now.get(Calendar.YEAR)
    val todayMonth = now.get(Calendar.MONTH) + 1

    // Drive enter/exit via MutableTransitionState so we can keep the Popup
    // mounted during the exit animation (Popup unmounts the moment `open`
    // flips false otherwise).
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = open

    if (transition.currentState || transition.targetState) {
        Popup(
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
            offset = resolvedOffset,
        ) {
            AnimatedVisibility(
                visibleState = transition,
                enter = fadeIn(tween(150)) +
                    slideInVertically(tween(180)) { -it / 6 } +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(180)),
                exit = fadeOut(tween(110)) +
                    scaleOut(targetScale = 0.96f, animationSpec = tween(130)),
            ) {
                TilePanel(
                    type = type,
                    cursorYear = cursorYear,
                    selectedYear = year,
                    selectedMonth = month,
                    todayYear = todayYear,
                    todayMonth = todayMonth,
                    primaryColor = primaryColor,
                    onCursorChange = { cursorYear = it },
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun TilePanel(
    type: TilePickerType,
    cursorYear: Int,
    selectedYear: Int,
    selectedMonth: Int,
    todayYear: Int,
    todayMonth: Int,
    primaryColor: Color,
    onCursorChange: (Int) -> Unit,
    onSelect: (year: Int, month: Int) -> Unit,
) {
    val header = if (type == TilePickerType.MONTH) {
        cursorYear.toString()
    } else {
        val start = (cursorYear / 10) * 10
        "$start–${start + 9}"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.width(260.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                IconButton(
                    onClick = {
                        onCursorChange(if (type == TilePickerType.MONTH) cursorYear - 1 else cursorYear - 10)
                    },
                    modifier = Modifier.size(28.dp),
                ) { Text("‹", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Text(
                    header,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = {
                        onCursorChange(if (type == TilePickerType.MONTH) cursorYear + 1 else cursorYear + 10)
                    },
                    modifier = Modifier.size(28.dp),
                ) { Text("›", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
            HorizontalDivider()

            val cells: List<TileCell> = if (type == TilePickerType.MONTH) {
                (0 until 12).map { i ->
                    TileCell(
                        label = MONTHS_SHORT[i],
                        year = cursorYear,
                        month = i + 1,
                        active = cursorYear == selectedYear && (i + 1) == selectedMonth,
                        today = cursorYear == todayYear && (i + 1) == todayMonth,
                        muted = false,
                    )
                }
            } else {
                val start = (cursorYear / 10) * 10 - 1
                (0 until 12).map { i ->
                    val y = start + i
                    TileCell(
                        label = y.toString(),
                        year = y,
                        month = 1,
                        active = y == selectedYear,
                        today = y == todayYear,
                        muted = i == 0 || i == 11,
                    )
                }
            }

            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 4) {
                        val cell = cells[row * 4 + col]
                        TileItem(
                            cell = cell,
                            primaryColor = primaryColor,
                            onClick = { onSelect(cell.year, cell.month) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private data class TileCell(
    val label: String,
    val year: Int,
    val month: Int,
    val active: Boolean,
    val today: Boolean,
    val muted: Boolean,
)

@Composable
private fun TileItem(
    cell: TileCell,
    primaryColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (cell.active) primaryColor else Color.Transparent
    val fg = when {
        cell.active -> Color.White
        cell.today -> primaryColor
        cell.muted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderMod = if (cell.today && !cell.active) {
        Modifier.border(1.dp, primaryColor, RoundedCornerShape(6.dp))
    } else Modifier

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(borderMod)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (cell.active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ─── Range picker: OutlinedTextField-style trigger + M3 DateRangePicker ──────

/**
 * Read-only field styled like [CategoryFilterField] for visual consistency on
 * Income / Expenses screens. Tapping opens a themed Material 3 [DateRangePicker]
 * dialog. Empty state shows the placeholder; with a value the trailing icon
 * becomes a clear button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerField(
    fromIso: String?,
    toIso: String?,
    primaryColor: Color,
    onChange: (from: String?, to: String?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Период",
) {
    var open by remember { mutableStateOf(false) }
    val display = remember(fromIso, toIso) {
        if (fromIso != null && toIso != null) "${displayDate(fromIso)} — ${displayDate(toIso)}"
        else "Все даты"
    }
    val isSet = fromIso != null && toIso != null

    Box(modifier) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = {
                if (isSet) {
                    IconButton(onClick = { onChange(null, null) }, modifier = Modifier.size(24.dp)) {
                        Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = true },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedBorderColor = primaryColor,
            ),
        )
    }

    DateRangePickerDialog(
        open = open,
        initialFromIso = fromIso,
        initialToIso = toIso,
        primaryColor = primaryColor,
        onConfirm = { f, t ->
            onChange(f, t)
            open = false
        },
        onDismiss = { open = false },
    )
}

/**
 * Controlled M3 date-range dialog. Pass [open] = true to show; call [onDismiss]
 * to close. Used both by [DateRangePickerField] and directly by callers that
 * supply their own trigger (e.g. the Statistics chip).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(
    open: Boolean,
    initialFromIso: String?,
    initialToIso: String?,
    primaryColor: Color,
    onConfirm: (from: String?, to: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFromIso?.let(::isoToUtcMillis),
        initialSelectedEndDateMillis = initialToIso?.let(::isoToUtcMillis),
    )

    val onPrimary = Color.White
    val pickerColors = DatePickerDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        headlineContentColor = MaterialTheme.colorScheme.onSurface,
        weekdayContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        subheadContentColor = MaterialTheme.colorScheme.onSurface,
        navigationContentColor = MaterialTheme.colorScheme.onSurface,
        yearContentColor = MaterialTheme.colorScheme.onSurface,
        currentYearContentColor = primaryColor,
        selectedYearContentColor = onPrimary,
        selectedYearContainerColor = primaryColor,
        dayContentColor = MaterialTheme.colorScheme.onSurface,
        selectedDayContentColor = onPrimary,
        selectedDayContainerColor = primaryColor,
        todayContentColor = primaryColor,
        todayDateBorderColor = primaryColor,
        dayInSelectionRangeContainerColor = primaryColor.copy(alpha = 0.20f),
        dayInSelectionRangeContentColor = MaterialTheme.colorScheme.onSurface,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val f = state.selectedStartDateMillis
                    val t = state.selectedEndDateMillis
                    if (f != null && t != null) onConfirm(utcMillisToIso(f), utcMillisToIso(t)) else onDismiss()
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
        DateRangePicker(
            state = state,
            showModeToggle = false,
            colors = pickerColors,
            title = {
                Text(
                    "Выберите период",
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headline = {
                val f = state.selectedStartDateMillis
                val t = state.selectedEndDateMillis
                val text = when {
                    f != null && t != null -> "${displayDate(utcMillisToIso(f))} — ${displayDate(utcMillisToIso(t))}"
                    f != null -> displayDate(utcMillisToIso(f))
                    else -> "С — ПО"
                }
                Text(
                    text,
                    modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
        )
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
    return fmt.parse(iso.take(10))?.time ?: System.currentTimeMillis()
}

private fun utcMillisToIso(ms: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return fmt.format(Date(ms))
}
