package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Continuous-track progress bar — the filled bar overlays a single
 * uninterrupted track instead of sitting beside it. The Material 3
 * [androidx.compose.material3.LinearProgressIndicator] reads as «two
 * separate pills» because both halves get rounded caps, leaving a visible
 * notch in the middle even with `gapSize = 0.dp`. For pure status bars
 * (no animation semantics needed) this Box-overlay is cleaner.
 */
@Composable
fun OverlayProgress(
    fraction: Float,
    color: Color,
    trackColor: Color,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val radius = height / 2
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(trackColor),
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(clamped)
                    .clip(RoundedCornerShape(radius))
                    .background(color),
            )
        }
    }
}
