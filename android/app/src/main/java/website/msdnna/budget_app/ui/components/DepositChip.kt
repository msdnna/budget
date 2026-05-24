package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Canonical deposit values mirrored from backend `models.DepositType`. */
const val DEPOSIT_BANK = "bank"
const val DEPOSIT_CASH = "cash"
const val DEPOSIT_DEFAULT = DEPOSIT_BANK

data class DepositMeta(val value: String, val label: String, val icon: ImageVector)

val DEPOSITS = listOf(
    DepositMeta(DEPOSIT_BANK, "Банковская карта", Icons.Filled.CreditCard),
    DepositMeta(DEPOSIT_CASH, "Наличные", Icons.Filled.Payments),
)

fun normalizeDeposit(value: String?): String =
    if (value == DEPOSIT_CASH) DEPOSIT_CASH else DEPOSIT_BANK

fun depositMeta(value: String?): DepositMeta = DEPOSITS.first { it.value == normalizeDeposit(value) }

/**
 * Compact deposit indicator. Read-only mode shows a tinted icon only; editable
 * mode wraps it in an IconButton + dropdown that emits [onChange] with the new
 * value. Sizing matches the surrounding text — caller passes [iconSize] to
 * keep it in scale with whatever it sits next to (avatar, category label, etc.)
 */
@Composable
fun DepositChip(
    value: String,
    onChange: ((String) -> Unit)? = null,
    iconSize: Dp = 16.dp,
    tint: Color = LocalContentColor.current,
) {
    val meta = depositMeta(value)
    if (onChange == null) {
        Icon(
            imageVector = meta.icon,
            contentDescription = "Счёт: ${meta.label}",
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(iconSize + 12.dp),
        ) {
            Icon(
                imageVector = meta.icon,
                contentDescription = "Счёт: ${meta.label}",
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DEPOSITS.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.label) },
                    leadingIcon = { Icon(d.icon, contentDescription = null) },
                    onClick = {
                        expanded = false
                        if (d.value != normalizeDeposit(value)) onChange(d.value)
                    },
                )
            }
        }
    }
}

/**
 * Compact "Банк / Нал" picker for forms — two FilterChip's in a Row. Older
 * Material3 versions don't ship SegmentedButton, and FilterChip gives the
 * same single-select feel with a friendlier baseline API.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DepositSegmented(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = normalizeDeposit(value)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DEPOSITS.forEach { meta ->
            FilterChip(
                selected = meta.value == current,
                onClick = { if (meta.value != current) onChange(meta.value) },
                label = { Text(meta.label) },
                leadingIcon = {
                    Icon(
                        imageVector = meta.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}
