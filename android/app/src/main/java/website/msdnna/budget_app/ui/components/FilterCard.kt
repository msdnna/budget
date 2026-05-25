package website.msdnna.budget_app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import website.msdnna.budget_app.ui.theme.BudgetTheme

/**
 * Shared shell for the filter card on Income / Expenses / Statistics /
 * Forecast. Owns the collapse animation, the surface Card, the «Фильтры»
 * header and the optional «Всего: N / Сбросить» footer — every screen only
 * passes its own FilterSection's via [content].
 *
 * The footer renders when either [totalText] is non-null or
 * ([hasActiveFilters] && [onReset] != null).
 */
@Composable
fun FilterCard(
    visible: Boolean,
    modifier: Modifier = Modifier,
    title: String = "Фильтры",
    totalText: String? = null,
    hasActiveFilters: Boolean = false,
    onReset: (() -> Unit)? = null,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                content()
                val showReset = hasActiveFilters && onReset != null
                if (totalText != null || showReset) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (totalText != null) {
                            Text(
                                totalText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // Empty Box keeps «Сбросить» pinned to the right
                            // when no total is shown (SpaceBetween needs two
                            // children to position correctly).
                            Box {}
                        }
                        if (showReset) {
                            // Plain clickable Text rather than TextButton —
                            // the latter bakes in a 48dp min-height which
                            // made this row jump as soon as any filter
                            // activated.
                            Text(
                                "Сбросить",
                                style = MaterialTheme.typography.labelMedium,
                                color = primaryColor,
                                modifier = Modifier
                                    .clickable { onReset() }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// @Preview block (Android Studio layout). Renders all three footer variants
// side-by-side in light/dark so visual regressions to header, spacing or
// "Сбросить" footer are caught at design time. Wrapped in BudgetTheme so the
// preview uses the same palette (primary / surface / onSurface) as the app.
// ---------------------------------------------------------------------------

@Preview(
    name = "Light",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
    heightDp = 720,
)
@Preview(
    name = "Dark",
    showBackground = true,
    backgroundColor = 0xFF111418,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    heightDp = 720,
)
@Composable
private fun FilterCardPreview() {
    BudgetTheme(isDark = false) {
        Surface {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1) Income/Expenses-style: total + active reset.
                Text(
                    "1. С total + активный сброс",
                    style = MaterialTheme.typography.labelSmall,
                )
                FilterCard(
                    visible = true,
                    totalText = "Всего: 42",
                    hasActiveFilters = true,
                    onReset = {},
                ) {
                    PreviewSection("Период", selected = "Май 2026")
                    PreviewSection("Категории", selected = "Продукты")
                    PreviewSection("Счёт", selected = "Все счета")
                }

                // 2) Stats/Forecast-style: только reset, без total.
                Text(
                    "2. Только сброс (Stats/Forecast)",
                    style = MaterialTheme.typography.labelSmall,
                )
                FilterCard(
                    visible = true,
                    hasActiveFilters = true,
                    onReset = {},
                ) {
                    PreviewSection("Счёт", selected = "Наличные")
                }

                // 3) Idle (нет активных фильтров) — footer'а нет вовсе.
                Text(
                    "3. Idle (без активных)",
                    style = MaterialTheme.typography.labelSmall,
                )
                FilterCard(
                    visible = true,
                    totalText = "Всего: 0",
                    hasActiveFilters = false,
                    onReset = {},
                ) {
                    PreviewSection("Период", selected = "Год")
                    PreviewSection("Счёт", selected = "Все счета")
                }
            }
        }
    }
}

/**
 * Minimal stand-in for the real PeriodChipsRow / CategoryChipsRow /
 * DepositScopeChip rows — the production versions pull from DataStore /
 * Retrofit / CategoryRepository, none of which are wired up in the preview
 * runtime. This stub just shows what a typical [FilterSection] row looks
 * like with one active chip so the surrounding shell renders realistically.
 */
@Composable
private fun PreviewSection(title: String, selected: String) {
    FilterSection(title = title) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(selected) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
            item {
                FilterChip(selected = false, onClick = {}, label = { Text("Другое") })
            }
        }
    }
}
