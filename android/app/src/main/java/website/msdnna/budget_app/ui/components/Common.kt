package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import website.msdnna.budget_app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Scrolls the nearest scrollable ancestor so this composable is visible when it gains focus.
 * Fixes keyboard overlap in scrollable containers (BottomSheets, scrollable forms).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringIntoViewOnFocus(): Modifier = composed {
    val scope = rememberCoroutineScope()
    val requester = remember { BringIntoViewRequester() }
    this
        .bringIntoViewRequester(requester)
        .onFocusEvent {
            if (it.isFocused) scope.launch {
                // Wait for IME animation to complete before scrolling.
                kotlinx.coroutines.delay(300)
                requester.bringIntoView()
            }
        }
}

private val NunitoExtraBold = FontFamily(Font(R.font.nunito_extrabold, FontWeight.ExtraBold))

/**
 * "mb" logo matching the web sidebar: bold letters, accent underline under "b".
 * The underline color is the primary color at 45% opacity.
 */
@Composable
fun MbLogo(
    primaryColor: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val underlineColor = primaryColor.copy(alpha = 0.45f)
    val lineThickness = if (fontSize.value >= 40f) 4.dp else 2.5.dp
    val lineGap = if (fontSize.value >= 40f) 5.dp else 3.dp

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            "m",
            modifier = Modifier.padding(bottom = lineThickness + lineGap),
            fontSize = fontSize,
            fontFamily = NunitoExtraBold,
            fontWeight = FontWeight.ExtraBold,
            color = primaryColor,
            letterSpacing = (-1).sp,
            lineHeight = fontSize
        )
        Text(
            "b",
            modifier = Modifier
                .drawWithContent {
                    drawContent()
                    val y = size.height - lineThickness.toPx() / 2
                    drawLine(
                        color = underlineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = lineThickness.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                .padding(bottom = lineThickness + lineGap),
            fontSize = fontSize,
            fontFamily = NunitoExtraBold,
            fontWeight = FontWeight.ExtraBold,
            color = primaryColor,
            lineHeight = fontSize
        )
    }
}

private val AVATAR_PALETTE = listOf(
    Color(0xFFE57373), Color(0xFFF06292), Color(0xFFBA68C8), Color(0xFF7986CB),
    Color(0xFF4FC3F7), Color(0xFF4DD0E1), Color(0xFF4DB6AC), Color(0xFF81C784),
    Color(0xFFAED581), Color(0xFFFFB74D), Color(0xFFFF8A65), Color(0xFFA1887F),
)

private fun avatarBgColor(name: String): Color {
    var hash = 0
    for (ch in name) hash = ch.code + ((hash shl 5) - hash)
    return AVATAR_PALETTE[kotlin.math.abs(hash) % AVATAR_PALETTE.size]
}

@Composable
fun UserAvatar(
    displayName: String,
    avatarUrl: String?,
    size: Dp = 28.dp
) {
    val initials = remember(displayName) {
        displayName.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2).joinToString("").ifEmpty { "?" }
    }
    val bgColor = remember(displayName) { avatarBgColor(displayName) }
    var imageError by remember(avatarUrl) { mutableStateOf(false) }

    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUrl.isNullOrBlank() && !imageError) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                onError = { imageError = true },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (avatarUrl.isNullOrBlank() || imageError) {
            Text(
                text = initials,
                color = Color.White,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = (size.value * 0.38f).sp
            )
        }
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Повторить")
        }
    }
}

@Composable
fun EmptyView(text: String = "Нет данных", modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SummaryCard(
    label: String,
    amount: Double,
    prefix: String = "",
    amountColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (prefix.isNotEmpty()) {
                    Text(
                        text = prefix,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = amountColor
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    text = formatMoney(amount),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                Text(
                    text = " ₽",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatMoney(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("ru", "RU"))
    fmt.maximumFractionDigits = 0
    return fmt.format(amount)
}

fun formatDate(isoDate: String): String {
    return try {
        val parts = isoDate.substring(0, 10).split("-")
        "${parts[2]}.${parts[1]}.${parts[0]}"
    } catch (e: Exception) { isoDate }
}

val MONTHS_SHORT = listOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн",
    "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")

val MONTHS_FULL = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")

fun monthName(month: Int, full: Boolean = false): String {
    val idx = (month - 1).coerceIn(0, 11)
    return if (full) MONTHS_FULL[idx] else MONTHS_SHORT[idx]
}
