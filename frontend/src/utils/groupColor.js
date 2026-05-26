// Pastel tints used to visually link a parent transaction to its children
// (split-income groups + detail-request groups). Mirrors Android's
// `ui/components/GroupColors.kt` palette + algorithm so the same group hashes
// to the same slot across web/mobile.
const GROUP_TINTS = [
  '#B39DDB', // lavender
  '#FFB74D', // amber
  '#81C784', // green
  '#4FC3F7', // cyan
  '#E57373', // coral
  '#FFD54F', // gold
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

function tintIndex(key) {
  let h = 0
  for (let i = 0; i < key.length; i++) h = h * 31 + key.charCodeAt(i)
  return ((h % GROUP_TINTS.length) + GROUP_TINTS.length) % GROUP_TINTS.length
}

/** Returns a hex tint for the row's group, or null if no group. Alpha
 *  applied as an 8-bit suffix so callers can paint via `background:` directly. */
export function groupTint(row, alphaHex = '26') {
  const key = groupKey(row)
  if (!key) return null
  return GROUP_TINTS[tintIndex(key)] + alphaHex
}
