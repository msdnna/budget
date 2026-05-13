// Shared category icon + color dictionary.
//
// `icon` keys here mirror the Android dictionary at
// android/app/src/main/java/website/msdnna/budget_app/ui/icons/CategoryIcons.kt.
// Keys are stored on Category.icon in the backend; clients translate them
// to their native icon component. Keep both files in sync.

import {
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
} from '@vicons/ionicons5'

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

// Picker grid order — drives the icon-picker UI in the (upcoming) admin page.
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

export function categoryIcon(key) {
  return CATEGORY_ICONS[key] || PricetagOutline
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
