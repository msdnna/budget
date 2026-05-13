package website.msdnna.budget_app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class AppTheme(val name: String, val key: String, val primary: Color)

val AppThemes = listOf(
    AppTheme("Синий", "blue", Color(0xFF2080F0)),
    AppTheme("Зелёный", "green", Color(0xFF18A058)),
    AppTheme("Красный", "red", Color(0xFFD03050)),
    AppTheme("Оранжевый", "orange", Color(0xFFF0A020)),
    AppTheme("Фиолетовый", "purple", Color(0xFF722ED1)),
    AppTheme("Бирюзовый", "teal", Color(0xFF0EB0A9)),
    AppTheme("Розовый", "pink", Color(0xFFEB2F96)),
)

val IncomeColor = Color(0xFF2D6A4F)
val ExpenseColor = Color(0xFFE63946)
val IncomeColorDark = Color(0xFF52B788)
val ExpenseColorDark = Color(0xFFFF6B6B)

val LocalIncomeColor = staticCompositionLocalOf { IncomeColor }
val LocalExpenseColor = staticCompositionLocalOf { ExpenseColor }

fun themeByKey(key: String) = AppThemes.find { it.key == key } ?: AppThemes[0]

// In Material3 1.3+, AlertDialog/NavigationBar/etc. use surfaceContainer* tokens
// rather than `surface`. We override every variant explicitly so the dialog
// background matches `surface` instead of M3's default tinted (pink) values.
fun buildColorScheme(primary: Color): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.12f),
    onPrimaryContainer = primary,
    secondary = primary,
    secondaryContainer = primary.copy(alpha = 0.08f),
    onSecondaryContainer = primary,
    background = Color(0xFFF5F6FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    onBackground = Color(0xFF1A1A2E),
    onSurface = Color(0xFF1A1A2E),
    onSurfaceVariant = Color(0xFF666680),
    outline = Color(0xFFDDDDE0),
    outlineVariant = Color(0xFFEEEEF2),
)

fun buildDarkColorScheme(primary: Color): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.20f),
    onPrimaryContainer = primary,
    secondary = primary,
    secondaryContainer = primary.copy(alpha = 0.15f),
    onSecondaryContainer = primary,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2C2C2C),
    surfaceContainerLowest = Color(0xFF161616),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF272727),
    onBackground = Color(0xFFE8E8E8),
    onSurface = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF9E9EAA),
    outline = Color(0xFF3D3D42),
    outlineVariant = Color(0xFF2D2D32),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

@Composable
fun BudgetTheme(
    primary: Color = AppThemes[0].primary,
    isDark: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalIncomeColor provides if (isDark) IncomeColorDark else IncomeColor,
        LocalExpenseColor provides if (isDark) ExpenseColorDark else ExpenseColor,
    ) {
        MaterialTheme(
            colorScheme = if (isDark) buildDarkColorScheme(primary) else buildColorScheme(primary),
            typography = AppTypography,
            content = content
        )
    }
}
