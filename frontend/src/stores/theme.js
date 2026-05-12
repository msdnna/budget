import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { darkTheme } from 'naive-ui'
import { DARK, LIGHT } from '@/styles/tokens'

export const COLOR_THEMES = [
  {
    name: 'Синий',
    key: 'blue',
    primary: '#2080f0',
    hover: '#4098fc',
    pressed: '#1060c9',
    suppl: '#4098fc',
  },
  {
    name: 'Зелёный',
    key: 'green',
    primary: '#18a058',
    hover: '#36ad6a',
    pressed: '#0c7a43',
    suppl: '#36ad6a',
  },
  {
    name: 'Красный',
    key: 'red',
    primary: '#d03050',
    hover: '#de576b',
    pressed: '#ab1f3f',
    suppl: '#e06080',
  },
  {
    name: 'Оранжевый',
    key: 'orange',
    primary: '#f0a020',
    hover: '#fcb040',
    pressed: '#c97c10',
    suppl: '#fcb040',
  },
  {
    name: 'Фиолетовый',
    key: 'purple',
    primary: '#722ed1',
    hover: '#9254de',
    pressed: '#531dab',
    suppl: '#9254de',
  },
  {
    name: 'Бирюзовый',
    key: 'teal',
    primary: '#0eb0a9',
    hover: '#36b8b3',
    pressed: '#07877f',
    suppl: '#36b8b3',
  },
  {
    name: 'Розовый',
    key: 'pink',
    primary: '#eb2f96',
    hover: '#f759ab',
    pressed: '#c41d7f',
    suppl: '#f759ab',
  },
]

// ── HSL helpers ──────────────────────────────────────────────────
function hexToHsl(hex) {
  let r = parseInt(hex.slice(1, 3), 16) / 255
  let g = parseInt(hex.slice(3, 5), 16) / 255
  let b = parseInt(hex.slice(5, 7), 16) / 255
  const max = Math.max(r, g, b),
    min = Math.min(r, g, b)
  let h, s
  const l = (max + min) / 2
  if (max === min) {
    h = s = 0
  } else {
    const d = max - min
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
    switch (max) {
      case r:
        h = ((g - b) / d + (g < b ? 6 : 0)) / 6
        break
      case g:
        h = ((b - r) / d + 2) / 6
        break
      case b:
        h = ((r - g) / d + 4) / 6
        break
    }
  }
  return [h * 360, s * 100, l * 100]
}

function hslToHex(h, s, l) {
  h /= 360
  s /= 100
  l /= 100
  const hue2rgb = (p, q, t) => {
    if (t < 0) t += 1
    if (t > 1) t -= 1
    if (t < 1 / 6) return p + (q - p) * 6 * t
    if (t < 1 / 2) return q
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6
    return p
  }
  let r, g, b
  if (s === 0) {
    r = g = b = l
  } else {
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s
    const p = 2 * l - q
    r = hue2rgb(p, q, h + 1 / 3)
    g = hue2rgb(p, q, h)
    b = hue2rgb(p, q, h - 1 / 3)
  }
  const toHex = (x) =>
    Math.round(x * 255)
      .toString(16)
      .padStart(2, '0')
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`
}

function generatePalette(primaryHex, count = 8) {
  const [h, s] = hexToHsl(primaryHex)
  const sat = Math.min(Math.max(s, 52), 88)
  return Array.from({ length: count }, (_, i) => {
    const l = 22 + (i / (count - 1)) * 50
    return hslToHex(h, sat, l)
  })
}

// ── Store ────────────────────────────────────────────────────────
export const useThemeStore = defineStore('theme', () => {
  const savedKey = localStorage.getItem('budget-theme') || 'blue'
  const activeTheme = ref(COLOR_THEMES.find((t) => t.key === savedKey) ?? COLOR_THEMES[0])
  const isDark = ref(localStorage.getItem('budget-dark-mode') === 'true')

  const primaryColor = computed(() => activeTheme.value.primary)
  const chartColors = computed(() => generatePalette(activeTheme.value.primary))
  const palette = computed(() => (isDark.value ? DARK : LIGHT))

  // The Naive UI theme object (null = light)
  const naiveTheme = computed(() => (isDark.value ? darkTheme : null))

  // All Naive UI component overrides — single source of truth
  const themeOverrides = computed(() => {
    const p = activeTheme.value
    const common = {
      primaryColor: p.primary,
      primaryColorHover: p.hover,
      primaryColorPressed: p.pressed,
      primaryColorSuppl: p.suppl,
    }

    if (!isDark.value) {
      return { common }
    }

    const d = DARK
    return {
      common: {
        ...common,
        textColor1: d.text1,
        textColor2: d.text2,
        textColor3: d.text3,
        textColorPlaceholder: d.placeholder,
        borderColor: d.border,
        cardBorderColor: d.border,
      },
      Card: {
        borderColor: d.border,
        titleTextColor: d.text1,
      },
      Button: {
        colorQuaternary: d.surfaceAlt,
      },
      Layout: {
        color: d.bg,
        colorHeader: d.surface,
        borderColor: d.border,
      },
      Sider: { color: d.surface },
      Menu: {
        color: 'transparent',
        colorItemHover: d.hover,
        colorItemActive: d.hover,
        textColor: d.text2,
        textColorActive: d.text1,
        textColorCollapsed: d.text2,
        borderRadius: '8px',
      },
      Popover: { color: d.surfaceAlt },
      Tooltip: { color: d.surfaceAlt },
      Input: {
        color: d.surfaceAlt,
        colorFocus: d.surfaceAlt,
        colorDisabled: d.surface,
        borderColor: d.border,
        borderColorFocus: p.primary,
        placeholderColor: d.placeholder,
        textColor: d.text2,
      },
      Select: {
        peers: {
          InternalSelection: {
            color: d.surfaceAlt,
            colorActive: d.surfaceAlt,
            colorDisabled: d.surface,
            border: `1px solid ${d.border}`,
            borderHover: `1px solid ${p.primary}`,
            borderActive: `1px solid ${p.primary}`,
            borderFocus: `1px solid ${p.primary}`,
            textColor: d.text2,
            placeholderColor: d.placeholder,
            arrowColor: d.text3,
            arrowColorDisabled: d.placeholder,
          },
        },
      },
      InternalSelectMenu: {
        color: d.surfaceAlt,
        optionTextColor: d.text2,
        optionTextColorActive: d.text1,
        optionColorPending: d.hover,
        optionColorActivePending: d.hover,
        borderRadius: '6px',
      },
      DatePicker: {
        color: d.surfaceAlt,
        colorFocus: d.surfaceAlt,
        colorDisabled: d.surface,
        borderColor: d.border,
        borderColorFocus: p.primary,
        placeholderColor: d.placeholder,
        textColor: d.text2,
        panelColor: d.surface,
        panelHeaderDividerColor: d.border,
        panelActionDividerColor: d.border,
        calendarTitleTextColor: d.text2,
        calendarDayTextColor: d.text3,
        itemTextColor: d.text2,
        itemTextColorDisabled: d.placeholder,
        itemColorHover: d.hover,
      },
      DataTable: {
        color: d.surface,
        colorHover: d.surfaceAlt,
        colorTargetRow: d.surfaceAlt,
        borderColor: d.border,
        thColor: d.surface,
        thTextColor: d.text3,
        tdTextColor: d.text2,
        tdPaddingLeft: '12px',
        tdPaddingRight: '12px',
      },
      Pagination: {
        itemColor: d.surface,
        itemColorHover: d.surfaceAlt,
        itemColorActive: p.primary,
        itemBorderColor: d.border,
        itemTextColor: d.text2,
        itemTextColorHover: d.text1,
        itemTextColorActive: '#ffffff',
      },
      Tag: { colorBorder: d.border },
      FormItem: { feedbackHeight: '20px' },
      Switch: { railColorActive: p.primary },
      Divider: { color: d.border },
      Radio: {
        colorChecked: p.primary,
        borderColorChecked: p.primary,
      },
      Popconfirm: { color: d.surfaceAlt },
      List: { color: 'transparent' },
      Scrollbar: {
        color: d.surfaceAlt,
        colorRail: d.border,
      },
      Tree: { color: d.surface },
      Alert: {
        color: d.surfaceAlt,
        borderColor: d.border,
        titleTextColor: d.text2,
        contentTextColor: d.text3,
      },
      Modal: {
        color: d.surface,
        boxShadow: '0 8px 32px rgba(0,0,0,0.6)',
      },
      Dialog: {
        color: d.surface,
        textColor: d.text2,
        titleTextColor: d.text1,
      },
    }
  })

  function selectTheme(t) {
    activeTheme.value = t
    localStorage.setItem('budget-theme', t.key)
  }

  function toggleDarkMode() {
    isDark.value = !isDark.value
    localStorage.setItem('budget-dark-mode', String(isDark.value))
  }

  const valuesHidden = ref(localStorage.getItem('budget-values-hidden') === 'true')

  function toggleValuesHidden() {
    valuesHidden.value = !valuesHidden.value
    localStorage.setItem('budget-values-hidden', String(valuesHidden.value))
  }

  const pieChartUnit = ref(localStorage.getItem('budget-pie-unit') || 'percent')

  function togglePieChartUnit() {
    pieChartUnit.value = pieChartUnit.value === 'percent' ? 'ruble' : 'percent'
    localStorage.setItem('budget-pie-unit', pieChartUnit.value)
  }

  watch(
    isDark,
    (dark) => {
      if (dark) {
        document.documentElement.setAttribute('data-theme', 'dark')
      } else {
        document.documentElement.removeAttribute('data-theme')
      }
    },
    { immediate: true },
  )

  return {
    activeTheme,
    isDark,
    primaryColor,
    chartColors,
    palette,
    naiveTheme,
    themeOverrides,
    selectTheme,
    toggleDarkMode,
    valuesHidden,
    toggleValuesHidden,
    pieChartUnit,
    togglePieChartUnit,
  }
})
