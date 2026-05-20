import { describe, it, expect } from 'vitest'
import {
  CATEGORY_ICONS,
  CATEGORY_ICON_ORDER,
  FALLBACK_PALETTE,
  ICON_NAMES,
  categoryIcon,
  iconKind,
  normalizeIconKey,
  fallbackColorFor,
  resolveCategoryColor,
} from '../../src/utils/categoryIcons.js'

describe('categoryIcons — static exports', () => {
  it('CATEGORY_ICONS map has all curated kebab-case keys', () => {
    expect(CATEGORY_ICONS.cart).toBeDefined()
    expect(CATEGORY_ICONS['fast-food']).toBeDefined()
    expect(CATEGORY_ICONS['ellipsis-horizontal']).toBeDefined()
    // ordered list lives parallel to the map — sanity check parity.
    for (const key of CATEGORY_ICON_ORDER) {
      expect(CATEGORY_ICONS[key], `missing curated icon: ${key}`).toBeDefined()
    }
  })

  it('FALLBACK_PALETTE is non-empty hex array', () => {
    expect(FALLBACK_PALETTE.length).toBeGreaterThan(0)
    for (const c of FALLBACK_PALETTE) {
      expect(c).toMatch(/^#[0-9A-F]{6}$/)
    }
  })

  it('ICON_NAMES is sorted PascalCase only', () => {
    expect(ICON_NAMES.length).toBeGreaterThan(100)
    expect(ICON_NAMES).toEqual([...ICON_NAMES].sort())
    expect(ICON_NAMES.every((n) => /^[A-Z][A-Za-z0-9]*$/.test(n))).toBe(true)
  })
})

describe('categoryIcon()', () => {
  it('returns the component for a curated kebab key', () => {
    expect(categoryIcon('cart')).toBe(CATEGORY_ICONS.cart)
  })

  it('returns the component for a PascalCase ionicons name', () => {
    const name = ICON_NAMES.find((n) => n.length < 16)
    expect(categoryIcon(name)).toBeDefined()
  })

  it('returns null for empty / non-string / custom: keys', () => {
    expect(categoryIcon('')).toBeNull()
    expect(categoryIcon(null)).toBeNull()
    expect(categoryIcon(undefined)).toBeNull()
    expect(categoryIcon(42)).toBeNull()
    expect(categoryIcon('custom:abc')).toBeNull()
  })

  it('returns null for unknown keys', () => {
    expect(categoryIcon('definitely-not-a-real-icon')).toBeNull()
  })
})

describe('iconKind()', () => {
  it('classifies builtin / custom / none correctly', () => {
    expect(iconKind('cart')).toBe('builtin')
    expect(iconKind(ICON_NAMES[0])).toBe('builtin')
    expect(iconKind('custom:abc')).toBe('custom')
    expect(iconKind('custom:')).toBe('custom') // even empty-payload custom is still custom
    expect(iconKind('')).toBe('none')
    expect(iconKind(null)).toBe('none')
    expect(iconKind('definitely-not-a-real-icon')).toBe('none')
  })
})

describe('normalizeIconKey()', () => {
  it('returns the input unchanged for non-mapped keys', () => {
    expect(normalizeIconKey('cart')).toBe('cart') // already curated
    expect(normalizeIconKey('Whatever')).toBe('Whatever') // unknown PascalCase
    expect(normalizeIconKey('')).toBe('')
    expect(normalizeIconKey(null)).toBeNull()
  })

  it('collapses a known PascalCase to its curated kebab key when components match', () => {
    // Cart is both in CATEGORY_ICONS ("cart") and in Ionicons5 ("Cart") with
    // the same component, so the PASCAL_TO_KEBAB lookup should fold it.
    expect(normalizeIconKey('Cart')).toBe('cart')
    expect(normalizeIconKey('Home')).toBe('home')
  })
})

describe('fallbackColorFor()', () => {
  it('returns palette[0] for empty input', () => {
    expect(fallbackColorFor('')).toBe(FALLBACK_PALETTE[0])
    expect(fallbackColorFor(null)).toBe(FALLBACK_PALETTE[0])
    expect(fallbackColorFor(undefined)).toBe(FALLBACK_PALETTE[0])
  })

  it('is deterministic — same name → same color', () => {
    expect(fallbackColorFor('Транспорт')).toBe(fallbackColorFor('Транспорт'))
    expect(fallbackColorFor('Food')).toBe(fallbackColorFor('Food'))
  })

  it('always returns a value from the palette', () => {
    for (const name of ['a', 'AB', 'Транспорт', 'Очень длинное название категории']) {
      expect(FALLBACK_PALETTE).toContain(fallbackColorFor(name))
    }
  })
})

describe('resolveCategoryColor()', () => {
  it('prefers explicit color over fallback', () => {
    expect(resolveCategoryColor({ color: '#ABCDEF', name: 'X' })).toBe('#ABCDEF')
  })

  it('falls back to deterministic color when no explicit color', () => {
    const cat = { name: 'Транспорт' }
    expect(resolveCategoryColor(cat)).toBe(fallbackColorFor('Транспорт'))
  })

  it('handles null / undefined gracefully', () => {
    expect(resolveCategoryColor(null)).toBe(FALLBACK_PALETTE[0])
    expect(resolveCategoryColor(undefined)).toBe(FALLBACK_PALETTE[0])
    expect(resolveCategoryColor({})).toBe(FALLBACK_PALETTE[0])
  })
})
