package website.msdnna.budget_app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.SplitPart
import website.msdnna.budget_app.data.model.Transaction
import website.msdnna.budget_app.ui.components.DEPOSIT_BANK
import website.msdnna.budget_app.ui.components.DEPOSIT_CASH
import website.msdnna.budget_app.ui.components.DepositSegmented
import website.msdnna.budget_app.ui.components.normalizeDeposit

private data class SplitRow(val amount: String, val deposit: String)

/**
 * Modal bottom sheet for splitting an income transaction into N parts across
 * deposits. Mirrors the web SplitIncomeModal: NumberStepper for the part count
 * + a card per part with amount + DepositSegmented. The last row auto-balances
 * on edits to any preceding row so the sum stays equal to the parent. Save is
 * disabled unless the sum matches exactly (0.01 ₽ tolerance) and every part is
 * positive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitIncomeSheet(
    transaction: Transaction,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onConfirm: suspend (List<SplitPart>) -> Throwable?,
) {
    val total = transaction.amount
    var count by remember { mutableStateOf(2) }
    val rows = remember { mutableStateListOf<SplitRow>() }
    // Indices the user manually edited — they're locked from auto-balance.
    // The untouched rows split the remaining amount equally among themselves.
    val touched = remember { mutableStateListOf<Int>() }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Rebuild parts whenever the count changes (or the sheet first opens).
    LaunchedEffect(count) {
        val each = roundHalfUp(total / count)
        val newRows = (0 until count).map { i ->
            val amt = if (i == count - 1) roundHalfUp(total - each * (count - 1)) else each
            SplitRow(
                amount = formatAmountInput(amt),
                deposit = if (i == 0) DEPOSIT_BANK else DEPOSIT_CASH,
            )
        }
        rows.clear()
        rows.addAll(newRows)
        touched.clear()
    }

    val sum = rows.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    val delta = roundHalfUp(sum - total)
    val sumMatches = abs(delta) < 0.01
    val hasInvalid = rows.any { (it.amount.toDoubleOrNull() ?: 0.0) <= 0.0 }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Разделить доход",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${formatDate(transaction.date)} · ${transaction.category}" +
                    (transaction.source?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                    " — ${formatMoney(total)} ₽",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Делить на:", modifier = Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { if (count > 2) count -= 1 },
                    enabled = count > 2,
                ) { Icon(Icons.Default.Remove, contentDescription = "Уменьшить") }
                Text(
                    "$count",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(32.dp),
                )
                FilledTonalIconButton(
                    onClick = { if (count < 10) count += 1 },
                    enabled = count < 10,
                ) { Icon(Icons.Default.Add, contentDescription = "Увеличить") }
            }

            HorizontalDivider()

            rows.forEachIndexed { idx, row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Часть #${idx + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = row.amount,
                        onValueChange = { v ->
                            val cleaned = v.filter { it.isDigit() || it == '.' || it == ',' }
                                .replace(',', '.')
                            rows[idx] = row.copy(amount = cleaned)
                            if (idx !in touched) touched.add(idx)
                            // Redistribute the remainder over still-untouched
                            // rows. If everything is touched the user fixes
                            // any drift manually (sum-mismatch warns).
                            val touchedSum = rows.mapIndexedNotNull { i, r ->
                                if (i in touched) r.amount.toDoubleOrNull() ?: 0.0 else null
                            }.sum()
                            val untouchedIdx = rows.indices.filter { it !in touched }
                            if (untouchedIdx.isNotEmpty()) {
                                val remainder = roundHalfUp(total - touchedSum)
                                val each = roundHalfUp(remainder / untouchedIdx.size)
                                var allocated = 0.0
                                untouchedIdx.forEachIndexed { k, i ->
                                    val amt = if (k == untouchedIdx.lastIndex) {
                                        roundHalfUp(remainder - allocated)
                                    } else {
                                        each
                                    }
                                    allocated += amt
                                    rows[i] = rows[i].copy(amount = formatAmountInput(amt))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        suffix = { Text("₽") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    DepositSegmented(
                        value = row.deposit,
                        onChange = { v -> rows[idx] = row.copy(deposit = v) },
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Сумма частей:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatMoney(sum)} ₽",
                        fontWeight = FontWeight.SemiBold,
                        color = if (sumMatches) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    )
                    if (!sumMatches) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "(${if (delta > 0) "+" else ""}${formatMoney(delta)} ₽)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { if (!saving) onDismiss() },
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                ) { Text("Отмена") }
                Button(
                    onClick = {
                        if (!sumMatches || hasInvalid || saving) return@Button
                        saving = true
                        error = null
                        scope.launch {
                            val parts = rows.map {
                                SplitPart(
                                    amount = it.amount.toDoubleOrNull() ?: 0.0,
                                    deposit = normalizeDeposit(it.deposit),
                                )
                            }
                            val err = onConfirm(parts)
                            saving = false
                            if (err != null) {
                                error = err.message ?: "Не удалось разделить"
                            } else {
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = sumMatches && !hasInvalid && !saving,
                ) { Text(if (saving) "Делю…" else "Разделить") }
            }
        }
    }
}

private fun roundHalfUp(v: Double): Double = Math.round(v * 100.0) / 100.0

private fun formatAmountInput(v: Double): String {
    val rounded = roundHalfUp(v)
    return if (rounded == rounded.toInt().toDouble()) {
        rounded.toInt().toString()
    } else {
        "%.2f".format(rounded)
    }
}

// Локальные форматтеры — топ-левел `formatMoney`/`formatDate` в других
// screens-файлах приватные; дублирую минимальную реализацию вместо вытаскивания
// в shared util (одно место использования).
private fun formatMoney(v: Double): String = "%,.0f".format(v).replace(',', ' ')

private fun formatDate(iso: String): String {
    if (iso.length < 10) return iso
    val y = iso.substring(0, 4)
    val m = iso.substring(5, 7)
    val d = iso.substring(8, 10)
    return "$d.$m.$y"
}
