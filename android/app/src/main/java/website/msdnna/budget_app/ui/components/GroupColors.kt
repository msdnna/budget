package website.msdnna.budget_app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import website.msdnna.budget_app.data.model.Transaction

/**
 * Pastel tints used to visually link a parent transaction to its children
 * (split-income groups + detail-request groups). Each group hashes to a
 * deterministic slot so the same group keeps the same colour across renders
 * and across the Income/Expenses list.
 *
 * Values are intentionally low-saturation so they read as "this row belongs
 * to a group" without competing with the amount colour or the hidden-tint.
 * 12 slots widen the palette enough that ~5 visible groups rarely collide.
 */
private val GROUP_TINTS = listOf(
    Color(0xFFB39DDB), // lavender
    Color(0xFFFFB74D), // amber
    Color(0xFF81C784), // green
    Color(0xFF4FC3F7), // cyan
    Color(0xFFE57373), // coral
    Color(0xFFFFD54F), // gold
    Color(0xFFA1887F), // taupe
    Color(0xFFF06292), // pink
    Color(0xFF7986CB), // indigo
    Color(0xFFAED581), // lime
    Color(0xFF4DD0E1), // teal
    Color(0xFFFF8A65), // peach
)

/** Stable group key for a transaction. Children point to their parent; split-
 *  parents and DR-parents use their own id (so the parent row shares the tint
 *  with its children when surfaced via «Показывать разделённые»/«Закрытые
 *  ЗнД»). Returns null when the row isn't part of any group. */
fun Transaction.groupKey(): String? = when {
    parentId.isNotBlank() -> parentId
    // Split-parent: type=income + excluded + no DR.
    type == "income" &&
        excludedFromStats &&
        detailRequestStatus.isBlank() -> id
    // DR-parent: closed DR.
    detailRequestStatus == "closed" -> id
    else -> null
}

/** FNV-1a hash → palette slot. The polynomial-31 version we had before
 *  collapsed UUIDs onto the same final bytes (string `*31` overflows fast
 *  and the last 6 chars dominate), so visually-distinct groups ended up
 *  on the same tint. FNV-1a mixes every byte into the high bits and gives
 *  near-uniform distribution across 12 buckets for UUIDs. */
private fun tintIndex(key: String): Int {
    var h = -2128831035 // 0x811c9dc5 (FNV offset basis) as signed Int
    for (c in key) {
        h = (h xor c.code) * 16777619 // FNV prime
    }
    return ((h % GROUP_TINTS.size) + GROUP_TINTS.size) % GROUP_TINTS.size
}

/** Tint composited onto the current surface — call site uses it as the card
 *  background. Returns null when no group key is present. */
@Composable
@ReadOnlyComposable
fun Transaction.groupTint(alpha: Float = 0.16f): Color? {
    val key = groupKey() ?: return null
    val surface = MaterialTheme.colorScheme.surface
    return GROUP_TINTS[tintIndex(key)].copy(alpha = alpha).compositeOver(surface)
}
