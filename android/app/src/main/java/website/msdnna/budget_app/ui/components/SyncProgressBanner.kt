package website.msdnna.budget_app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import website.msdnna.budget_app.data.sync.SyncProgress

/**
 * Inline banner pinned above the pager whenever sync is mid-flight. Mirrors
 * the layout/spacing of [UpdateBanner] so both can coexist visually without
 * fighting for the same chrome.
 *
 * Determinate when [SyncProgress.Running.total] > 0 (transactions / wishlist
 * with known counts), indeterminate otherwise (push phase, fetch phase, or
 * empty entity).
 */
@Composable
fun SyncProgressBanner(
    progress: SyncProgress.Running,
    primaryColor: Color,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Синхронизация",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitleFor(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // gapSize = 0 + drawStopIndicator = {} → classic overlay
                // (active bar drawn on top of full-width track) instead of
                // M3's segmented look. Matches the limits-progress bar
                // style elsewhere in the app.
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        color = primaryColor,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        color = primaryColor,
                        gapSize = 0.dp,
                    )
                }
            }
        }
    }
}

private fun subtitleFor(p: SyncProgress.Running): String {
    val label = p.phase.label
    return if (p.total > 0) "$label: ${p.processed} / ${p.total}" else label
}
