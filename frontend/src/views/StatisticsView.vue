<template>
  <div>
    <!-- Period selector: type-buttons (Месяц / Год / Период) слева, value-
         picker справа. На десктопе inline TilePeriodPicker / NDatePicker.
         На мобильном — иконка-кнопка с лейблом, разворачивает picker
         в popover (узкий контент, помещается). -->
    <n-card style="margin-bottom: 16px">
      <n-space align="center" wrap :size="8">
        <n-text v-if="!isMobile" strong>Период:</n-text>
        <n-button-group :size="isMobile ? 'small' : 'medium'">
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
        <!-- Desktop value-picker inline. -->
        <template v-if="!isMobile">
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
        </template>
        <!-- Mobile: маленькая кнопка-триггер value-picker'a справа. -->
        <n-popover v-else trigger="click" placement="bottom-end" :show-arrow="false">
          <template #trigger>
            <n-button size="small">
              <template #icon><n-icon :component="CalendarOutline" /></template>
              {{ periodLabel }}
            </n-button>
          </template>
          <div class="period-popover">
            <TilePeriodPicker
              v-if="period === 'month'"
              v-model:value="selectedMonth"
              type="month"
              size="small"
              width="100%"
              @update:value="onPeriodValueChange"
            />
            <TilePeriodPicker
              v-if="period === 'year'"
              v-model:value="selectedYear"
              type="year"
              size="small"
              width="100%"
              @update:value="onPeriodValueChange"
            />
            <n-date-picker
              v-if="period === 'custom'"
              v-model:value="dateRange"
              type="daterange"
              clearable
              size="small"
              style="width: 100%"
              @update:value="onPeriodValueChange"
            />
          </div>
        </n-popover>
      </n-space>
    </n-card>

    <!-- Summary cards — 6-col grid lets us hit 2×2 on mobile (Доходы + Расходы
         half-each, Баланс full-width) and 3-col on desktop without breakpoint
         juggling: half = 3/6, third = 2/6, full = 6/6. -->
    <n-grid
      :cols="6"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      :item-responsive="true"
      style="margin-bottom: 16px"
    >
      <n-grid-item span="3 m:2">
        <n-card>
          <n-statistic label="Доходы">
            <template #prefix>
              <n-icon :component="TrendingUpOutline" :color="primaryColor" :size="20" />
            </template>
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
      <n-grid-item span="3 m:2">
        <n-card>
          <n-statistic label="Расходы">
            <template #prefix>
              <n-icon :component="TrendingDownOutline" :color="palette.expense" :size="20" />
            </template>
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
      <n-grid-item span="6 m:2">
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
              :data="
                expensePieData.map((d) => ({
                  category: d.category,
                  amount: d.amount,
                  count: d.count,
                }))
              "
              :category-meta="categoryMetaByName"
              :palette="palette"
              :unit="pieChartUnit"
              :values-hidden="valuesHidden"
              :limits-by-name="limitsByName"
              @drilldown="(name) => drilldown('expense', name)"
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
              :data="
                incomePieData.map((d) => ({
                  category: d.category,
                  amount: d.amount,
                  count: d.count,
                }))
              "
              :category-meta="categoryMetaByName"
              :palette="palette"
              :unit="pieChartUnit"
              :values-hidden="valuesHidden"
              @drilldown="(name) => drilldown('income', name)"
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
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
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
  NPopover,
  NIcon,
} from 'naive-ui'
import { CalendarOutline, TrendingUpOutline, TrendingDownOutline } from '@vicons/ionicons5'
import { statistics, categories as catApi } from '@/api'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import { useRouter } from 'vue-router'
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
const router = useRouter()

// Pie-slice click on either chart routes to the matching list view with a
// category + date-range filter pre-applied. Period derivation mirrors the
// Android `StatsPeriod → (from, to)` translation: MONTH → first/last of
// month, YEAR → Jan 1 / Dec 31, RANGE → pass-through. The receiving view
// reads `route.query.from / .to / .categories` on mount.
function drilldownRange() {
  const pad = (n) => String(n).padStart(2, '0')
  const fmt = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  if (period.value === 'month') {
    const d = new Date(selectedMonth.value)
    const first = new Date(d.getFullYear(), d.getMonth(), 1)
    const last = new Date(d.getFullYear(), d.getMonth() + 1, 0)
    return { from: fmt(first), to: fmt(last) }
  }
  if (period.value === 'year') {
    const y = new Date(selectedYear.value).getFullYear()
    return { from: `${y}-01-01`, to: `${y}-12-31` }
  }
  if (period.value === 'custom' && dateRange.value) {
    return { from: fmt(new Date(dateRange.value[0])), to: fmt(new Date(dateRange.value[1])) }
  }
  return {}
}

function drilldown(kind, categoryName) {
  if (!categoryName) return
  const { from, to } = drilldownRange()
  router.push({
    path: kind === 'income' ? '/income' : '/expenses',
    query: {
      categories: categoryName,
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
    },
  })
}

const period = ref('month')
const selectedMonth = ref(Date.now())
const selectedYear = ref(Date.now())
const dateRange = ref(null)

const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
const isMobile = computed(() => windowWidth.value < 768)
function onWinResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

// Краткий лейбл текущего периода для триггера popover'а на мобильном.
const periodLabel = computed(() => {
  if (period.value === 'month') {
    const d = new Date(selectedMonth.value)
    return d.toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })
  }
  if (period.value === 'year') {
    return String(new Date(selectedYear.value).getFullYear())
  }
  if (dateRange.value) {
    const fmt = (ts) =>
      new Date(ts).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })
    return `${fmt(dateRange.value[0])} – ${fmt(dateRange.value[1])}`
  }
  return 'Период'
})

const summary = ref({ total_income: 0, total_expense: 0, balance: 0 })
const expensePieData = ref([])
const incomePieData = ref([])
// Name → Category map used to resolve per-category color/icon for pie slices.
// Both expense + income sections are merged; collisions are vanishingly rare
// (income and expense rarely share names) and a single lookup keeps slice
// colors stable regardless of which chart they appear in.
const categoryMetaByName = ref({})
// name → {spent, limit, percent} for expense categories that have a
// monthly_limit set. Always reflects the CURRENT calendar month — the
// limits-progress endpoint defaults to that, and we don't pass `month`
// even when the user is filtering by year/range, because a monthly limit
// vs a year-to-date sum is misleading. The donut legend renders a small
// progress bar per row whenever there's a matching entry.
const limitsByName = ref({})
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
      for (const c of list) {
        merged[c.name] = { color: c.color, icon: c.icon, icon_scale: c.icon_scale }
      }
    }
    categoryMetaByName.value = merged
  } catch {
    // Best-effort — pie chart falls back to name-hashed palette colors.
  }
}

async function loadLimitsProgress() {
  try {
    const { data } = await catApi.limitsProgress()
    const map = {}
    for (const row of data?.categories || []) {
      map[row.name] = { spent: row.spent, limit: row.limit, percent: row.percent }
    }
    limitsByName.value = map
  } catch {
    // Soft-fail: the bars just don't render.
    limitsByName.value = {}
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
      loadLimitsProgress(),
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

<style scoped>
.period-popover {
  width: min(280px, calc(100vw - 32px));
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
