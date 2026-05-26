// Pastel tints used to visually link a parent transaction to its children
// (split-income groups + detail-request groups). Mirrors Android's
// `ui/components/GroupColors.kt` palette + FNV-1a hash so the same group
// hashes to the same slot across web/mobile.
export const GROUP_TINTS = [
  '#B39DDB', // lavender
  '#FFB74D', // amber
  '#81C784', // green
  '#4FC3F7', // cyan
  '#E57373', // coral
  '#FFD54F', // gold
  '#A1887F', // taupe
  '#F06292', // pink
  '#7986CB', // indigo
  '#AED581', // lime
  '#4DD0E1', // teal
  '#FF8A65', // peach
]

/** Stable group key for a transaction. Children point to their parent;
 *  split-parents and DR-parents use their own id (so the parent shares the
 *  tint with its children when surfaced via the «show split/closed»
 *  toggles). Returns null when the row isn't part of any group. */
export function groupKey(row) {
  if (row.parent_id) return row.parent_id
  // Split-parent: income + excluded + no DR.
  if (row.type === 'income' && row.excluded_from_stats && !row.detail_request_status) {
    return row.id
  }
  // DR-parent: closed DR.
  if (row.detail_request_status === 'closed') return row.id
  return null
}

// FNV-1a — see `GroupColors.kt`. The polynomial-31 version we had collapsed
// UUIDs onto the same bytes; FNV-1a mixes every char into the high bits.
function tintIndex(key) {
  let h = -2128831035 // 0x811c9dc5 as signed 32-bit
  for (let i = 0; i < key.length; i++) {
    h = Math.imul(h ^ key.charCodeAt(i), 16777619)
  }
  return ((h % GROUP_TINTS.length) + GROUP_TINTS.length) % GROUP_TINTS.length
}

/** CSS class name for the row's group, or null if no group. CSS rules under
 *  `.tx-grp-N` paint the cell/card backgrounds — necessary on desktop where
 *  Naive's td background overrides row inline-style. */
export function groupClass(row) {
  const key = groupKey(row)
  if (key == null) return null
  return `tx-grp-${tintIndex(key)}`
}

/** Composite the group tint over an opaque base (default white) and return a
 *  solid hex. Mobile cards need this — semi-transparent backgrounds bleed
 *  through during swipe (revealing the action rail). 0.16 alpha matches the
 *  Android `groupTint(alpha=0.16f)` look. */
export function groupSolidBg(row, base = '#FFFFFF', alpha = 0.16) {
  const key = groupKey(row)
  if (key == null) return null
  const tint = GROUP_TINTS[tintIndex(key)]
  const tr = parseInt(tint.slice(1, 3), 16)
  const tg = parseInt(tint.slice(3, 5), 16)
  const tb = parseInt(tint.slice(5, 7), 16)
  const br = parseInt(base.slice(1, 3), 16)
  const bg = parseInt(base.slice(3, 5), 16)
  const bb = parseInt(base.slice(5, 7), 16)
  const r = Math.round(tr * alpha + br * (1 - alpha))
  const g = Math.round(tg * alpha + bg * (1 - alpha))
  const b = Math.round(tb * alpha + bb * (1 - alpha))
  const hx = (n) => n.toString(16).padStart(2, '0')
  return `#${hx(r)}${hx(g)}${hx(b)}`
}

/** @deprecated Use [groupClass] for desktop tables (Naive overrides row inline
 *  style) and [groupSolidBg] for opaque mobile cards. Kept temporarily for
 *  back-compat. */
export function groupTint(row, alphaHex = '26') {
  const key = groupKey(row)
  if (!key) return null
  return GROUP_TINTS[tintIndex(key)] + alphaHex
}
