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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
