<template>
  <div class="cdc">
    <div ref="chartWrap" class="cdc-chart">
      <v-chart ref="chartRef" :option="chartOption" autoresize />
      <div
        v-for="b in iconOverlays"
        :key="`${b.name}-${animKey}`"
        class="cdc-slice-icon"
        :style="{ left: b.x + 'px', top: b.y + 'px' }"
      >
        <n-icon :component="b.icon" :size="16" color="#fff" />
      </div>
    </div>
    <div class="cdc-legend" :style="scrollbarVars">
      <div
        v-for="row in legendRows"
        :key="row.name"
        class="cdc-row"
        :class="{ 'cdc-row-hidden': row.hidden }"
        role="button"
        tabindex="0"
        @click="toggleHidden(row.name)"
        @keydown.enter.prevent="toggleHidden(row.name)"
        @keydown.space.prevent="toggleHidden(row.name)"
      >
        <div class="cdc-row-badge" :style="{ backgroundColor: row.color }">
          <n-icon :component="row.icon" :size="18" color="#fff" />
        </div>
        <div class="cdc-row-text">
          <div class="cdc-row-name">{{ row.name }}</div>
        </div>
        <div class="cdc-row-meta">
          <span v-if="!hideMoney" class="cdc-row-amount">
            {{ row.amount.toLocaleString('ru') }} ₽
          </span>
          <span class="cdc-row-pct">{{ row.percent }}%</span>
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
import { NIcon } from 'naive-ui'
import { categoryIcon, resolveCategoryColor } from '@/utils/categoryIcons'

use([PieChart, TooltipComponent, CanvasRenderer])

const props = defineProps({
  data: { type: Array, default: () => [] }, // [{category, amount}]
  categoryMeta: { type: Object, default: () => ({}) }, // name → {color, icon}
  palette: { type: Object, required: true },
  unit: { type: String, default: 'percent' }, // 'percent' | 'ruble'
  valuesHidden: { type: Boolean, default: false },
})

const chartWrap = useTemplateRef('chartWrap')
const chartRef = ref(null)

// Below this %, the slice is collapsed into a single synthetic "Прочее"
// pie wedge so the donut stays readable. Legend still shows the small
// categories individually so you can drill down by hiding the big ones.
const MIN_VISIBLE_PCT = 3
// Below this %, even the on-slice icon overlay is skipped — fits visually
// inside a thin arc would be unreadable.
const MIN_ICON_PCT = 4
const OTHER_KEY = '__other__'
const OTHER_LABEL = 'Прочее'

const hiddenSet = ref(new Set())
function toggleHidden(name) {
  // Mutate by replacement so reactivity fires.
  const next = new Set(hiddenSet.value)
  if (next.has(name)) next.delete(name)
  else next.add(name)
  hiddenSet.value = next
}

const hideMoney = computed(() => props.unit === 'ruble' && props.valuesHidden)

// Full enrichment for every input category — used for the legend.
const enrichedAll = computed(() =>
  props.data.map((d) => {
    const meta = props.categoryMeta[d.category] || {}
    return {
      name: d.category,
      amount: Math.round(d.amount),
      color: resolveCategoryColor({ name: d.category, color: meta.color }),
      icon: categoryIcon(meta.icon),
    }
  }),
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
      icon: categoryIcon('ellipsis-horizontal'),
      grouped: small.map((x) => x.name),
    },
  ]
})

const legendRows = computed(() => {
  const tot = totalAll.value || 1
  return enrichedAll.value.map((x) => {
    const pct = (x.amount / tot) * 100
    return {
      ...x,
      hidden: hiddenSet.value.has(x.name),
      percent: pct < 10 ? pct.toFixed(1) : pct.toFixed(0),
    }
  })
})

const chartOption = computed(() => {
  const p = props.palette
  const isRuble = props.unit === 'ruble'
  const hide = isRuble && props.valuesHidden
  const tooltipFmt = (params) => {
    const slice = pieSlices.value.find((s) => s.name === params.name)
    const displayName = slice?.label || params.name
    if (isRuble) {
      return hide ? displayName : `${displayName}: ${params.value.toLocaleString('ru')} ₽`
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
    if (pct >= MIN_ICON_PCT) {
      const midRad = ((cum + slice.amount / 2) / tot) * Math.PI * 2 - Math.PI / 2
      overlays.push({
        name: slice.name,
        icon: slice.icon,
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
}
.cdc-chart :deep(.echarts) {
  width: 100% !important;
  height: 100% !important;
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
.cdc-row-badge {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
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
