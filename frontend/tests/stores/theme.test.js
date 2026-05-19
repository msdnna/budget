import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore, COLOR_THEMES } from '../../src/stores/theme.js'

describe('theme store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('defaults to the blue theme on first launch', () => {
    const t = useThemeStore()
    expect(t.activeTheme.key).toBe('blue')
    expect(t.isDark).toBe(false)
  })

  it('restores the saved theme key from localStorage', () => {
    localStorage.setItem('budget-theme', 'green')
    setActivePinia(createPinia())
    const t = useThemeStore()
    expect(t.activeTheme.key).toBe('green')
  })

  it('falls back to blue if the saved key is unknown', () => {
    localStorage.setItem('budget-theme', 'mystery')
    setActivePinia(createPinia())
    const t = useThemeStore()
    expect(t.activeTheme.key).toBe('blue')
  })

  it('selectTheme persists the new key', () => {
    const t = useThemeStore()
    const purple = COLOR_THEMES.find((x) => x.key === 'purple')
    t.selectTheme(purple)
    expect(t.activeTheme.key).toBe('purple')
    expect(localStorage.getItem('budget-theme')).toBe('purple')
  })

  it('toggleDarkMode flips and persists isDark', () => {
    const t = useThemeStore()
    expect(t.isDark).toBe(false)
    t.toggleDarkMode()
    expect(t.isDark).toBe(true)
    expect(localStorage.getItem('budget-dark-mode')).toBe('true')
    t.toggleDarkMode()
    expect(localStorage.getItem('budget-dark-mode')).toBe('false')
  })

  it('toggleValuesHidden persists the hidden flag', () => {
    const t = useThemeStore()
    t.toggleValuesHidden()
    expect(t.valuesHidden).toBe(true)
    expect(localStorage.getItem('budget-values-hidden')).toBe('true')
  })

  it('togglePieChartUnit rotates between percent and ruble', () => {
    const t = useThemeStore()
    expect(t.pieChartUnit).toBe('percent')
    t.togglePieChartUnit()
    expect(t.pieChartUnit).toBe('ruble')
    t.togglePieChartUnit()
    expect(t.pieChartUnit).toBe('percent')
  })

  it('chartColors palette produces 8 distinct hex strings', () => {
    const t = useThemeStore()
    const colors = t.chartColors
    expect(colors).toHaveLength(8)
    expect(new Set(colors).size).toBe(8)
    for (const c of colors) {
      expect(c).toMatch(/^#[0-9a-f]{6}$/)
    }
  })

  it('themeOverrides exposes common + Button + Radio (text-on-primary) in light mode', () => {
    // Button + Radio overrides несут принудительный textColor*, считаемый по
    // luminance активного primary-цвета — чтобы выбранный n-radio-button
    // оставался читаемым на оранжевом/бирюзовом так же, как и primary-кнопка.
    const t = useThemeStore()
    expect(Object.keys(t.themeOverrides).sort()).toEqual(['Button', 'Radio', 'common'])
    expect(t.themeOverrides.common.primaryColor).toBe(t.activeTheme.primary)
    expect(t.themeOverrides.Button.textColorPrimary).toMatch(/^#(?:fff|ffffff|1f1f1f)$/)
    expect(t.themeOverrides.Radio.buttonTextColorActive).toBe(
      t.themeOverrides.Button.textColorPrimary,
    )
    expect(t.themeOverrides.Radio.buttonColorActive).toBe(t.activeTheme.primary)
  })

  it('themeOverrides includes Naive component overrides in dark mode', () => {
    const t = useThemeStore()
    t.toggleDarkMode()
    expect(t.themeOverrides.Card).toBeDefined()
    expect(t.themeOverrides.DataTable).toBeDefined()
  })
})
