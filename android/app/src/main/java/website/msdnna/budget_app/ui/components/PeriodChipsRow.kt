package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * Single-row period selector used on Income / Expenses (and any other
 * screen that filters by a date range). Three chips — Месяц / Год / Период.
 *
 * Active state is derived from the (from, to) ISO pair the caller already
 * holds:
 *  - both null → nothing selected
 *  - exact calendar month bounds → "Месяц" with «Май 2026» label
 *  - exact calendar year bounds → "Год" with «2026» label
 *  - anything else → "Период" with «dd.mm – dd.mm» label
 *
 * Tapping a chip opens its picker; tapping the already-active chip clears
 * the filter (so users can quickly disable a period without hunting for a
 * reset button). The row is a LazyRow so labels like «01.05.2026 – 31.05.2026»
 * never need to truncate.
 */
@Composable
fun PeriodChipsRow(
    fromIso: String?,
    toIso: String?,
    primaryColor: Color,
    onChange: (from: String?, to: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (matchedMonth, matchedYear) = remember(fromIso, toIso) {
        val ym = monthFromIsoRange(fromIso, toIso)
        val yr = yearFromIsoRange(fromIso, toIso)
        ym to yr
    }
    val activeKind: PeriodKind = when {
        matchedMonth != null -> PeriodKind.MONTH
        matchedYear != null -> PeriodKind.YEAR
        fromIso != null && toIso != null -> PeriodKind.RANGE
        else -> PeriodKind.NONE
    }

    var pickerOpen by remember { mutableStateOf<PeriodKind?>(null) }
    val listState = rememberLazyListState()
    TrackInnerHorizontalScroll(listState)

    val now = remember { Calendar.getInstance() }
    val pickerYear = matchedMonth?.first ?: matchedYear ?: now.get(Calendar.YEAR)
    val pickerMonth = matchedMonth?.second ?: (now.get(Calendar.MONTH) + 1)

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                val label = matchedMonth?.let { (y, m) -> "${monthName(m)} $y" } ?: "Месяц"
                FilterChip(
                    selected = activeKind == PeriodKind.MONTH,
                    onClick = {
                        if (activeKind == PeriodKind.MONTH) {
                            onChange(null, null)
                        } else {
                            pickerOpen = PeriodKind.MONTH
                        }
                    },
                    label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = primaryColor,
                        selectedLabelColor = Color.White,
                    ),
                )
                TilePeriodPickerPopup(
                    open = pickerOpen == PeriodKind.MONTH,
                    type = TilePickerType.MONTH,
                    year = pickerYear,
                    month = pickerMonth,
                    primaryColor = primaryColor,
                    onSelect = { y, m ->
                        onChange(monthStart(y, m), monthEnd(y, m))
                        pickerOpen = null
                    },
                    onDismiss = { pickerOpen = null },
                )
            }
        }
        item {
            Box {
                val label = matchedYear?.toString() ?: "Год"
                FilterChip(
                    selected = activeKind == PeriodKind.YEAR,
                    onClick = {
                        if (activeKind == PeriodKind.YEAR) {
                            onChange(null, null)
                        } else {
                            pickerOpen = PeriodKind.YEAR
                        }
                    },
                    label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = primaryColor,
                        selectedLabelColor = Color.White,
                    ),
                )
                TilePeriodPickerPopup(
                    open = pickerOpen == PeriodKind.YEAR,
                    type = TilePickerType.YEAR,
                    year = pickerYear,
                    month = pickerMonth,
                    primaryColor = primaryColor,
                    onSelect = { y, _ ->
                        onChange("%04d-01-01".format(y), "%04d-12-31".format(y))
                        pickerOpen = null
                    },
                    onDismiss = { pickerOpen = null },
                )
            }
        }
        item {
            val rangeLabel = if (activeKind == PeriodKind.RANGE && fromIso != null && toIso != null) {
                "${shortIsoDate(fromIso)} – ${shortIsoDate(toIso)}"
            } else {
                "Период"
            }
            FilterChip(
                selected = activeKind == PeriodKind.RANGE,
                onClick = {
                    if (activeKind == PeriodKind.RANGE) {
                        onChange(null, null)
                    } else {
                        pickerOpen = PeriodKind.RANGE
                    }
                },
                label = { Text(rangeLabel, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = primaryColor,
                    selectedLabelColor = Color.White,
                ),
            )
        }
    }
    DateRangePickerDialog(
        open = pickerOpen == PeriodKind.RANGE,
        initialFromIso = fromIso,
        initialToIso = toIso,
        primaryColor = primaryColor,
        onConfirm = { f, t ->
            onChange(f, t)
            pickerOpen = null
        },
        onDismiss = { pickerOpen = null },
    )
}

private enum class PeriodKind { NONE, MONTH, YEAR, RANGE }

private fun monthStart(year: Int, month: Int): String = "%04d-%02d-01".format(year, month)
private fun monthEnd(year: Int, month: Int): String {
    val cal = Calendar.getInstance().apply { set(year, month - 1, 1) }
    val last = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    return "%04d-%02d-%02d".format(year, month, last)
}

private fun shortIsoDate(iso: String): String {
    // "YYYY-MM-DD" → "DD.MM.YYYY"
    val parts = iso.split('-')
    if (parts.size != 3) return iso
    return "${parts[2]}.${parts[1]}.${parts[0]}"
}

/** Returns (year, month) iff fromIso/toIso form exact calendar-month bounds. */
private fun monthFromIsoRange(from: String?, to: String?): Pair<Int, Int>? = runCatching {
    val fp = from?.split('-')?.takeIf { it.size == 3 } ?: return@runCatching null
    val tp = to?.split('-')?.takeIf { it.size == 3 } ?: return@runCatching null
    val y = fp[0].toInt()
    val m = fp[1].toInt()
    val sameYearMonth = fp[0] == tp[0] && fp[1] == tp[1]
    val startsAtFirst = fp[2] == "01"
    val cal = Calendar.getInstance().apply { set(y, m - 1, 1) }
    val endsAtLast = tp[2].toInt() == cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    if (sameYearMonth && startsAtFirst && endsAtLast) y to m else null
}.getOrNull()

/** Returns year iff fromIso/toIso form exact calendar-year bounds. */
private fun yearFromIsoRange(from: String?, to: String?): Int? = runCatching {
    val fp = from?.split('-')?.takeIf { it.size == 3 } ?: return@runCatching null
    val tp = to?.split('-')?.takeIf { it.size == 3 } ?: return@runCatching null
    val sameYear = fp[0] == tp[0]
    val janFirst = fp[1] == "01" && fp[2] == "01"
    val decLast = tp[1] == "12" && tp[2] == "31"
    if (sameYear && janFirst && decLast) fp[0].toInt() else null
}.getOrNull()
