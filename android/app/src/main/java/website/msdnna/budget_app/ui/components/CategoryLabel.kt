package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import website.msdnna.budget_app.data.model.Category
import website.msdnna.budget_app.ui.icons.categoryIcon
import website.msdnna.budget_app.ui.icons.parseCustomIconKey
import website.msdnna.budget_app.ui.icons.resolveCategoryColor

/**
 * Builds the absolute URL to a custom-uploaded category icon when the stored
 * `Category.icon` field follows the `custom:<id>` convention. Non-custom keys
 * (built-in vector lookups) and blank input return `null`.
 *
 * The `/api/` prefix mirrors `RetrofitClient.buildApiUrl` — the server URL the
 * app stores never ends with a slash, so trimming is defensive only.
 */
fun categoryIconUrl(serverUrl: String, iconKey: String?): String? {
    val id = parseCustomIconKey(iconKey) ?: return null
    val base = serverUrl.trimEnd('/')
    return "$base/api/icons/$id"
}

/**
 * Inline "<icon> <name>" pair for category references in lists, dropdowns and
 * detail rows. Same icon set the donut legend uses (built-in vector OR custom
 * uploaded SVG) but rendered WITHOUT a colored background badge — built-in
 * glyphs are tinted in the category's color directly, custom uploads keep
 * their natural colors so logos stay recognisable. When the category is
 * unknown (null) or has no icon set, falls back to plain text.
 *
 * The icon size defaults to the surrounding text size — measured from
 * [style] when expressible in sp, or [iconSize] when the caller wants to
 * override. The 6dp gap between glyph and label is the same gap the legend
 * row uses minus the badge padding.
 */
@Composable
fun CategoryLabel(
    name: String,
    category: Category?,
    serverUrl: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    textColor: Color = Color.Unspecified,
    iconSize: Dp? = null,
    spacing: Dp = 6.dp,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolvedColor = resolveCategoryColor(name, category?.color)
    val iconKey = category?.icon
    val builtin = categoryIcon(iconKey)
    val customUrl = categoryIconUrl(serverUrl, iconKey)
    // Caller wins; otherwise read the font size from [style]. For sp values
    // .toDp() returns the same numeric in dp (1sp = 1dp at default scale)
    // which is what we want — the glyph then matches the cap height of
    // adjacent text closely. Non-sp text (em-based) is uncommon in this
    // app; fall back to a sensible default size.
    val density = LocalDensity.current
    val computedSize = iconSize ?: run {
        val fs = style.fontSize
        if (fs.type == TextUnitType.Sp) with(density) { fs.toDp() } else 14.dp
    }
    // Pre-resolve the text colour outside the Text composable so we don't
    // call MaterialTheme from within a non-composable lambda below.
    val defaultTextColor = MaterialTheme.colorScheme.onSurface
    val resolvedTextColor = if (textColor != Color.Unspecified) {
        textColor
    } else {
        val styleColor = LocalTextStyle.current.color
        if (styleColor != Color.Unspecified) styleColor else defaultTextColor
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        when {
            builtin != null -> Icon(
                imageVector = builtin,
                contentDescription = null,
                tint = resolvedColor,
                modifier = Modifier.size(computedSize),
            )
            customUrl != null -> {
                // Custom uploads are typically alpha-only mono-glyphs in this
                // app (Wildberries / OZON / Магнит etc.) — invisible on light
                // surfaces and inconsistently coloured on dark ones. Tint them
                // in the category colour so they read the same way the built-in
                // vectors do. If a multi-colour logo ever lands here the tint
                // will flatten it; admins should pick built-in keys for those.
                //
                // Size is locked to the same `computedSize` as built-ins —
                // the per-category `iconScale` (designed to fill the donut
                // legend's 28dp badge with an 18dp base) would overflow text
                // baselines here. ContentScale.Fit keeps the aspect ratio of
                // non-square SVGs while clamping the longest dimension.
                AsyncImage(
                    model = customUrl,
                    contentDescription = null,
                    modifier = Modifier.size(computedSize),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(resolvedColor),
                )
            }
            // No icon set on the category — collapse the icon slot entirely
            // so the label flushes left. Don't emit a spacer or zero-size
            // Box: that would leave a phantom gap from the Row's
            // horizontalArrangement.spacedBy.
        }
        Text(
            text = name,
            style = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style,
            color = resolvedTextColor,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

// ── Previews ─────────────────────────────────────────────────────────────────
// Android Studio renders these via the debug-only `ui-tooling` artifact;
// they ship as no-ops in release. Always wrap in `BudgetTheme` so the
// preview picks up our palette (primary / surface / onSurface) instead of
// the default M3 baseline — without it CategoryLabel renders against pure
// white and looks nothing like what the user sees on device.

@Preview(
    name = "Light",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF,
)
@Preview(
    name = "Dark",
    showBackground = true,
    backgroundColor = 0xFF111418,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CategoryLabelPreview() {
    // Anything from the `data.model` layer is safe to instantiate inline —
    // these previews must NOT touch ViewModels, Retrofit, Room or
    // CategoryRepository (preview runtime has no Android context wired up).
    val groceries = website.msdnna.budget_app.data.model.Category(
        id = "p1",
        name = "Продукты",
        section = "expense",
        color = "#22C55E",
        icon = "cart",
    )
    val health = website.msdnna.budget_app.data.model.Category(
        id = "p2",
        name = "Здоровье",
        section = "expense",
        color = "#EF4444",
        icon = "medkit",
    )
    val unknown = website.msdnna.budget_app.data.model.Category(
        id = "p3",
        name = "Без иконки",
        section = "expense",
        color = "#6366F1",
        icon = null,
    )
    // BudgetTheme(isDark = ...) is normally derived from DataStore at app
    // startup; for previews we hardcode the value and Studio picks the
    // matching @Preview entry from the gutter dropdown.
    website.msdnna.budget_app.ui.theme.BudgetTheme(isDark = false) {
        androidx.compose.material3.Surface {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryLabel(
                    name = groceries.name,
                    category = groceries,
                    serverUrl = "",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                CategoryLabel(
                    name = health.name,
                    category = health,
                    serverUrl = "",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                CategoryLabel(
                    name = unknown.name,
                    category = unknown,
                    serverUrl = "",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
