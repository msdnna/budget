package website.msdnna.budget_app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import website.msdnna.budget_app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Provides a single shared shimmer-pulse alpha to all `SkeletonBox` instances
 * inside [content]. One [rememberInfiniteTransition] per loading state instead
 * of one per box keeps animation cost minimal even with dozens of skeletons.
 */
private val LocalShimmerAlpha = androidx.compose.runtime.compositionLocalOf { 0.22f }

@Composable
fun SkeletonShimmer(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalShimmerAlpha provides alpha, content = content)
}

/**
 * Solid rounded box used as a placeholder behind text/numbers in skeleton
 * loading states. When wrapped in [SkeletonShimmer] it pulses; standalone it
 * uses a static fallback alpha.
 */
@Composable
fun SkeletonBox(
    width: Dp,
    height: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    val alpha = LocalShimmerAlpha.current
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

/** Solid rounded circle placeholder, e.g. for avatar slots. */
@Composable
fun SkeletonCircle(size: Dp, modifier: Modifier = Modifier) {
    val alpha = LocalShimmerAlpha.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

/**
 * Skeleton replica of `SwipeableTransactionCard` / `SwipeableWishlistCard`.
 * Used while the screen's data is being fetched so the user sees a card-shaped
 * loading state instead of a centred spinner.
 */
@Composable
private fun SkeletonRowCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SkeletonCircle(size = 30.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(width = 56.dp)
                    SkeletonBox(width = 90.dp)
                }
                SkeletonBox(width = 140.dp, height = 15.dp)
            }
            SkeletonBox(width = 64.dp, height = 18.dp)
        }
    }
}

/**
 * Vertical stack of skeleton cards shown while a list-style screen
 * (Income / Expenses / Forecast) is loading from the API.
 */
@Composable
fun SkeletonTransactionList(count: Int = 6, modifier: Modifier = Modifier) {
    SkeletonShimmer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(count) { SkeletonRowCard() }
        }
    }
}

/**
 * Skeleton body for the Statistics screen — summary row + balance card + donut
 * card + bar chart card. The period selector at the top of the real screen is
 * NOT skeletoned (it's already laid out above this block).
 */
@Composable
fun SkeletonStatisticsContent(modifier: Modifier = Modifier) {
    SkeletonShimmer {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Income + expense side-by-side summary cards
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SkeletonBox(width = 80.dp, height = 12.dp)
                            SkeletonBox(width = 110.dp, height = 22.dp)
                        }
                    }
                }
            }
            // Balance card
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SkeletonBox(width = 80.dp, height = 12.dp)
                    SkeletonBox(width = 180.dp, height = 28.dp)
                }
            }
            // Donut card
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SkeletonBox(width = 160.dp, height = 16.dp)
                    SkeletonCircle(size = 160.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        repeat(3) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                SkeletonBox(width = 100.dp)
                                SkeletonBox(width = 60.dp)
                            }
                        }
                    }
                }
            }
            // Monthly bars card
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SkeletonBox(width = 180.dp, height = 16.dp)
                    val barAlpha = LocalShimmerAlpha.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = barAlpha))
                    )
                }
            }
        }
    }
}

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

/**
 * "mb" logo with sculpted shadow gradients under each letter (3D depth) and
 * a grey accent underline. Stacked as two drawables so [primaryColor] tints
 * only the letter base — the gradient shadows + underline overlay sit on top
 * untinted, surviving the ColorFilter that would otherwise flatten them.
 */
@Composable
fun MbLogo(
    primaryColor: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(size)
            .aspectRatio(304f / 184f)
    ) {
        Image(
            painter = painterResource(R.drawable.mb_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(primaryColor)
        )
        Image(
            painter = painterResource(R.drawable.mb_logo_overlay),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
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
fun OfflineView(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Повторить")
            }
        }
    }
}

@Composable
fun SummaryCard(
    label: String,
    amount: Double,
    prefix: String = "",
    amountColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    hidden: Boolean = false
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Crossfade(targetState = hidden, animationSpec = tween(220), label = "summaryHidden") { isHidden ->
                if (isHidden) {
                    Box(
                        modifier = Modifier
                            .height(26.dp)
                            .width(88.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(amountColor.copy(alpha = 0.22f))
                    )
                } else {
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
                        AnimatedAmountText(
                            amount = amount,
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
    }
}

/**
 * Smoothly animates between successive amount values. Used for monetary
 * displays where a stepped change (e.g. user edits the initial balance, or
 * statistics period changes) should glide rather than snap.
 */
@Composable
fun AnimatedAmountText(
    amount: Double,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
) {
    val animated = remember { Animatable(amount.toFloat()) }
    LaunchedEffect(amount) {
        animated.animateTo(
            targetValue = amount.toFloat(),
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing)
        )
    }
    Text(
        text = formatMoney(animated.value.toDouble()),
        modifier = modifier,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        style = style,
    )
}

fun formatMoney(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale.of("ru", "RU"))
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

/**
 * Centered checkmark overlay drawn on top of a list card when its screen is in
 * selection mode. Sits inside a shared [Box] with the card and fills it via
 * `matchParentSize()`. The overlay swallows clicks → [onClick] (toggle), so
 * the underlying card's combinedClickable is naturally disabled while shown.
 *
 * The icon sits directly over the card; the surrounding tinted card
 * background (applied by the caller via the card's `containerColor`) provides
 * enough visual separation so we don't need an extra puck behind it.
 */
@Composable
fun BoxScope.SelectionOverlay(
    visible: Boolean,
    selected: Boolean,
    primaryColor: Color,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(animationSpec = tween(140)),
        exit  = fadeOut(animationSpec = tween(140)),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = selected, animationSpec = tween(160), label = "selectIcon") { isSelected ->
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
