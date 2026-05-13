<template>
  <div>
    <!-- Period selector -->
    <n-card style="margin-bottom: 16px">
      <n-space align="center" wrap>
        <n-text strong>Период:</n-text>
        <n-button-group>
          <n-button :type="period === 'month' ? 'primary' : 'default'" @click="setPeriod('month')">
            Месяц
          </n-button>
          <n-button :type="period === 'year' ? 'primary' : 'default'" @click="setPeriod('year')">
            Год
          </n-button>
          <n-button
            :type="period === 'custom' ? 'primary' : 'default'"
            @click="setPeriod('custom')"
          >
            Период
          </n-button>
        </n-button-group>
        <TilePeriodPicker
          v-if="period === 'month'"
          v-model:value="selectedMonth"
          type="month"
          @update:value="onPeriodValueChange"
        />
        <TilePeriodPicker
          v-if="period === 'year'"
          v-model:value="selectedYear"
          type="year"
          @update:value="onPeriodValueChange"
        />
        <n-date-picker
          v-if="period === 'custom'"
          v-model:value="dateRange"
          type="daterange"
          clearable
          @update:value="onPeriodValueChange"
        />
      </n-space>
    </n-card>

    <!-- Summary cards -->
    <n-grid
      :cols="3"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      :item-responsive="true"
      style="margin-bottom: 16px"
    >
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Доходы">
            <template #prefix><span :style="{ color: primaryColor }">↑</span></template>
            <template #default>
              <span
                :style="
                  valuesHidden
                    ? 'filter:blur(8px);user-select:none;transition:filter .25s'
                    : 'transition:filter .25s'
                "
              >
                {{ Math.round(summary.total_income).toLocaleString('ru') }}
              </span>
            </template>
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Расходы">
            <template #prefix><span :style="{ color: palette.expense }">↓</span></template>
            <template #default>
              <span
                :style="
                  valuesHidden
                    ? 'filter:blur(8px);user-select:none;transition:filter .25s'
                    : 'transition:filter .25s'
                "
              >
                {{ Math.round(summary.total_expense).toLocaleString('ru') }}
              </span>
            </template>
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Баланс">
            <template #prefix>
              <span :style="{ color: summary.balance >= 0 ? primaryColor : palette.expense }">
                {{ summary.balance >= 0 ? '+' : '−' }}
              </span>
            </template>
            <template #default>
              <span
                :style="
                  valuesHidden
                    ? 'filter:blur(8px);user-select:none;transition:filter .25s'
                    : 'transition:filter .25s'
                "
              >
                {{ Math.round(Math.abs(summary.balance)).toLocaleString('ru') }}
              </span>
            </template>
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Charts row -->
    <n-grid
      :cols="2"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      :item-responsive="true"
      style="margin-bottom: 16px"
    >
      <n-grid-item span="2 m:1">
        <n-card title="Расходы по категориям">
          <n-spin :show="loadingCharts">
            <CategoryDonutChart
              v-if="expensePieData.length"
              :data="expensePieData.map((d) => ({ category: d.category, amount: d.amount }))"
              :category-meta="categoryMetaByName"
              :palette="palette"
              :unit="pieChartUnit"
              :values-hidden="valuesHidden"
            />
            <n-empty v-else description="Нет данных" style="padding: 60px 0" />
          </n-spin>
        </n-card>
      </n-grid-item>
      <n-grid-item span="2 m:1">
        <n-card title="Доходы по источникам">
          <n-spin :show="loadingCharts">
            <CategoryDonutChart
              v-if="incomePieData.length"
              :data="incomePieData.map((d) => ({ category: d.category, amount: d.amount }))"
              :category-meta="categoryMetaByName"
              :palette="palette"
              :unit="pieChartUnit"
              :values-hidden="valuesHidden"
            />
            <n-empty v-else description="Нет данных" style="padding: 60px 0" />
          </n-spin>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Monthly bar chart -->
    <n-card title="Динамика по месяцам">
      <n-spin :show="loadingMonthly">
        <v-chart
          ref="monthlyChartRef"
          :option="monthlyBarOption"
          style="height: 320px"
          autoresize
          @brushSelected="onBrushSelected"
        />
      </n-spin>
    </n-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  BrushComponent,
  ToolboxComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import CategoryDonutChart from '@/components/CategoryDonutChart.vue'
import {
  NCard,
  NGrid,
  NGridItem,
  NStatistic,
  NSpin,
  NEmpty,
  NSpace,
  NText,
  NButton,
  NButtonGroup,
  NDatePicker,
} from 'naive-ui'
import { statistics, categories as catApi } from '@/api'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import TilePeriodPicker from '@/components/TilePeriodPicker.vue'

// ToolboxComponent is registered (without UI) because the Brush component
// depends on it internally; we hide the buttons via `toolbox.show: false`.
// PieChart is registered transitively by CategoryDonutChart's own VChart usage.
use([
  BarChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  BrushComponent,
  ToolboxComponent,
  CanvasRenderer,
])

const { primaryColor, palette, valuesHidden, pieChartUnit } = storeToRefs(useThemeStore())

const period = ref('month')
const selectedMonth = ref(Date.now())
const selectedYear = ref(Date.now())
const dateRange = ref(null)

const summary = ref({ total_income: 0, total_expense: 0, balance: 0 })
const expensePieData = ref([])
const incomePieData = ref([])
// Name → Category map used to resolve per-category color/icon for pie slices.
// Both expense + income sections are merged; collisions are vanishingly rare
// (income and expense rarely share names) and a single lookup keeps slice
// colors stable regardless of which chart they appear in.
const categoryMetaByName = ref({})
const monthlyData = ref([])
const loadingCharts = ref(false)
const loadingMonthly = ref(false)

function fmtLocalDate(ts) {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function buildParams() {
  const p = {}
  if (period.value === 'month') {
    const d = new Date(selectedMonth.value)
    p.month = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  } else if (period.value === 'year') {
    p.year = new Date(selectedYear.value).getFullYear()
  } else if (dateRange.value) {
    p.from = fmtLocalDate(dateRange.value[0])
    p.to = fmtLocalDate(dateRange.value[1])
  }
  return p
}

async function loadCategoryMetadata() {
  // Fetch once per screen mount. Category color/icon only change on admin
  // edits, so a stale cache between pie reloads is fine.
  if (Object.keys(categoryMetaByName.value).length > 0) return
  try {
    const { data } = await catApi.all()
    const merged = {}
    for (const list of [data?.expense || [], data?.income || [], data?.wishlist || []]) {
      for (const c of list) merged[c.name] = { color: c.color, icon: c.icon }
    }
    categoryMetaByName.value = merged
  } catch {
    // Best-effort — pie chart falls back to name-hashed palette colors.
  }
}

async function loadData() {
  const params = buildParams()
  loadingCharts.value = true
  try {
    const [s, exp, inc] = await Promise.all([
      statistics.summary(params),
      statistics.byCategory({ ...params, type: 'expense' }),
      statistics.byCategory({ ...params, type: 'income' }),
      loadCategoryMetadata(),
    ])
    summary.value = s.data
    expensePieData.value = exp.data || []
    incomePieData.value = inc.data || []
  } finally {
    loadingCharts.value = false
  }
}

// Build params for the monthly dynamics chart. Unlike buildParams(), the
// "month" filter is widened to a full year here so the chart still shows
// surrounding context.
function buildMonthlyParams() {
  if (period.value === 'month') {
    const d = new Date(selectedMonth.value)
    return { year: d.getFullYear() }
  }
  if (period.value === 'year') {
    return { year: new Date(selectedYear.value).getFullYear() }
  }
  if (period.value === 'custom' && dateRange.value) {
    return {
      from: fmtLocalDate(dateRange.value[0]),
      to: fmtLocalDate(dateRange.value[1]),
    }
  }
  return { year: new Date().getFullYear() }
}

async function loadMonthly() {
  loadingMonthly.value = true
  try {
    const { data } = await statistics.monthly(buildMonthlyParams())
    monthlyData.value = data || []
  } finally {
    loadingMonthly.value = false
  }
}

async function reload() {
  await Promise.all([loadData(), loadMonthly()])
}

function setPeriod(p) {
  period.value = p
  reload()
}

function onPeriodValueChange() {
  reload()
}

function tooltipStyle() {
  return {
    backgroundColor: palette.value.tooltipBg,
    borderColor: palette.value.tooltipBorder,
    textStyle: { color: palette.value.tooltipText },
  }
}

const MONTH_NAMES = [
  'Янв',
  'Фев',
  'Мар',
  'Апр',
  'Май',
  'Июн',
  'Июл',
  'Авг',
  'Сен',
  'Окт',
  'Ноя',
  'Дек',
]

const monthlyChartRef = ref(null)

function monthLabel(m) {
  // Cross-year labels include the year so users can tell adjacent years apart.
  // Single-year labels stay compact ("Янв", "Фев", …).
  const years = new Set(monthlyData.value.map((x) => x.year))
  const base = MONTH_NAMES[(m.month || 1) - 1]
  if (years.size > 1) {
    return `${base}\n${String(m.year).slice(2)}`
  }
  return base
}

const monthlyBarOption = computed(() => {
  const p = palette.value
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        if (!params?.length) return ''
        const idx = params[0].dataIndex
        const m = monthlyData.value[idx]
        if (!m) return ''
        const header = `${MONTH_NAMES[(m.month || 1) - 1]} ${m.year}`
        const rows = params
          .map((s) => {
            const val = valuesHidden.value ? '••••' : Number(s.value).toLocaleString('ru')
            return `${s.marker} ${s.seriesName}: <b>${val}</b> ₽`
          })
          .join('<br/>')
        return `${header}<br/>${rows}`
      },
      ...tooltipStyle(),
    },
    legend: {
      data: ['Доходы', 'Расходы'],
      textStyle: { color: p.chartLabel },
      show: !valuesHidden.value,
      top: 0,
    },
    toolbox: { show: false, feature: { brush: {} } },
    brush: {
      toolbox: [],
      xAxisIndex: 0,
      brushType: 'lineX',
      brushMode: 'single',
      transformable: false,
      removeOnClick: true,
      throttleType: 'debounce',
      throttleDelay: 100,
      brushStyle: { borderColor: primaryColor.value, color: 'rgba(127,127,127,0.18)' },
    },
    color: [primaryColor.value, p.expense],
    grid: { left: 50, right: 16, top: 36, bottom: 32 },
    xAxis: {
      type: 'category',
      data: monthlyData.value.map(monthLabel),
      axisLabel: { color: p.chartAxis, interval: 'auto', fontSize: 11 },
      axisLine: { lineStyle: { color: p.chartGrid } },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: valuesHidden.value ? () => '' : (v) => v.toLocaleString('ru'),
        color: p.chartAxis,
      },
      splitLine: { lineStyle: { color: p.chartGrid } },
      axisLine: { lineStyle: { color: p.chartGrid } },
    },
    series: [
      {
        name: 'Доходы',
        type: 'bar',
        data: monthlyData.value.map((m) => Math.round(m.income)),
        barMaxWidth: 32,
      },
      {
        name: 'Расходы',
        type: 'bar',
        data: monthlyData.value.map((m) => Math.round(m.expense)),
        barMaxWidth: 32,
      },
    ],
  }
})

function activateBrush() {
  // Pre-arms ECharts brush so the user can drag a horizontal region without
  // ever seeing a "select range" toolbox button.
  monthlyChartRef.value?.dispatchAction?.({
    type: 'takeGlobalCursor',
    key: 'brush',
    brushOption: { brushType: 'lineX', brushMode: 'single' },
  })
}

let brushTimer = null
let applyingBrush = false

function applyBrushSelection(indices) {
  if (!indices.length) return
  const startIdx = Math.max(0, Math.min(...indices))
  const endIdx = Math.min(monthlyData.value.length - 1, Math.max(...indices))
  if (startIdx > endIdx) return
  const a = monthlyData.value[startIdx]
  const b = monthlyData.value[endIdx]
  if (!a || !b) return
  const fromTs = new Date(a.year, (a.month || 1) - 1, 1).getTime()
  const lastDay = new Date(b.year, b.month || 1, 0).getDate()
  const toTs = new Date(b.year, (b.month || 1) - 1, lastDay).getTime()
  applyingBrush = true
  period.value = 'custom'
  dateRange.value = [fromTs, toTs]
  reload().then(() =>
    nextTick(() => {
      // Clear the highlight rectangle and re-arm brush mode for the next drag.
      monthlyChartRef.value?.dispatchAction?.({ type: 'brush', areas: [] })
      activateBrush()
      applyingBrush = false
    }),
  )
}

function onBrushSelected(p) {
  if (applyingBrush) return
  // ECharts fires brushSelected continuously while the user drags. Collect
  // the selected dataIndices across all series and debounce — the last call
  // after the user releases the mouse becomes the committed selection.
  const batches = p?.batch || []
  const all = new Set()
  for (const batch of batches) {
    for (const sel of batch?.selected || []) {
      for (const idx of sel?.dataIndex || []) all.add(idx)
    }
  }
  clearTimeout(brushTimer)
  if (all.size === 0) return
  brushTimer = setTimeout(() => applyBrushSelection([...all]), 250)
}

onMounted(async () => {
  await reload()
  await nextTick()
  activateBrush()
})
</script>
