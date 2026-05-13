package website.msdnna.budget_app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// Shared category icon + color dictionary.
//
// Keys mirror the web dictionary at frontend/src/utils/categoryIcons.js.
// Keep both files in sync — the backend stores these string keys on
// Category.icon and clients translate them to their native components.

private val ICONS: Map<String, ImageVector> = mapOf(
    "cart" to Icons.Filled.ShoppingCart,
    "car" to Icons.Filled.DirectionsCar,
    "home" to Icons.Filled.Home,
    "restaurant" to Icons.Filled.Restaurant,
    "game-controller" to Icons.Filled.SportsEsports,
    "medkit" to Icons.Filled.HealthAndSafety,
    "school" to Icons.Filled.School,
    "shirt" to Icons.Filled.Checkroom,
    "phone-portrait" to Icons.Filled.Smartphone,
    "airplane" to Icons.Filled.Flight,
    "call" to Icons.Filled.Phone,
    "rose" to Icons.Filled.Spa,
    "barbell" to Icons.Filled.FitnessCenter,
    "ellipsis-horizontal" to Icons.Filled.MoreHoriz,
    "cash" to Icons.Filled.Payments,
    "briefcase" to Icons.Filled.Work,
    "trending-up" to Icons.AutoMirrored.Filled.TrendingUp,
    "gift" to Icons.Filled.CardGiftcard,
    "key" to Icons.Filled.VpnKey,
    "desktop" to Icons.Filled.DesktopWindows,
    "flame" to Icons.Filled.LocalGasStation,
    "swap-horizontal" to Icons.Filled.SwapHoriz,
    "wallet" to Icons.Filled.AccountBalanceWallet,
    "fast-food" to Icons.Filled.Fastfood,
    "cafe" to Icons.Filled.LocalCafe,
    "bag-handle" to Icons.Filled.ShoppingBag,
    "tag" to Icons.AutoMirrored.Filled.Label,
)

val CATEGORY_ICON_ORDER: List<String> = listOf(
    "cart", "car", "home", "restaurant", "fast-food", "cafe",
    "game-controller", "medkit", "school", "shirt", "phone-portrait",
    "airplane", "call", "rose", "barbell", "cash", "briefcase",
    "trending-up", "gift", "key", "desktop", "flame", "swap-horizontal",
    "wallet", "bag-handle", "tag", "ellipsis-horizontal",
)

private val FALLBACK_COLORS: List<Color> = listOf(
    Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFF8B5CF6),
    Color(0xFFEF4444), Color(0xFF0EA5E9), Color(0xFFEC4899), Color(0xFF6366F1),
    Color(0xFF14B8A6), Color(0xFFA855F7), Color(0xFFF97316), Color(0xFFF472B6),
    Color(0xFF10B981), Color(0xFF64748B),
)

fun categoryIcon(key: String?): ImageVector =
    ICONS[key.orEmpty()] ?: Icons.AutoMirrored.Filled.Label

// Stable color for a category name when no explicit color is stored.
// Mirrors frontend/src/utils/categoryIcons.js fallbackColorFor().
fun fallbackColorFor(name: String?): Color {
    if (name.isNullOrEmpty()) return FALLBACK_COLORS[0]
    var h = 0
    for (ch in name) h = h * 31 + ch.code
    val idx = (h.rem(FALLBACK_COLORS.size) + FALLBACK_COLORS.size).rem(FALLBACK_COLORS.size)
    return FALLBACK_COLORS[idx]
}

// Parses a leading-hash hex string ("#RRGGBB") into a Color. Returns null
// if the string is empty/blank or malformed; callers fall back to a
// name-hashed palette color.
fun parseHexColor(hex: String?): Color? {
    val s = hex?.trim().orEmpty()
    if (s.length != 7 || !s.startsWith("#")) return null
    return try {
        val r = s.substring(1, 3).toInt(16)
        val g = s.substring(3, 5).toInt(16)
        val b = s.substring(5, 7).toInt(16)
        Color(red = r / 255f, green = g / 255f, blue = b / 255f)
    } catch (_: NumberFormatException) {
        null
    }
}

fun resolveCategoryColor(name: String?, color: String?): Color =
    parseHexColor(color) ?: fallbackColorFor(name)
