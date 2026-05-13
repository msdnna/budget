// Shared category icon + color dictionary.
//
// The curated `CATEGORY_ICONS` map below mirrors the Android dictionary at
// android/app/src/main/java/website/msdnna/budget_app/ui/icons/CategoryIcons.kt
// (kebab-case keys for cross-platform sync). The full @vicons/ionicons5
// catalog is also re-exported as a flat namespace so the web admin can
// search through every available glyph (~1300) without us hand-curating
// the entire pack. Storage on Category.icon accepts either:
//   - a kebab-case key from CATEGORY_ICONS (e.g. "cart") — used by the
//     seed and the cross-platform default categories;
//   - a PascalCase ionicons name (e.g. "BicycleSharp") — used when an
//     admin picks something via the dropdown search;
//   - "custom:<uuid>" — references a user-uploaded PNG/SVG icon.

import * as Ionicons5 from '@vicons/ionicons5'

const {
  Cart,
  Car,
  Home,
  Restaurant,
  GameController,
  Medkit,
  School,
  Shirt,
  PhonePortrait,
  Airplane,
  Call,
  Rose,
  Barbell,
  EllipsisHorizontal,
  Cash,
  Briefcase,
  TrendingUp,
  Gift,
  Key,
  Desktop,
  Flame,
  SwapHorizontal,
  Wallet,
  FastFood,
  Cafe,
  BagHandle,
  PricetagOutline,
} = Ionicons5

export const CATEGORY_ICONS = {
  cart: Cart,
  car: Car,
  home: Home,
  restaurant: Restaurant,
  'game-controller': GameController,
  medkit: Medkit,
  school: School,
  shirt: Shirt,
  'phone-portrait': PhonePortrait,
  airplane: Airplane,
  call: Call,
  rose: Rose,
  barbell: Barbell,
  'ellipsis-horizontal': EllipsisHorizontal,
  cash: Cash,
  briefcase: Briefcase,
  'trending-up': TrendingUp,
  gift: Gift,
  key: Key,
  desktop: Desktop,
  flame: Flame,
  'swap-horizontal': SwapHorizontal,
  wallet: Wallet,
  'fast-food': FastFood,
  cafe: Cafe,
  'bag-handle': BagHandle,
  tag: PricetagOutline,
}

// Default seed for the recently-used picker grid in the admin page.
export const CATEGORY_ICON_ORDER = [
  'cart',
  'car',
  'home',
  'restaurant',
  'fast-food',
  'cafe',
  'game-controller',
  'medkit',
  'school',
  'shirt',
  'phone-portrait',
  'airplane',
  'call',
  'rose',
  'barbell',
  'cash',
  'briefcase',
  'trending-up',
  'gift',
  'key',
  'desktop',
  'flame',
  'swap-horizontal',
  'wallet',
  'bag-handle',
  'tag',
  'ellipsis-horizontal',
]

// Fallback palette for user-created categories without an explicit color.
// Mirrors backend repository/category_repo.go presets and Android
// CategoryIcons.kt FALLBACK_COLORS.
export const FALLBACK_PALETTE = [
  '#22C55E',
  '#3B82F6',
  '#F59E0B',
  '#8B5CF6',
  '#EF4444',
  '#0EA5E9',
  '#EC4899',
  '#6366F1',
  '#14B8A6',
  '#A855F7',
  '#F97316',
  '#F472B6',
  '#10B981',
  '#64748B',
]

// Sorted list of every PascalCase ionicons name — drives the admin
// dropdown search. Defaults like `default` and module metadata are
// filtered out (lowercase-leading or symbol-only).
export const ICON_NAMES = Object.keys(Ionicons5)
  .filter((k) => /^[A-Z][A-Za-z0-9]*$/.test(k))
  .sort()

// Reverse map: ionicons5 PascalCase name → curated kebab-case key, when
// the underlying component is the same. Lets `bumpRecent()` collapse
// duplicates so users picking "Cart" via search don't end up with both
// "cart" and "Cart" tiles in the grid.
const PASCAL_TO_KEBAB = (() => {
  const out = {}
  for (const [key, comp] of Object.entries(CATEGORY_ICONS)) {
    for (const name of ICON_NAMES) {
      if (Ionicons5[name] === comp) {
        out[name] = key
        break
      }
    }
  }
  return out
})()

// Returns the Vue icon component for a stored key:
//   - empty / null / `custom:` → null (caller renders bare badge or img)
//   - kebab-case curated key → CATEGORY_ICONS[key]
//   - PascalCase ionicons name → Ionicons5[key]
//   - anything else → null
export function categoryIcon(key) {
  if (!key || typeof key !== 'string') return null
  if (key.startsWith('custom:')) return null
  return CATEGORY_ICONS[key] || Ionicons5[key] || null
}

// 'builtin' — any curated or ionicons key that resolves;
// 'custom'  — server-side uploaded icon (`custom:<id>`);
// 'none'    — no icon set, render only the colored badge.
export function iconKind(key) {
  if (!key || typeof key !== 'string') return 'none'
  if (key.startsWith('custom:')) return 'custom'
  if (CATEGORY_ICONS[key] || Ionicons5[key]) return 'builtin'
  return 'none'
}

// Normalises an ionicons PascalCase name to its curated kebab-case key
// when one exists, otherwise returns the input unchanged. Used by the
// picker before persisting to the recently-used list.
export function normalizeIconKey(key) {
  if (!key || typeof key !== 'string') return key
  return PASCAL_TO_KEBAB[key] || key
}

// Stable color for a category name when no explicit color is stored.
// Hash → palette index; the same name always maps to the same swatch.
export function fallbackColorFor(name) {
  if (!name) return FALLBACK_PALETTE[0]
  let h = 0
  for (let i = 0; i < name.length; i++) {
    h = (h * 31 + name.charCodeAt(i)) | 0
  }
  return FALLBACK_PALETTE[Math.abs(h) % FALLBACK_PALETTE.length]
}

export function resolveCategoryColor(category) {
  if (category?.color) return category.color
  return fallbackColorFor(category?.name)
}
