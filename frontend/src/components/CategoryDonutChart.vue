<template>
  <div class="cdc">
    <div ref="chartWrap" class="cdc-chart">
      <v-chart ref="chartRef" :option="chartOption" autoresize @click="onSliceClick" />
      <!-- Back button: shown only after the user expanded the synthetic
           "Прочее" wedge (which hides every non-grouped category). One tap
           restores the original visible set so the donut returns to the
           pre-expand state. Sits in the donut hole; click is captured here
           and not passed through to ECharts. -->
      <button
        v-if="otherExpanded"
        type="button"
        class="cdc-back-btn"
        :style="{
          background: palette.surface,
          borderColor: palette.border,
          color: palette.text2,
        }"
        title="Назад"
        @click.stop="exitOtherExpand"
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.25"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <polyline points="15 18 9 12 15 6" />
        </svg>
        <span>Назад</span>
      </button>
      <div
        v-for="b in iconOverlays"
        :key="`${b.name}-${animKey}`"
        class="cdc-slice-icon"
        :style="{ left: b.x + 'px', top: b.y + 'px' }"
      >
        <n-icon v-if="b.iconComp" :component="b.iconComp" :size="16" color="#fff" />
        <img v-else-if="b.customUrl" :src="b.customUrl" class="cdc-slice-img" alt="" />
      </div>
    </div>
    <div class="cdc-legend" :style="scrollbarVars">
      <div
        v-for="row in legendRows"
        :key="row.name"
        class="cdc-row"
        :class="{ 'cdc-row-hidden': row.hidden, 'cdc-row-with-limit': row.limit }"
        role="button"
        tabindex="0"
        @click="toggleHidden(row.name)"
        @keydown.enter.prevent="toggleHidden(row.name)"
        @keydown.space.prevent="toggleHidden(row.name)"
      >
        <!-- Badge sits as a direct child of .cdc-row so its flex parent's
             align-items: center vertically centres the icon across the
             *entire* row height — including the limit bar on a second
             line. When the badge lived inside .cdc-row-main it only
             centred within the top sub-row, anchoring the icon to the top
             edge of limited rows. -->
        <div class="cdc-row-badge" :style="{ backgroundColor: row.color }">
          <n-icon v-if="row.iconComp" :component="row.iconComp" :size="18" color="#fff" />
          <img
            v-else-if="row.customUrl"
            :src="row.customUrl"
            class="cdc-row-img"
            :style="rowImgStyle(row.iconScale)"
            alt=""
          />
        </div>
        <div class="cdc-row-content">
          <div class="cdc-row-main">
            <div class="cdc-row-text">
              <div class="cdc-row-name">{{ row.name }}</div>
              <!-- Count caption lives under the name for non-limited rows.
                   Limited rows show it under the bar instead (see below) so
                   the name line stays compact next to the limit progress.
                   `hideCount` suppresses it entirely for charts where the
                   tx count would be misleading (Forecast). -->
              <div v-if="!row.limit && !hideCount" class="cdc-row-count">
                Количество транзакций: {{ row.count }}
              </div>
            </div>
            <div class="cdc-row-meta">
              <!-- Amount blurs in hidden mode (rather than disappearing) so
                   the row layout stays stable — same UX as the StatisticsView
                   summary cards. For limited rows the chart-share percent
                   moves into a hover tooltip; without a limit it stays as
                   the small line under the amount. -->
              <n-tooltip v-if="row.limit" trigger="hover" placement="top">
                <template #trigger>
                  <span class="cdc-row-amount" :class="{ 'cdc-row-amount-hidden': valuesHidden }">
                    {{ row.amount.toLocaleString('ru') }} ₽
                  </span>
                </template>
                Доля в диаграмме: {{ row.percent }}%
              </n-tooltip>
              <span
                v-else
                class="cdc-row-amount"
                :class="{ 'cdc-row-amount-hidden': valuesHidden }"
              >
                {{ row.amount.toLocaleString('ru') }} ₽
              </span>
              <span v-if="!row.limit" class="cdc-row-pct">{{ row.percent }}%</span>
            </div>
          </div>
          <!-- Limit bar lives in the same flex-column as the main name+amount
               line so the badge to the left centres vertically across both.
               Under the bar: count (left) + spent/limit (right) share a
               single line via space-between so the row stays compact. -->
          <div v-if="row.limit" class="cdc-row-limit">
            <n-progress
              type="line"
              :percentage="Math.min(100, row.limit.percent)"
              :show-indicator="false"
              :color="limitProgressColor(row.limit.percent)"
              :height="6"
              :border-radius="3"
            />
            <div class="cdc-row-limit-foot">
              <span v-if="!hideCount" class="cdc-row-count">
                Количество транзакций: {{ row.count }}
              </span>
              <span v-else />
              <span class="cdc-row-limit-text" :class="{ 'cdc-row-amount-hidden': valuesHidden }">
                {{ formatMoney(row.limit.spent) }} / {{ formatMoney(row.limit.limit) }} ₽ ({{
                  Math.round(row.limit.percent)
                }}%)
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, useTemplateRef, watch } from 'vue'
import { use } from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import { NIcon, NProgress, NTooltip } from 'naive-ui'
import { categoryIcon, resolveCategoryColor } from '@/utils/categoryIcons'
import { useIconCacheStore, parseCustomIconKey } from '@/stores/iconCache'

use([PieChart, TooltipComponent, CanvasRenderer])

const props = defineProps({
  data: { type: Array, default: () => [] }, // [{category, amount}]
  categoryMeta: { type: Object, default: () => ({}) }, // name → {color, icon}
  palette: { type: Object, required: true },
  unit: { type: String, default: 'percent' }, // 'percent' | 'ruble'
  valuesHidden: { type: Boolean, default: false },
  // name → {spent, limit, percent}. Always reflects the *current* calendar
  // month (limits-progress endpoint default) regardless of the filter the
  // parent applies to the pie data itself — limit windows are fixed monthly
  // by spec, so showing year-to-date over a monthly limit would be lying.
  limitsByName: { type: Object, default: () => ({}) },
  // Suppresses the "Количество транзакций: N" caption. Forecast view sets
  // this because its breakdown mixes historical tx + projected wishlist
  // items, so a single tx count would be misleading.
  hideCount: { type: Boolean, default: false },
})

// `drilldown` fires when a real (non-grouped) slice is clicked. Parent uses
// it to navigate to Income/Expenses with a per-category filter applied —
// mirrors the Android pie-slice drilldown flow.
const emit = defineEmits(['drilldown'])

const chartWrap = useTemplateRef('chartWrap')
const chartRef = ref(null)

// Below this %, the slice is collapsed into a single synthetic "Прочее"
// pie wedge so the donut stays readable. Legend still shows the small
// categories individually so you can drill down by hiding the big ones.
// Threshold matches MIN_ICON_PCT — anything that wouldn't fit an on-slice
// icon overlay also doesn't deserve its own wedge.
const MIN_VISIBLE_PCT = 5
const MIN_ICON_PCT = 5
const OTHER_KEY = '__other__'
const OTHER_LABEL = 'Прочее'

const hiddenSet = ref(new Set())
// Stack of previous `hiddenSet` snapshots — pushed every time the user
// clicks the synthetic "Прочее" wedge to expand its grouped categories.
// Back button pops one level (not a wipe), so nested expands stay
// reversible step-by-step. Empty stack = top-level view, back button
// hidden. Snapshots are stored as plain Sets (immutable from our side,
// since toggleHidden replaces the ref rather than mutating).
const hiddenStack = ref([])
const otherExpanded = computed(() => hiddenStack.value.length > 0)

function toggleHidden(name) {
  // Mutate by replacement so reactivity fires.
  const next = new Set(hiddenSet.value)
  if (next.has(name)) next.delete(name)
  else next.add(name)
  hiddenSet.value = next
}

// Pie slice click: a real category emits `drilldown` to the parent
// (StatisticsView routes to Income/Expenses with category+period filter
// applied). The synthetic "Прочее" wedge is expanded in place by hiding
// every category that *isn't* part of the grouped set — the small slices
// balloon into proper wedges and become drilldown-tappable on the next
// click. The previous `hiddenSet` is pushed onto a stack so the back
// button can pop one level at a time (supports nested «Прочее» drill-ins).
function onSliceClick(params) {
  if (params?.componentType !== 'series') return
  const slice = pieSlices.value.find((s) => s.name === params.name)
  if (!slice) return
  if (slice.name === OTHER_KEY) {
    const grouped = new Set(slice.grouped || [])
    const allLabels = enrichedAll.value.map((x) => x.name)
    hiddenStack.value = [...hiddenStack.value, hiddenSet.value]
    hiddenSet.value = new Set(allLabels.filter((n) => !grouped.has(n)))
    return
  }
  emit('drilldown', slice.name)
}

function exitOtherExpand() {
  if (hiddenStack.value.length === 0) return
  const stack = hiddenStack.value
  const prev = stack[stack.length - 1]
  hiddenSet.value = prev instanceof Set ? prev : new Set()
  hiddenStack.value = stack.slice(0, -1)
}

const iconCache = useIconCacheStore()

// Full enrichment for every input category — used for the legend. Each
// slice surfaces `iconComp` (Vue component for built-in keys) OR
// `customUrl` (cached blob URL for `custom:<id>` keys). Neither set means
// "no icon" — the badge renders as a colored square without a glyph.
const enrichedAll = computed(() =>
  props.data.map((d) => {
    const meta = props.categoryMeta[d.category] || {}
    const customId = parseCustomIconKey(meta.icon)
    const rawScale = meta.icon_scale
    return {
      name: d.category,
      amount: Math.round(d.amount),
      // Tx count surfaces as a "Количество транзакций: N" caption — under
      // the name when there's no limit, under the bar when there is.
      count: typeof d.count === 'number' ? d.count : 0,
      color: resolveCategoryColor({ name: d.category, color: meta.color }),
      iconComp: customId ? null : categoryIcon(meta.icon),
      customUrl: customId ? (iconCache.cache.get(customId) ?? null) : null,
      customId,
      // Only used in legend/list badges; on-slice icons stay fixed-size
      // for arc readability.
      iconScale: rawScale && rawScale > 0 ? rawScale : 1,
    }
  }),
)

// Kick off blob fetches for custom icons; resolved URLs land in
// `iconCache.cache`, which triggers re-render via the Map reactivity.
watch(
  enrichedAll,
  (rows) => {
    for (const r of rows) {
      if (r.customId && !iconCache.cache.get(r.customId)) {
        iconCache.resolve(r.customId).catch(() => {
          // Best-effort — missing/deleted icons fall back to plain badge.
        })
      }
    }
  },
  { immediate: true },
)

const totalAll = computed(() => enrichedAll.value.reduce((s, x) => s + x.amount, 0))

const visible = computed(() => enrichedAll.value.filter((x) => !hiddenSet.value.has(x.name)))
const totalVisible = computed(() => visible.value.reduce((s, x) => s + x.amount, 0))

// Pie chart data with grouping: anything < MIN_VISIBLE_PCT of the visible
// total is folded into a single "Прочее" wedge. If only one slice would be
// folded we keep it standalone — a "Прочее (1)" wedge is just noise.
const pieSlices = computed(() => {
  const tot = totalVisible.value
  if (tot === 0) return []
  const big = []
  const small = []
  for (const v of visible.value) {
    const pct = (v.amount / tot) * 100
    if (pct < MIN_VISIBLE_PCT) small.push(v)
    else big.push(v)
  }
  if (small.length <= 1) return [...big, ...small]
  const sum = small.reduce((s, x) => s + x.amount, 0)
  return [
    ...big,
    {
      name: OTHER_KEY,
      label: OTHER_LABEL,
      amount: sum,
      color: props.palette.text3,
      iconComp: categoryIcon('ellipsis-horizontal'),
      customUrl: null,
      grouped: small.map((x) => x.name),
    },
  ]
})

const legendRows = computed(() => {
  const tot = totalAll.value || 1
  return enrichedAll.value.map((x) => {
    const pct = (x.amount / tot) * 100
    const lim = props.limitsByName[x.name] || null
    return {
      ...x,
      hidden: hiddenSet.value.has(x.name),
      percent: pct < 10 ? pct.toFixed(1) : pct.toFixed(0),
      limit: lim,
    }
  })
})

// Limit-progress tint mirrors AdminCategoriesView / ExpensesView: green
// under 80%, amber 80-100%, red ≥100%. Theme-aware tokens via props.palette
// keep the colors in sync with the rest of the UI's income/expense pair.
function limitProgressColor(percent) {
  if (percent >= 100) return props.palette.expense
  if (percent >= 80) return '#F59E0B'
  return props.palette.income
}

function formatMoney(value) {
  if (value == null) return ''
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value)
}

const chartOption = computed(() => {
  const p = props.palette
  const isRuble = props.unit === 'ruble'
  const hide = props.valuesHidden
  const tooltipFmt = (params) => {
    const slice = pieSlices.value.find((s) => s.name === params.name)
    const displayName = slice?.label || params.name
    if (isRuble) {
      // In hidden mode the value is masked, never dropped — matches the
      // monthly bar tooltip ('•••• ₽') so the hover never reveals real
      // numbers but the user still sees the unit and category.
      const valStr = hide ? '••••' : params.value.toLocaleString('ru')
      return `${displayName}: ${valStr} ₽`
    }
    return `${displayName}: ${params.percent}%`
  }
  return {
    tooltip: {
      trigger: 'item',
      formatter: tooltipFmt,
      backgroundColor: p.tooltipBg,
      borderColor: p.tooltipBorder,
      textStyle: { color: p.tooltipText },
    },
    legend: { show: false },
    series: [
      {
        type: 'pie',
        radius: ['52%', '78%'],
        center: ['50%', '50%'],
        // Theme-aware gap between slices: 2px border in the Card surface
        // colour creates a visible separation that "is the background"
        // rather than a contrasting line. borderRadius rounds each slice's
        // four corners.
        itemStyle: {
          borderColor: p.surface,
          borderWidth: 2,
          borderRadius: 6,
        },
        data: pieSlices.value.map((x) => ({
          name: x.name,
          value: x.amount,
          itemStyle: { color: x.color },
        })),
        // Slice grows outward when hovered — no drop shadow because on
        // light theme the white border around adjacent slices is visible
        // through the shadow and reads as a halo around the hovered wedge.
        emphasis: {
          scale: true,
          scaleSize: 8,
        },
        label: { show: false },
        labelLine: { show: false },
        animationDuration: 700,
      },
    ],
  }
})

// Container rect, updated on mount + whenever the wrapper resizes. We do
// NOT key icon positions to ECharts `finished` — that event fires after the
// sweep animation completes, leaving icons absent during the entire
// animation window.
const wrapRect = ref({ width: 0, height: 0 })
let ro = null

function measure() {
  if (!chartWrap.value) return
  const r = chartWrap.value.getBoundingClientRect()
  wrapRect.value = { width: r.width, height: r.height }
}

onMounted(() => {
  measure()
  if (typeof ResizeObserver !== 'undefined' && chartWrap.value) {
    ro = new ResizeObserver(measure)
    ro.observe(chartWrap.value)
  }
})

onBeforeUnmount(() => {
  if (ro) ro.disconnect()
})

// Bump on data/filter changes — keys icon overlays so they remount and
// retrigger the CSS `cdc-icon-in` keyframe in sync with ECharts' sweep.
const animKey = ref(0)
watch(
  pieSlices,
  () => {
    animKey.value++
  },
  { flush: 'post' },
)

const iconOverlays = computed(() => {
  const { width, height } = wrapRect.value
  if (!width || !height) return []
  const cx = width / 2
  const cy = height / 2
  const refSize = Math.min(width, height) / 2
  const innerR = refSize * 0.52
  const outerR = refSize * 0.78
  const midR = (innerR + outerR) / 2

  const slices = pieSlices.value
  const tot = totalVisible.value
  if (tot === 0) return []

  const overlays = []
  let cum = 0
  for (const slice of slices) {
    const pct = (slice.amount / tot) * 100
    // Skip slices below the icon-readability threshold AND slices that have
    // no icon at all (empty/unknown key — admin hasn't picked one yet).
    if (pct >= MIN_ICON_PCT && (slice.iconComp || slice.customUrl)) {
      const midRad = ((cum + slice.amount / 2) / tot) * Math.PI * 2 - Math.PI / 2
      overlays.push({
        name: slice.name,
        iconComp: slice.iconComp,
        customUrl: slice.customUrl,
        // Icon is 16px; subtract half so its centre lands on the arc midline.
        x: cx + Math.cos(midRad) * midR - 8,
        y: cy + Math.sin(midRad) * midR - 8,
      })
    }
    cum += slice.amount
  }
  return overlays
})

const scrollbarVars = computed(() => ({
  '--cdc-scroll-thumb': props.palette.border,
  '--cdc-scroll-thumb-hover': props.palette.text3,
}))

// Legend badge is 36×36; the default custom-icon inside it is 22px. Scale
// > 1 enlarges and the parent .cdc-row-badge clips to its rounded shape
// (overflow: hidden) so the icon "fills" the badge without bleeding out.
function rowImgStyle(scale) {
  const base = 22
  const s = scale && scale > 0 ? scale : 1
  const size = base * s
  return { width: size + 'px', height: size + 'px' }
}
</script>

<style scoped>
.cdc {
  display: flex;
  flex-direction: row;
  gap: 20px;
  align-items: stretch;
  min-height: 320px;
}
.cdc-chart {
  position: relative;
  flex: 0 0 320px;
  height: 320px;
  cursor: pointer;
}
.cdc-chart :deep(.echarts) {
  width: 100% !important;
  height: 100% !important;
}
/* Back button parked in the donut hole. Absolute-centred inside the
   chart wrap; sized to fit comfortably inside the inner radius (~52% of
   the smaller dimension). Border + hover state pick up the surrounding
   theme via CSS variables so it sits naturally in both light + dark. */
.cdc-back-btn {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid;
  border-radius: 999px;
  cursor: pointer;
  z-index: 2;
  /* background / borderColor / color come from inline style bound to the
     theme palette, so the chip reads naturally on both light + dark. */
  transition:
    opacity 0.15s,
    transform 0.15s;
  -webkit-tap-highlight-color: transparent;
}
.cdc-back-btn:hover {
  opacity: 0.85;
}
.cdc-back-btn svg {
  display: block;
}
.cdc-slice-icon {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  /* Fade + scale in over the same window as ECharts' 700ms sweep so icons
     don't pop fully-formed onto a half-drawn donut. `:key` bumps via
     animKey remount this element on data/filter change to retrigger. */
  animation: cdc-icon-in 700ms cubic-bezier(0.25, 0.46, 0.45, 0.94) both;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.35));
}
.cdc-slice-img {
  width: 16px;
  height: 16px;
  object-fit: contain;
  display: block;
}
.cdc-row-img {
  object-fit: contain;
  display: block;
  /* width/height set inline from rowImgStyle() so the icon can scale
     beyond the badge and the parent's `overflow: hidden` clips it. */
}
@keyframes cdc-icon-in {
  0% {
    opacity: 0;
    transform: scale(0.4);
  }
  40% {
    opacity: 0;
    transform: scale(0.4);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}
.cdc-legend {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 320px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: var(--cdc-scroll-thumb) transparent;
}
.cdc-legend::-webkit-scrollbar {
  width: 8px;
}
.cdc-legend::-webkit-scrollbar-track {
  background: transparent;
}
.cdc-legend::-webkit-scrollbar-thumb {
  background: var(--cdc-scroll-thumb);
  border-radius: 4px;
}
.cdc-legend::-webkit-scrollbar-thumb:hover {
  background: var(--cdc-scroll-thumb-hover);
}
.cdc-row {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  user-select: none;
  border-radius: 6px;
  padding: 4px 6px;
  transition: opacity 0.15s;
  width: 100%;
}
.cdc-row-content {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cdc-row-main {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 12px;
  width: 100%;
}
.cdc-row:hover {
  background: var(--cdc-scroll-thumb);
  opacity: 0.95;
}
.cdc-row-hidden {
  opacity: 0.4;
}
.cdc-row-hidden .cdc-row-name {
  text-decoration: line-through;
}
/* Limit line — bar starts under the category name (badge is a sibling of
   .cdc-row-content, so the bar is already offset by the badge's width
   without explicit padding). Text right-aligned so it sits under amount. */
.cdc-row-limit {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cdc-row-limit-text {
  font-size: 11px;
  opacity: 0.6;
  font-variant-numeric: tabular-nums;
  text-align: right;
  transition: filter 0.25s;
}
/* Count caption — same muted style as the limit/percent secondary text. */
.cdc-row-count {
  font-size: 11px;
  opacity: 0.6;
  font-variant-numeric: tabular-nums;
}
/* On limited rows the count sits to the left of the spent/limit text on a
   shared line; `gap: 8px` keeps them apart if the row is narrow enough to
   collide. */
.cdc-row-limit-foot {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.cdc-row-badge {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}
.cdc-row-text {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.cdc-row-name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cdc-row-meta {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.cdc-row-amount {
  font-size: 14px;
  font-weight: 500;
  transition: filter 0.25s;
}
.cdc-row-amount-hidden {
  filter: blur(6px);
  user-select: none;
}
.cdc-row-pct {
  font-size: 12px;
  opacity: 0.6;
}
/* Stack vertically on narrow screens so the donut keeps a sane aspect. */
@media (max-width: 600px) {
  .cdc {
    flex-direction: column;
    min-height: 0;
  }
  .cdc-chart {
    flex: 0 0 260px;
    height: 260px;
    width: 100%;
  }
  .cdc-legend {
    max-height: none;
  }
}
</style>
