package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import website.msdnna.budget_app.data.model.Category

/**
 * Multi-select category filter. Renders a clickable readonly field that mirrors
 * an OutlinedTextField; tapping it opens a dropdown with checkboxed categories
 * plus a "Сбросить" footer. Pre-sorted [categories] should already reflect the
 * recent-use order from the view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFilterField(
    selected: Set<String>,
    categories: List<Category>,
    primaryColor: Color,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected.isEmpty() -> "Все категории"
        selected.size == 1 -> selected.first()
        else -> "Категории: ${selected.size}"
    }

    Box(modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Категория") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = true },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedBorderColor = primaryColor,
            ),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .heightIn(max = 360.dp),
        ) {
            categories.forEach { cat ->
                val isSelected = cat.name in selected
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggle(cat.name) },
                                colors = CheckboxDefaults.colors(checkedColor = primaryColor),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                cat.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    },
                    onClick = { onToggle(cat.name) },
                    trailingIcon = if (!cat.isDefault) {
                        {
                            IconButton(
                                onClick = { onDelete(cat.id) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Удалить",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    } else null,
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Сбросить", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = {
                    onClear()
                    expanded = false
                },
                enabled = selected.isNotEmpty(),
            )
        }
    }
}
