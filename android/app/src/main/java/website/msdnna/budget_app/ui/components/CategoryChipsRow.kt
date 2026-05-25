package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.ui.icons.categoryIcon
import website.msdnna.budget_app.ui.icons.resolveCategoryColor

/**
 * Multi-select category filter as a horizontally-scrollable chip row.
 * Layout:
 *   [ Все ]  [ icon Категория₁ ]  [ icon Категория₂ ] …
 *
 * Selection model mirrors what the user described:
 *   • "Все" is selected when `selected` is empty.
 *   • Tapping any category selects it AND deselects "Все".
 *   • Tapping more categories adds to the set (does not deselect existing).
 *   • Tapping a selected category removes it; if the set becomes empty,
 *     "Все" re-activates automatically.
 *   • Tapping "Все" clears the whole set.
 *
 * `onToggle` and `onClear` plug into the existing per-view-model verbs so
 * no VM-side change is needed.
 */
@Composable
fun CategoryChipsRow(
    selected: Set<String>,
    categories: List<Category>,
    primaryColor: Color,
    /** Resolves `custom:<id>` icon keys for chip leading-icons. Empty
     *  disables the custom-icon path; built-in vector icons still render. */
    serverUrl: String = "",
    onToggle: (name: String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    TrackInnerHorizontalScroll(listState)

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            val allSelected = selected.isEmpty()
            FilterChip(
                selected = allSelected,
                onClick = { if (!allSelected) onClear() },
                label = {
                    Text(
                        "Все",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = primaryColor,
                    selectedLabelColor = Color.White,
                ),
            )
        }
        items(categories, key = { it.id }) { cat ->
            val isSelected = cat.name in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(cat.name) },
                label = {
                    Text(
                        cat.name,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    val builtin = categoryIcon(cat.icon)
                    val tint = if (isSelected) Color.White else resolveCategoryColor(cat.name, cat.color)
                    if (builtin != null) {
                        androidx.compose.material3.Icon(
                            imageVector = builtin,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        // No icon defined for this category — collapse the
                        // leading slot. (Don't render a placeholder; the
                        // chip looks balanced without it.)
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = primaryColor,
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White,
                ),
            )
        }
    }
}
