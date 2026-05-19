package website.msdnna.budget_app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.data.repository.CategoryRepository
import website.msdnna.budget_app.data.repository.LimitsProgressRepository
import website.msdnna.budget_app.ui.components.OverlayProgress
import website.msdnna.budget_app.ui.icons.categoryIcon
import website.msdnna.budget_app.ui.icons.parseCustomIconKey
import website.msdnna.budget_app.ui.icons.resolveCategoryColor

/**
 * Admin-only screen for editing per-category monthly_limit values.
 * Reachable from the «Лимит расходов» card on ExpensesScreen; the card
 * itself is only tappable when `isAdmin == true`, so this screen never
 * shows for non-admin users.
 *
 * UX:
 *  - Cards mirror the transaction list visual language (icon + name +
 *    amount on the right), but instead of an avatar we render the
 *    category icon. Tapping a card opens a BottomSheet to set / clear
 *    the limit.
 *  - Below each card sits a tinted progress bar reading the same
 *    palette as the «Лимит расходов» summary card (green / amber / red
 *    at 80% / 100%).
 *  - Online-only operation: backend gate is admin, sync engine doesn't
 *    carry partial updates for categories. Failures land in a snackbar-
 *    less inline error — re-tap to retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryLimitsScreen(
    primaryColor: Color,
    serverUrl: String,
    onClose: () -> Unit,
) {
    val categories by CategoryRepository.expense.collectAsState()
    val progress by LimitsProgressRepository.state.collectAsState()
    val byId = progress?.categories.orEmpty().associateBy { it.categoryId }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Category?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BackHandler(onBack = onClose)

    LaunchedEffect(serverUrl) {
        if (serverUrl.isNotBlank()) LimitsProgressRepository.refresh(serverUrl)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Лимиты категорий") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (categories.isEmpty()) {
            EmptyCategoriesState(padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(categories, key = { it.id }) { cat ->
                    val prog = byId[cat.id]
                    CategoryLimitCard(
                        category = cat,
                        spent = prog?.spent ?: 0.0,
                        percent = prog?.percent ?: 0.0,
                        serverUrl = serverUrl,
                        primaryColor = primaryColor,
                        onClick = { editing = cat },
                    )
                }
            }
        }
    }

    editing?.let { cat ->
        LimitEditSheet(
            category = cat,
            primaryColor = primaryColor,
            onDismiss = { editing = null },
            onSave = { newLimit ->
                scope.launch {
                    val updated = CategoryRepository.patchMonthlyLimit(serverUrl, cat.id, newLimit)
                    if (updated != null && serverUrl.isNotBlank()) {
                        LimitsProgressRepository.refresh(serverUrl)
                    }
                    editing = null
                }
            },
            sheetState = sheetState,
        )
    }
}

@Composable
private fun EmptyCategoriesState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Нет категорий расходов",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryLimitCard(
    category: Category,
    spent: Double,
    percent: Double,
    serverUrl: String,
    primaryColor: Color,
    onClick: () -> Unit,
) {
    val hasLimit = category.monthlyLimit != null && category.monthlyLimit > 0
    val tint = when {
        !hasLimit -> MaterialTheme.colorScheme.outline
        percent >= 100.0 -> Color(0xFFEF4444)
        percent >= 80.0 -> Color(0xFFF59E0B)
        else -> Color(0xFF22C55E)
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(
                    iconKey = category.icon,
                    iconScale = category.iconScale,
                    colorHex = category.color,
                    name = category.name,
                    serverUrl = serverUrl,
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (hasLimit) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${formatMoney(spent)} / ${formatMoney(category.monthlyLimit!!)} ₽ " +
                                "(${kotlin.math.round(percent).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (hasLimit) "${formatMoney(category.monthlyLimit!!)} ₽"
                    else "Без лимита",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    // outline reads as a placeholder/disabled tint on M3 and
                    // makes the «Без лимита» label nearly invisible on both
                    // themes. onSurfaceVariant gives a usable secondary
                    // contrast while still ranking below the active limit
                    // value typographically.
                    color = if (hasLimit) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasLimit) {
                Spacer(Modifier.height(8.dp))
                OverlayProgress(
                    fraction = (percent / 100.0).toFloat(),
                    color = tint,
                    trackColor = tint.copy(alpha = 0.16f),
                    height = 6.dp,
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(
    iconKey: String?,
    iconScale: Double,
    colorHex: String?,
    name: String,
    serverUrl: String,
) {
    val bg = resolveCategoryColor(name, colorHex)
    val builtin = categoryIcon(iconKey)
    val customId = parseCustomIconKey(iconKey)
    val customUrl = customId?.let { id -> "${serverUrl.trimEnd('/')}/api/icons/$id" }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            builtin != null -> Icon(
                builtin,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            customUrl != null -> {
                val s = if (iconScale > 0.0) iconScale.toFloat() else 1f
                coil.compose.AsyncImage(
                    model = customUrl,
                    contentDescription = null,
                    modifier = Modifier.size((22f * s).dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitEditSheet(
    category: Category,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onSave: (newLimit: Double?) -> Unit,
    sheetState: androidx.compose.material3.SheetState,
) {
    var amount by remember {
        mutableStateOf(category.monthlyLimit?.let { formatPlain(it) } ?: "")
    }
    val current = amount.replace(',', '.').toDoubleOrNull()
    val canSave = current != null && current > 0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                category.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Месячный лимит — потолок расходов на эту категорию за календарный месяц. Сбрасывается 1-го числа.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { v ->
                    // Allow digits + single decimal separator only.
                    amount = v.filter { it.isDigit() || it == '.' || it == ',' }
                },
                label = { Text("Лимит, ₽") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = primaryColor),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (category.monthlyLimit != null) {
                    OutlinedButton(
                        onClick = { onSave(null) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Снять лимит") }
                }
                Button(
                    onClick = { current?.let { onSave(it) } },
                    modifier = Modifier.weight(1f),
                    enabled = canSave,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                ) { Text("Сохранить") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatMoney(value: Double): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("ru-RU"))
        .apply { maximumFractionDigits = 0 }
        .format(value)

private fun formatPlain(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
