package website.msdnna.budget_app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import website.msdnna.budget_app.ui.theme.LocalExpenseColor
import website.msdnna.budget_app.ui.theme.LocalIncomeColor

data class PieSlice(val label: String, val value: Float, val color: Color)

@Composable
fun DonutChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    centerText: String = ""
) {
    // Progressive sweep — 0..1. Re-animates whenever the slice list identity
    // changes (e.g. period change), so the chart re-draws in.
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        sweep.snapTo(0f)
        sweep.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        )
    }
    val progress = sweep.value

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val total = slices.sumOf { it.value.toDouble() }.toFloat()
            if (total == 0f) return@Canvas

            val radius = min(size.width, size.height) / 2f
            val stroke = radius * 0.38f
            val topLeft = Offset(
                size.width / 2f - radius + stroke / 2,
                size.height / 2f - radius + stroke / 2
            )
            val arcSize = Size((radius - stroke / 2) * 2, (radius - stroke / 2) * 2)

            var startAngle = -90f
            slices.forEach { slice ->
                val sweepAngle = (slice.value / total) * 360f * progress
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle.coerceAtLeast(0.5f) - 1.2f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                startAngle += sweepAngle
            }
        }
        if (centerText.isNotEmpty()) {
            Text(
                text = centerText,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChartLegend(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    pieUnitRuble: Boolean = true,
    valuesHidden: Boolean = false
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slices.forEach { slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(slice.color, CircleShape)
                )
                Text(
                    text = slice.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (pieUnitRuble) {
                    // AnimatedContent with CenterEnd alignment so the 48dp
                    // placeholder and the variable-width Text both anchor on
                    // the right column edge — Crossfade's TopStart default
                    // briefly slid the placeholder leftward during the fade.
                    androidx.compose.animation.AnimatedContent(
                        targetState = valuesHidden,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn(tween(220))
                                .togetherWith(androidx.compose.animation.fadeOut(tween(220)))
                        },
                        contentAlignment = Alignment.CenterEnd,
                        label = "legendValue",
                    ) { isHidden ->
                        if (isHidden) {
                            Box(
                                modifier = Modifier
                                    .height(14.dp)
                                    .width(48.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f))
                            )
                        } else {
                            Text(
                                text = "%.0f ₽".format(slice.value),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    val pct = if (total > 0f) slice.value / total * 100f else 0f
                    Text(
                        text = "%.1f%%".format(pct),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
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

    // Progressive grow — bars rise from baseline. Re-animates on data change.
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
            if (incH > 0)
                drawRect(
                    color = incomeColor,
                    topLeft = Offset(cx - gap - barW, chartH - incH),
                    size = Size(barW, incH)
                )

            val expH = (entry.expense / maxVal) * chartH * progress
            if (expH > 0)
                drawRect(
                    color = expenseColor,
                    topLeft = Offset(cx + gap, chartH - expH),
                    size = Size(barW, expH)
                )
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
                maxLines = 1
            )
        }
    }
}
