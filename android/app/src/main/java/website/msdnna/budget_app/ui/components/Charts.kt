package website.msdnna.budget_app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import website.msdnna.budget_app.ui.icons.categoryIcon
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.theme.LocalIncomeColor

data class PieSlice(
    val label: String,
    val value: Float,
    val color: Color,
    val iconKey: String? = null,
    val customIconUrl: String? = null,
    // Multiplier for the custom icon size inside the legend badge — pie
    // slice icons stay fixed for arc readability. <= 0 means "use default".
    val iconScale: Float = 1f,
)

private const val MIN_ICON_PCT = 5f // skip on-slice icon below this
private const val MIN_VISIBLE_PCT = 5f // fold into "Прочее" below this
private const val OTHER_LABEL = "Прочее"
private val OTHER_COLOR = Color(0xFF64748B) // slate — neutral grey for grouping

/**
 * Builds a closed donut-wedge path with rounded corners on all four sides.
 *
 * The wedge is described by `[startAngleDeg, startAngleDeg + sweepAngleDeg]`
 * sweeping clockwise (the same convention as Compose `Canvas.drawArc`, where
 * 0° is at 3 o'clock and -90° is at 12 o'clock).
 *
 * Corners are filleted with a quadratic Bezier whose control point is the
 * unrounded corner — this is a small geometric approximation of a circular
 * arc that's cheap to compute and visually close enough at these radii.
 * Filled with `DrawScope.drawPath()`; gaps between adjacent slices are
 * created by leaving angular space between consecutive slices (the gap
 * reveals whatever the Canvas sits on top of — `Card.surface` in our case,
 * so it's theme-aware without any explicit drawing).
 */
private fun buildSlicePath(
    cx: Float,
    cy: Float,
    innerR: Float,
    outerR: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
    cornerR: Float,
): Path {
    val path = Path()
    val a1 = startAngleDeg
    val a2 = startAngleDeg + sweepAngleDeg

    // Don't let the corner eat more than half the radial thickness — a thin
    // sector would otherwise collapse to nothing.
    val cornerClamp = cornerR.coerceAtMost((outerR - innerR) / 2f)
    // Angular offset for the cornerR roundoff at each radius. Cap at half the
    // sweep so the two ends of a thin slice don't cross.
    val cornerArcDegInner = (Math.toDegrees((cornerClamp / innerR).toDouble()).toFloat())
        .coerceAtMost(sweepAngleDeg / 2f)
    val cornerArcDegOuter = (Math.toDegrees((cornerClamp / outerR).toDouble()).toFloat())
        .coerceAtMost(sweepAngleDeg / 2f)

    val r1in = innerR + cornerClamp // radial position inset from the inner corner
    val r2in = outerR - cornerClamp // radial position inset from the outer corner

    fun pt(r: Float, angDeg: Float): Offset {
        val rad = Math.toRadians(angDeg.toDouble())
        return Offset(
            cx + (r * cos(rad)).toFloat(),
            cy + (r * sin(rad)).toFloat(),
        )
    }

    val p1 = pt(innerR, a1 + cornerArcDegInner) // inner arc after start corner
    val p2 = pt(innerR, a2 - cornerArcDegInner) // inner arc before end corner
    val p3 = pt(r1in, a2) // right edge inside bottom-right corner
    val p4 = pt(r2in, a2) // right edge inside top-right corner
    val p5 = pt(outerR, a2 - cornerArcDegOuter) // outer arc before end corner
    val p6 = pt(outerR, a1 + cornerArcDegOuter) // outer arc after start corner
    val p7 = pt(r2in, a1) // left edge inside top-left corner
    val p8 = pt(r1in, a1) // left edge inside bottom-left corner
    val cBR = pt(innerR, a2) // unrounded inner-end corner (Bezier control)
    val cTR = pt(outerR, a2) // unrounded outer-end corner
    val cTL = pt(outerR, a1) // unrounded outer-start corner
    val cBL = pt(innerR, a1) // unrounded inner-start corner

    val innerRect = Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
    val outerRect = Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR)

    path.moveTo(p1.x, p1.y)
    // Inner arc clockwise from p1 to p2.
    path.arcTo(
        rect = innerRect,
        startAngleDegrees = a1 + cornerArcDegInner,
        sweepAngleDegrees = (a2 - cornerArcDegInner) - (a1 + cornerArcDegInner),
        forceMoveTo = false,
    )
    // Round corner at inner-end (bottom-right of the wedge as drawn).
    path.quadraticTo(cBR.x, cBR.y, p3.x, p3.y)
    // Up the right edge.
    path.lineTo(p4.x, p4.y)
    // Round corner at outer-end (top-right).
    path.quadraticTo(cTR.x, cTR.y, p5.x, p5.y)
    // Outer arc counter-clockwise from p5 to p6 (negative sweep).
    path.arcTo(
        rect = outerRect,
        startAngleDegrees = a2 - cornerArcDegOuter,
        sweepAngleDegrees = (a1 + cornerArcDegOuter) - (a2 - cornerArcDegOuter),
        forceMoveTo = false,
    )
    // Round corner at outer-start (top-left).
    path.quadraticTo(cTL.x, cTL.y, p7.x, p7.y)
    // Down the left edge.
    path.lineTo(p8.x, p8.y)
    // Round corner at inner-start (bottom-left), back to p1.
    path.quadraticTo(cBL.x, cBL.y, p1.x, p1.y)
    path.close()
    return path
}

private fun groupSmallSlices(slices: List<PieSlice>): List<PieSlice> {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    if (total == 0f) return slices
    val big = mutableListOf<PieSlice>()
    val small = mutableListOf<PieSlice>()
    for (s in slices) {
        if (s.value / total * 100f < MIN_VISIBLE_PCT) small.add(s) else big.add(s)
    }
    // Single small slice isn't worth folding — "Прочее (1)" is just noise.
    if (small.size <= 1) return slices
    val sum = small.sumOf { it.value.toDouble() }.toFloat()
    return big + PieSlice(
        label = OTHER_LABEL,
        value = sum,
        color = OTHER_COLOR,
        iconKey = "ellipsis-horizontal",
    )
}

/**
 * High-level donut + legend pair that owns its filter state.
 *
 * - Click on a legend row toggles that category's visibility in the donut.
 *   Hidden rows render with reduced opacity + strikethrough.
 * - Slices < 3% of the visible total are folded into a single grey "Прочее"
 *   wedge; the small categories remain in the legend so the user can drill
 *   down by hiding bigger ones.
 *
 * Filter state resets whenever the underlying slice list changes (period
 * switch or data reload) — keyed by the joined label list, which changes
 * with actual data but not with cosmetic recompositions.
 */
@Composable
fun CategoryDonut(
    allSlices: List<PieSlice>,
    modifier: Modifier = Modifier,
    centerText: String = "",
    pieUnitRuble: Boolean = true,
    valuesHidden: Boolean = false,
    legendMaxHeight: androidx.compose.ui.unit.Dp = 200.dp,
) {
    val labelKey = remember(allSlices) { allSlices.joinToString("|") { it.label } }
    var hidden by remember(labelKey) { mutableStateOf(setOf<String>()) }

    val pieSlices by remember(allSlices, hidden) {
        derivedStateOf { groupSmallSlices(allSlices.filter { it.label !in hidden }) }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DonutChart(
            slices = pieSlices,
            modifier = Modifier.size(160.dp),
            centerText = centerText,
        )
        ChartLegend(
            slices = allSlices,
            hidden = hidden,
            onToggle = { name ->
                hidden = if (name in hidden) hidden - name else hidden + name
            },
            pieUnitRuble = pieUnitRuble,
            valuesHidden = valuesHidden,
            maxHeight = legendMaxHeight,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun DonutChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    centerText: String = "",
) {
    // Progressive sweep — 0..1. Re-animates whenever the slice list identity
    // changes (e.g. period change or filter toggle).
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        sweep.snapTo(0f)
        sweep.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        )
    }
    val progress = sweep.value
    val total = remember(slices) { slices.sumOf { it.value.toDouble() }.toFloat() }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val refDp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val refPx: Float = with(density) { refDp.toPx() }
        val strokeFraction = 0.38f
        val midRadiusPx = (refPx / 2f) * (1f - strokeFraction / 2f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (total == 0f) return@Canvas

            val radius = min(size.width, size.height) / 2f
            val stroke = radius * strokeFraction
            val innerR = radius - stroke
            val outerR = radius
            val cx = size.width / 2f
            val cy = size.height / 2f

            // Single-slice donuts (e.g. one-category income) draw as a plain
            // closed ring — rounded corners on a 360° wedge would meet at the
            // start/end and create a small notch artifact at 12 o'clock.
            if (slices.size == 1) {
                val ringPath = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addOval(Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR))
                    addOval(Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR))
                }
                drawPath(ringPath, color = slices[0].color, alpha = progress)
                return@Canvas
            }

            // 1.5° gap between adjacent slices — the unrendered angular range
            // shows the Card surface beneath the Canvas, so the gap is
            // theme-aware without any explicit border draw.
            val gapDeg = 1.5f
            // Corner roundoff radius — proportional to stroke so it scales
            // with chart size.
            val cornerR = stroke * 0.22f

            var startAngle = -90f
            slices.forEach { slice ->
                val sweepAngle = (slice.value / total) * 360f * progress
                val drawSweep = (sweepAngle - gapDeg).coerceAtLeast(0.5f)
                val sliceStart = startAngle + gapDeg / 2f
                val path = buildSlicePath(
                    cx = cx,
                    cy = cy,
                    innerR = innerR,
                    outerR = outerR,
                    startAngleDeg = sliceStart,
                    sweepAngleDeg = drawSweep,
                    cornerR = cornerR,
                )
                drawPath(path, color = slice.color)
                startAngle += sweepAngle
            }
        }

        // On-slice icons. No background chip — just the icon (white tinted
        // for builtin vectors, natural colours for user-uploaded customs)
        // anchored at the arc midline. Rendered alongside the sweep so they
        // fade in as the donut draws.
        if (total > 0f) {
            var cum = 0f
            for (slice in slices) {
                val pct = slice.value / total * 100f
                val builtin = categoryIcon(slice.iconKey)
                val hasIcon = builtin != null || slice.customIconUrl != null
                if (pct >= MIN_ICON_PCT && hasIcon) {
                    val midRad = ((cum + slice.value / 2f) / total * 2f * Math.PI - Math.PI / 2f).toFloat()
                    val xPx = cos(midRad) * midRadiusPx
                    val yPx = sin(midRad) * midRadiusPx
                    val xDp = with(density) { xPx.toDp() }
                    val yDp = with(density) { yPx.toDp() }
                    val iconMod = Modifier
                        .offset(x = xDp, y = yDp)
                        .size(16.dp)
                    if (builtin != null) {
                        Icon(
                            imageVector = builtin,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = iconMod,
                        )
                    } else {
                        AsyncImage(
                            model = slice.customIconUrl,
                            contentDescription = null,
                            modifier = iconMod,
                        )
                    }
                }
                cum += slice.value
            }
        }

        if (centerText.isNotEmpty()) {
            Text(
                text = centerText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun ChartLegend(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
    onToggle: ((String) -> Unit)? = null,
    pieUnitRuble: Boolean = true,
    valuesHidden: Boolean = false,
    maxHeight: androidx.compose.ui.unit.Dp = 200.dp,
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        slices.forEach { slice ->
            val isHidden = slice.label in hidden
            val rowModifier = if (onToggle != null) {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggle(slice.label) }
            } else {
                Modifier.fillMaxWidth()
            }
            Row(
                modifier = rowModifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Colored badge — glyph layered when the category has an
                // icon (builtin or user-uploaded), bare colored square when
                // it doesn't (no icon set yet from admin). `clip()` lets a
                // scaled-up custom icon bleed against the badge silhouette
                // without escaping the rounded corners.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(slice.color),
                    contentAlignment = Alignment.Center,
                ) {
                    val builtin = categoryIcon(slice.iconKey)
                    when {
                        builtin != null -> Icon(
                            imageVector = builtin,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        slice.customIconUrl != null -> {
                            val s = if (slice.iconScale > 0f) slice.iconScale else 1f
                            AsyncImage(
                                model = slice.customIconUrl,
                                contentDescription = null,
                                modifier = Modifier.size((18f * s).dp),
                            )
                        }
                    }
                }
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isHidden) 0.4f else 1f),
                    textDecoration = if (isHidden) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pieUnitRuble) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = valuesHidden,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn(tween(220))
                                .togetherWith(androidx.compose.animation.fadeOut(tween(220)))
                        },
                        contentAlignment = Alignment.CenterEnd,
                        label = "legendValue",
                    ) { isAmountHidden ->
                        if (isAmountHidden) {
                            Box(
                                modifier = Modifier
                                    .height(14.dp)
                                    .width(48.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)),
                            )
                        } else {
                            Text(
                                text = "%.0f ₽".format(slice.value),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isHidden) 0.4f else 1f),
                                textDecoration = if (isHidden) TextDecoration.LineThrough else null,
                            )
                        }
                    }
                } else {
                    val pct = if (total > 0f) slice.value / total * 100f else 0f
                    Text(
                        text = if (pct < 10f) "%.1f%%".format(pct) else "%.0f%%".format(pct),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isHidden) 0.4f else 1f),
                        textDecoration = if (isHidden) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

data class BarEntry(val label: String, val income: Float, val expense: Float)

@Composable
fun BarChart(entries: List<BarEntry>, modifier: Modifier = Modifier) {
    val incomeColor = LocalIncomeColor.current
    val expenseColor = LocalExpenseColor.current

    val grow = remember { Animatable(0f) }
    LaunchedEffect(entries) {
        grow.snapTo(0f)
        grow.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        )
    }
    val progress = grow.value

    Canvas(modifier = modifier) {
        if (entries.isEmpty()) return@Canvas
        val maxVal = entries.maxOf { maxOf(it.income, it.expense) }.coerceAtLeast(1f)
        val barGroupW = size.width / entries.size
        val barW = barGroupW * 0.32f
        val gap = barW * 0.25f
        val chartH = size.height - 20.dp.toPx()

        entries.forEachIndexed { i, entry ->
            val cx = i * barGroupW + barGroupW / 2f

            val incH = (entry.income / maxVal) * chartH * progress
            if (incH > 0) {
                drawRect(
                    color = incomeColor,
                    topLeft = Offset(cx - gap - barW, chartH - incH),
                    size = Size(barW, incH),
                )
            }

            val expH = (entry.expense / maxVal) * chartH * progress
            if (expH > 0) {
                drawRect(
                    color = expenseColor,
                    topLeft = Offset(cx + gap, chartH - expH),
                    size = Size(barW, expH),
                )
            }
        }
    }
}

@Composable
fun BarChartLabels(entries: List<BarEntry>, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        entries.forEach { entry ->
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
    }
}
