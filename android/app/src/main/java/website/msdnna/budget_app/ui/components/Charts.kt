package website.msdnna.budget_app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.launch
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
    // Monthly limit progress — populated only for expense slices where the
    // admin has set Category.monthly_limit. Always reflects the CURRENT
    // calendar month regardless of the chart's active period filter; a
    // year-to-date spend over a monthly limit would be misleading.
    val limitTotal: Double? = null,
    val limitSpent: Double = 0.0,
    val limitPercent: Double = 0.0,
    // Populated only for the synthetic "Прочее" wedge — the list of labels
    // that were folded into it. Tap-on-Прочее uses this to programmatically
    // hide the *non*-grouped (big) categories so the small ones expand into
    // full-sized wedges. Empty for ordinary slices.
    val groupedLabels: List<String> = emptyList(),
)

private const val MIN_ICON_PCT = 5f // skip on-slice icon below this
private const val MIN_VISIBLE_PCT = 5f // fold into "Прочее" below this
private const val OTHER_LABEL = "Прочее"
private val OTHER_COLOR = Color(0xFF64748B) // slate — neutral grey for grouping
private const val STROKE_FRACTION = 0.38f

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
        // Carry the small-slice labels so a tap on Прочее can hide every
        // *other* (non-grouped) category and reveal them as full-sized
        // wedges. Without this list, the wedge would have no way to know
        // which categories it represents at click time.
        groupedLabels = small.map { it.label },
    )
}

/**
 * High-level donut + legend pair that owns its filter state.
 *
 * - Click on a legend row toggles that category's visibility in the donut.
 *   Hidden rows render with reduced opacity + strikethrough.
 * - Slices < 5% of the visible total are folded into a single grey "Прочее"
 *   wedge; the small categories remain in the legend so the user can drill
 *   down by hiding bigger ones.
 * - Tap on a regular slice → [onCategoryDrilldown] (when non-null) — the
 *   parent navigates to the corresponding transactions view filtered by
 *   that category.
 * - Tap on the synthetic "Прочее" wedge — programmatically hides every
 *   *non*-grouped (big) category so the small ones expand into full
 *   wedges; no drilldown fires until the user clicks one of the now-large
 *   slices.
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
    onCategoryDrilldown: ((String) -> Unit)? = null,
) {
    val labelKey = remember(allSlices) { allSlices.joinToString("|") { it.label } }
    var hidden by remember(labelKey) { mutableStateOf(setOf<String>()) }

    val pieSlices by remember(allSlices, hidden) {
        derivedStateOf { groupSmallSlices(allSlices.filter { it.label !in hidden }) }
    }

    // Tap on a slice. Behavior splits on whether the wedge is the synthetic
    // "Прочее" group: regular slices drill down to transactions; Прочее
    // toggles the legend filter so the small categories take over the
    // canvas (allowing a follow-up drilldown click).
    val onSliceClick: ((PieSlice) -> Unit)? = if (onCategoryDrilldown != null) {
        { slice ->
            if (slice.groupedLabels.isNotEmpty()) {
                // Hide every label NOT in the Прочее group → the grouped
                // small categories become the only visible slices.
                hidden = allSlices.map { it.label }
                    .filter { it !in slice.groupedLabels }
                    .toSet()
            } else {
                onCategoryDrilldown(slice.label)
            }
        }
    } else {
        null
    }

    // Sentinel that bumps whenever the upstream data set (period switch,
    // pull-to-refresh, backend reload) replaces allSlices wholesale —
    // but NOT when the legend toggle alone changes `hidden`. DonutChart
    // uses it to pick between two animation modes: a clockwise reveal
    // sweep on fresh data, vs per-slice morph on filter toggles.
    val freshDataKey = remember(allSlices) { Any() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DonutChart(
            slices = pieSlices,
            modifier = Modifier.size(160.dp),
            centerText = centerText,
            onSliceClick = onSliceClick,
            freshDataKey = freshDataKey,
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

/**
 * Per-slice animation state. Each entry's [value] [Animatable] animates from
 * the previous value toward its current target whenever the input list
 * changes; entries that disappear from the input animate to 0 and are then
 * dropped. Reading [value].value in the Canvas draw scope automatically
 * subscribes the chart to per-frame redraws via Compose snapshots.
 */
private class AnimSlice(
    val label: String,
    var slice: PieSlice,
    val anim: Animatable<Float, AnimationVector1D>,
    var exiting: Boolean,
)

@Composable
fun DonutChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    centerText: String = "",
    onSliceClick: ((PieSlice) -> Unit)? = null,
    // Sentinel from the parent: bumped whenever the upstream data set is
    // replaced wholesale (period switch, pull-to-refresh, backend reload).
    // A different instance vs the last observed value triggers the
    // clockwise reveal sweep — without this signal, all backend refreshes
    // would just morph per-slice and lose the "fresh data" feel.
    freshDataKey: Any? = null,
) {
    // Per-slice animation entries. For filter-toggle morphing: entries keep
    // their slot through the transition (neighbours stay neighbours). For
    // fresh-data reveal: entries are snapped to their final values and a
    // separate sweepProgress 0→1 controls how much of the donut is drawn.
    val entries = remember { mutableStateListOf<AnimSlice>() }
    // 1f means "fully drawn"; values <1 expose only the leading portion
    // of the donut (clockwise from 12 o'clock). Outside fresh-data mode
    // we keep it at 1f so the morph path renders every slice in full.
    val sweepProgress = remember { Animatable(0f) }
    // Sentinel marker for "we've never seen any data yet" — distinct from
    // a caller-supplied null, so the very first slices-update is always
    // treated as fresh data.
    val unsetMarker = remember { Any() }
    var lastFreshKey by remember { mutableStateOf<Any?>(unsetMarker) }
    // Compose previews don't tick the animation clock — `Animatable.animateTo`
    // suspends indefinitely and slices never appear. Inspector mode short-
    // circuits to the fully-drawn state so @Preview blocks render the chart
    // at progress = 1f.
    val inInspection = androidx.compose.ui.platform.LocalInspectionMode.current

    LaunchedEffect(slices) {
        if (inInspection) {
            entries.clear()
            for (s in slices) {
                entries.add(AnimSlice(s.label, s, Animatable(s.value), false))
            }
            sweepProgress.snapTo(1f)
            lastFreshKey = freshDataKey
            return@LaunchedEffect
        }
        val isFreshData = lastFreshKey !== freshDataKey
        if (isFreshData) {
            // Clockwise reveal: rebuild entries from scratch with values
            // pre-snapped to their target shares, then animate sweepProgress
            // 0→1. Per-slice anim values never move during a sweep — the
            // illusion of motion comes from progressively revealing slices
            // along the arc, not from individual wedges growing radially.
            lastFreshKey = freshDataKey
            entries.clear()
            for (s in slices) {
                entries.add(
                    AnimSlice(
                        label = s.label,
                        slice = s,
                        anim = Animatable(s.value),
                        exiting = false,
                    ),
                )
            }
            sweepProgress.snapTo(0f)
            sweepProgress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            return@LaunchedEffect
        }

        // Filter-toggle morph path. Two responsibilities here:
        // - Preserve slot positions: a hidden-then-shown slice must come
        //   back at its ORIGINAL position, not appended at the end. The
        //   walk below interleaves target slices (in target order) with
        //   exiting entries (kept at their old positions) by stepping
        //   through both lists in lockstep.
        // - Reuse Animatable instances: an in-flight animateTo(0) on a
        //   re-shown slice gets cancelled by this LaunchedEffect re-key
        //   and the new animateTo(targetValue) reverses smoothly from
        //   wherever the value happened to land.
        sweepProgress.snapTo(1f)
        val existingByLabel = entries.associateBy { it.label }
        val newLabelSet = slices.map { it.label }.toSet()

        // Build target entries in target order, reusing AnimSlices when
        // we have one (and stamping the new slice metadata onto it).
        val targetEntries = slices.map { s ->
            val existing = existingByLabel[s.label]
            if (existing != null) {
                existing.slice = s
                existing.exiting = false
                existing
            } else {
                AnimSlice(s.label, s, Animatable(0f), false)
            }
        }

        // Walk the OLD order. For every old slot we either:
        //   a) emit the surviving target entry that matched (along with
        //      any target entries that came BEFORE it in target order
        //      and didn't exist in the old order — those get inserted at
        //      the right slot), or
        //   b) emit an exiting entry as-is, preserving its position.
        val newOrder = mutableListOf<AnimSlice>()
        var cursor = 0
        val oldOrder = entries.toList()
        for (oldEntry in oldOrder) {
            if (oldEntry.label in newLabelSet) {
                // Drain target entries that don't appear in the old order
                // (newly visible labels) up to the matching position.
                while (cursor < targetEntries.size &&
                    targetEntries[cursor].label != oldEntry.label
                ) {
                    newOrder.add(targetEntries[cursor])
                    cursor++
                }
                if (cursor < targetEntries.size) {
                    newOrder.add(targetEntries[cursor])
                    cursor++
                }
            } else {
                newOrder.add(oldEntry)
            }
        }
        // Tail: any target entries with no anchor in oldOrder land at the end.
        while (cursor < targetEntries.size) {
            newOrder.add(targetEntries[cursor])
            cursor++
        }

        entries.clear()
        entries.addAll(newOrder)

        // Animate target entries toward their share.
        for (e in targetEntries) {
            launch {
                e.anim.animateTo(e.slice.value, tween(700, easing = FastOutSlowInEasing))
            }
        }
        // Mark entries no longer in target as exiting and animate to 0;
        // after the animation finishes the slot is released.
        for (e in oldOrder) {
            if (e.label !in newLabelSet && !e.exiting) {
                e.exiting = true
                val label = e.label
                launch {
                    e.anim.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
                    entries.removeAll { it.label == label && it.exiting }
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val refDp = if (maxWidth < maxHeight) maxWidth else maxHeight
        val refPx: Float = with(density) { refDp.toPx() }
        val midRadiusPx = (refPx / 2f) * (1f - STROKE_FRACTION / 2f)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onSliceClick) {
                    if (onSliceClick == null) return@pointerInput
                    detectTapGestures { offset ->
                        // Swallow taps mid-sweep: hit-testing against
                        // partially-drawn slices would map clicks to wedges
                        // the user can't see yet.
                        if (sweepProgress.value < 1f) return@detectTapGestures
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val radius = min(width, height) / 2f
                        val stroke = radius * STROKE_FRACTION
                        val innerR = radius - stroke
                        val cx = width / 2f
                        val cy = height / 2f
                        val dx = offset.x - cx
                        val dy = offset.y - cy
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist < innerR || dist > radius) return@detectTapGestures
                        // atan2 returns radians measured from +x (3 o'clock)
                        // sweeping counter-clockwise in mathematical space but
                        // clockwise on screen (y flipped). Normalize to 0..360
                        // measured clockwise from -90° (12 o'clock) — the
                        // same reference the slice draw loop uses.
                        val angDeg = Math.toDegrees(
                            atan2(dy.toDouble(), dx.toDouble())
                        ).toFloat()
                        var rel = (angDeg + 90f) % 360f
                        if (rel < 0f) rel += 360f
                        val totalNow = entries.sumOf { it.anim.value.toDouble() }.toFloat()
                        if (totalNow == 0f) return@detectTapGestures
                        var cum = 0f
                        for (e in entries.toList()) {
                            val span = (e.anim.value / totalNow) * 360f
                            if (rel >= cum && rel < cum + span) {
                                onSliceClick(e.slice)
                                return@detectTapGestures
                            }
                            cum += span
                        }
                    }
                }
        ) {
            val total = entries.sumOf { it.anim.value.toDouble() }.toFloat()
            if (total <= 0f) return@Canvas

            val radius = min(size.width, size.height) / 2f
            val stroke = radius * STROKE_FRACTION
            val innerR = radius - stroke
            val outerR = radius
            val cx = size.width / 2f
            val cy = size.height / 2f
            val progress = sweepProgress.value
            // Maximum angle past -90° (12 o'clock) that's currently allowed
            // to render. While progress < 1 we clip each slice to whatever
            // portion lies inside this cap, producing the clockwise reveal.
            val maxAngleFromStart = 360f * progress

            // Single-slice donuts (e.g. one-category income) draw as a plain
            // closed ring — rounded corners on a 360° wedge would meet at the
            // start/end and create a small notch artifact at 12 o'clock.
            // Skip the special case during the reveal sweep so it picks up
            // the partial arc from the generic path below instead of popping
            // in fully-drawn.
            val nonEmpty = entries.filter { it.anim.value > 0.01f }
            if (nonEmpty.size == 1 && progress >= 1f) {
                val ringPath = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addOval(Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR))
                    addOval(Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR))
                }
                drawPath(ringPath, color = nonEmpty[0].slice.color)
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
            var consumedAngle = 0f
            entries.forEach { entry ->
                val v = entry.anim.value
                if (v <= 0f) return@forEach
                val sweepAngle = (v / total) * 360f
                // Clip the slice's sweep against the reveal cap so partially
                // exposed slices draw only their leading portion.
                val allowed = (maxAngleFromStart - consumedAngle).coerceAtLeast(0f)
                val clipped = sweepAngle.coerceAtMost(allowed)
                if (clipped > 0.5f) {
                    val drawSweep = (clipped - gapDeg).coerceAtLeast(0.5f)
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
                    drawPath(path, color = entry.slice.color)
                }
                startAngle += sweepAngle
                consumedAngle += sweepAngle
            }
        }

        // On-slice icons. No background chip — just the icon (white tinted
        // for builtin vectors, natural colours for user-uploaded customs)
        // anchored at the arc midline. Icons track the animated slice angle
        // so they slide with their wedge during transitions; during the
        // reveal sweep they fade in once the sweep crosses the slice's
        // midline so they don't sit on top of un-drawn wedges.
        val totalAnim = entries.sumOf { it.anim.value.toDouble() }.toFloat()
        val sweepCap = 360f * sweepProgress.value
        if (totalAnim > 0f) {
            var cum = 0f
            for (entry in entries) {
                val v = entry.anim.value
                if (v <= 0f) continue
                val pct = v / totalAnim * 100f
                val builtin = categoryIcon(entry.slice.iconKey)
                val hasIcon = builtin != null || entry.slice.customIconUrl != null
                // Fade icon in/out across the visibility threshold so it
                // doesn't pop on/off mid-animation as a slice grows or
                // shrinks past MIN_ICON_PCT.
                val pctAlpha = ((pct - MIN_ICON_PCT * 0.6f) / (MIN_ICON_PCT * 0.4f))
                    .coerceIn(0f, 1f)
                // During the sweep, only show icons of slices whose midline
                // has already been revealed (cum + v/2 is the slice midline
                // measured clockwise from -90°). After the sweep finishes
                // sweepCap == 360f so the gate opens for every slice.
                val sliceMid = (cum + v / 2f) / totalAnim * 360f
                val sweepAlpha = if (sliceMid <= sweepCap) 1f else 0f
                val iconAlpha = pctAlpha * sweepAlpha
                if (iconAlpha > 0f && hasIcon) {
                    val midRad = ((cum + v / 2f) / totalAnim * 2f * Math.PI - Math.PI / 2f).toFloat()
                    val xPx = cos(midRad) * midRadiusPx
                    val yPx = sin(midRad) * midRadiusPx
                    val xDp = with(density) { xPx.toDp() }
                    val yDp = with(density) { yPx.toDp() }
                    val iconMod = Modifier
                        .offset(x = xDp, y = yDp)
                        .size(16.dp)
                        .alpha(iconAlpha)
                    if (builtin != null) {
                        Icon(
                            imageVector = builtin,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = iconMod,
                        )
                    } else {
                        AsyncImage(
                            model = entry.slice.customIconUrl,
                            contentDescription = null,
                            modifier = iconMod,
                        )
                    }
                }
                cum += v
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
            val hasLimit = slice.limitTotal != null && slice.limitTotal > 0
            val containerModifier = if (onToggle != null) {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggle(slice.label) }
            } else {
                Modifier.fillMaxWidth()
            }
            // Row-level (not Column-level) so `verticalAlignment = CenterVertically`
            // centres the badge across the *entire* item (main row + optional
            // limit row), not just the main row. Mirrors the CategoryLimitsScreen
            // / web CategoryDonutChart fix — when a slice has a limit the
            // badge would otherwise anchor to the top.
            Row(
                modifier = containerModifier.padding(vertical = 2.dp),
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
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                                            .background(
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.22f),
                                            ),
                                    )
                                } else {
                                    Text(
                                        text = "%.0f ₽".format(slice.value),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                            .copy(alpha = if (isHidden) 0.4f else 1f),
                                        textDecoration =
                                        if (isHidden) TextDecoration.LineThrough else null,
                                    )
                                }
                            }
                        } else {
                            val pct = if (total > 0f) slice.value / total * 100f else 0f
                            Text(
                                text = if (pct < 10f) "%.1f%%".format(pct) else "%.0f%%".format(pct),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = if (isHidden) 0.4f else 1f),
                                textDecoration =
                                if (isHidden) TextDecoration.LineThrough else null,
                            )
                        }
                    }
                    // Limit progress row — sits under the main line. Trailing
                    // text shows only the *cap* (e.g. «50 000 ₽») — the spent
                    // value is already on the row above, and the percent is
                    // already encoded in the bar's fill.
                    if (hasLimit && slice.limitTotal != null) {
                        val limit = slice.limitTotal
                        val tint = when {
                            slice.limitPercent >= 100.0 -> Color(0xFFEF4444)
                            slice.limitPercent >= 80.0 -> Color(0xFFF59E0B)
                            else -> Color(0xFF22C55E)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OverlayProgress(
                                fraction = (slice.limitPercent / 100.0).toFloat(),
                                color = tint,
                                trackColor = tint.copy(alpha = 0.16f),
                                height = 5.dp,
                                modifier = Modifier.weight(1f),
                            )
                            // Hide the cap text via alpha (NOT shrinkHorizontally)
                            // so the row layout stays invariant — `shrinkHorizontally`
                            // collapses the text's width to 0 and the bar's
                            // `weight(1f)` then expands rightward, which made
                            // legend rows jump between hidden / visible states.
                            // alpha keeps the slot reserved; the bar's right edge
                            // stays put across both modes.
                            val capAlpha by animateFloatAsState(
                                targetValue = if (valuesHidden) 0f else 1f,
                                animationSpec = tween(220),
                                label = "legendLimitAlpha",
                            )
                            Text(
                                text = "%.0f ₽".format(limit),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alpha(capAlpha),
                            )
                        }
                    }
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

// ── Previews ─────────────────────────────────────────────────────────────────
// Mirror the StatisticsScreen layout: two Cards stacked, expense donut with
// limit progress bars on a couple of slices, single-source income donut. The
// `LocalInspectionMode` guard inside `DonutChart` keeps `sweepProgress` at
// 1f for these renders — the animation clock doesn't tick in the inspector,
// so without it the slices would stay collapsed at 0.

private fun previewExpenseSlices(): List<PieSlice> = listOf(
    PieSlice(
        label = "Одежда",
        value = 13540f,
        color = Color(0xFFEC4899),
        iconKey = "shirt",
    ),
    PieSlice(
        label = "OZON",
        value = 100000f,
        color = Color(0xFF1E40AF),
        iconKey = "cart",
        // Mirrors the screenshot: 12 326 / 50 000 (25%). Green tint, well
        // under 80% so the limit bar stays in the safe band.
        limitTotal = 50000.0,
        limitSpent = 100000.0,
        limitPercent = 200.00,
    ),
    PieSlice(
        label = "Здоровье",
        value = 11500f,
        color = Color(0xFFEF4444),
        iconKey = "medkit",
    ),
    PieSlice(
        label = "Wildberries",
        value = 90000f,
        color = Color(0xFF8B5CF6),
        // No exact wb glyph in the built-in set; use phone-portrait as a
        // stand-in. On a real device the admin uploads a custom "wb" SVG
        // which now tints with the category color (post-1.37.1).
        iconKey = "phone-portrait",
        limitTotal = 100000.0,
        limitSpent = 90000.0,
        limitPercent = 90.0,
    ),
    PieSlice(
        label = "Прочее",
        value = 3900f,
        color = Color(0xFF64748B),
        iconKey = "ellipsis-horizontal",
    ),
)

private fun previewIncomeSlices(): List<PieSlice> = listOf(
    PieSlice(
        label = "Выплата",
        value = 68901f,
        // Same magenta the screenshot uses for the lone income source.
        color = Color(0xFFDB2777),
        iconKey = "cash",
    ),
)

@Preview(
    name = "Statistics — Light",
    showBackground = true,
    backgroundColor = 0xFFF6F7F9,
    heightDp = 760,
)
@Preview(
    name = "Statistics — Dark",
    showBackground = true,
    backgroundColor = 0xFF111418,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    heightDp = 760,
)
@Composable
private fun StatisticsDonutsPreview() {
    // BudgetTheme isn't theme-key-aware in previews — we just need its
    // CompositionLocals (LocalIncomeColor / LocalExpenseColor) wired up so
    // the donut + legend pick up the same palette the live screen uses.
    website.msdnna.budget_app.ui.theme.BudgetTheme(isDark = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Расходы по категориям",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        CategoryDonut(
                            allSlices = previewExpenseSlices(),
                            centerText = "Расходы",
                            pieUnitRuble = true,
                            // Generous max-height so all 5 legend rows fit
                            // inside the preview without scrolling.
                            legendMaxHeight = 320.dp,
                        )
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Доходы по источникам",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        CategoryDonut(
                            allSlices = previewIncomeSlices(),
                            centerText = "Доходы",
                            pieUnitRuble = true,
                        )
                    }
                }
            }
        }
    }
}
