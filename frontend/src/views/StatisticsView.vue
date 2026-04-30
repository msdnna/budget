<template>
  <div>
    <!-- Period selector -->
    <n-card style="margin-bottom: 16px;">
      <n-space align="center" wrap>
        <n-text strong>Период:</n-text>
        <n-button-group>
          <n-button :type="period === 'month' ? 'primary' : 'default'" @click="setPeriod('month')">Месяц</n-button>
          <n-button :type="period === 'year' ? 'primary' : 'default'" @click="setPeriod('year')">Год</n-button>
          <n-button :type="period === 'custom' ? 'primary' : 'default'" @click="setPeriod('custom')">Период</n-button>
        </n-button-group>
        <n-date-picker v-if="period === 'month'" v-model:value="selectedMonth" type="month" @update:value="loadData" />
        <n-date-picker v-if="period === 'year'" v-model:value="selectedYear" type="year" @update:value="loadData" />
        <n-date-picker v-if="period === 'custom'" v-model:value="dateRange" type="daterange" @update:value="loadData" clearable />
      </n-space>
    </n-card>

    <!-- Summary cards -->
    <n-grid :cols="3" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true" style="margin-bottom: 16px;">
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Доходы" :value="summary.total_income" :precision="0">
            <template #prefix><span :style="{ color: primaryColor }">↑</span></template>
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Расходы" :value="summary.total_expense" :precision="0">
            <template #prefix><span :style="{ color: palette.expense }">↓</span></template>
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic
            label="Баланс"
            :value="Math.abs(summary.balance)"
            :precision="0"
          >
            <template #prefix>
              <span :style="{ color: summary.balance >= 0 ? primaryColor : palette.expense }">
                {{ summary.balance >= 0 ? '+' : '−' }}
              </span>
            </template>
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Charts row -->
    <n-grid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true" style="margin-bottom: 16px;">
      <n-grid-item span="2 m:1">
        <n-card title="Расходы по категориям">
          <n-spin :show="loadingCharts">
            <v-chart v-if="expensePieData.length" :option="expensePieOption" style="height: 320px;" autoresize />
            <n-empty v-else description="Нет данных" style="padding: 60px 0;" />
          </n-spin>
        </n-card>
      </n-grid-item>
      <n-grid-item span="2 m:1">
        <n-card title="Доходы по источникам">
          <n-spin :show="loadingCharts">
            <v-chart v-if="incomePieData.length" :option="incomePieOption" style="height: 320px;" autoresize />
            <n-empty v-else description="Нет данных" style="padding: 60px 0;" />
          </n-spin>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Monthly bar chart -->
    <n-card title="Динамика по месяцам">
      <n-spin :show="loadingMonthly">
        <v-chart :option="monthlyBarOption" style="height: 300px;" autoresize />
      </n-spin>
    </n-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { use } from 'echarts/core'
import { PieChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import {
  NCard, NGrid, NGridItem, NStatistic, NSpin, NEmpty,
  NSpace, NText, NButton, NButtonGroup, NDatePicker
} from 'naive-ui'
import { statistics } from '@/api'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'

use([PieChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const { chartColors, primaryColor, palette } = storeToRefs(useThemeStore())

const period = ref('month')
const selectedMonth = ref(Date.now())
const selectedYear = ref(Date.now())
const dateRange = ref(null)

const summary = ref({ total_income: 0, total_expense: 0, balance: 0 })
const expensePieData = ref([])
const incomePieData = ref([])
const monthlyData = ref([])
const loadingCharts = ref(false)
const loadingMonthly = ref(false)

function buildParams() {
  const p = {}
  if (period.value === 'month') {
    const d = new Date(selectedMonth.value)
    p.month = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  } else if (period.value === 'year') {
    p.year = new Date(selectedYear.value).getFullYear()
  } else if (dateRange.value) {
    p.from = new Date(dateRange.value[0]).toISOString().split('T')[0]
    p.to = new Date(dateRange.value[1]).toISOString().split('T')[0]
  }
  return p
}

async function loadData() {
  const params = buildParams()
  loadingCharts.value = true
  try {
    const [s, exp, inc] = await Promise.all([
      statistics.summary(params),
      statistics.byCategory({ ...params, type: 'expense' }),
      statistics.byCategory({ ...params, type: 'income' }),
    ])
    summary.value = s.data
    expensePieData.value = exp.data || []
    incomePieData.value = inc.data || []
  } finally {
    loadingCharts.value = false
  }
}

async function loadMonthly() {
  loadingMonthly.value = true
  try {
    const year = period.value === 'year'
      ? new Date(selectedYear.value).getFullYear()
      : new Date().getFullYear()
    const { data } = await statistics.monthly({ year })
    monthlyData.value = data || []
  } finally {
    loadingMonthly.value = false
  }
}

function setPeriod(p) {
  period.value = p
  loadData()
  if (p === 'year') loadMonthly()
}

function tooltipStyle() {
  return {
    backgroundColor: palette.value.tooltipBg,
    borderColor:     palette.value.tooltipBorder,
    textStyle: { color: palette.value.tooltipText },
  }
}

function makePieOption(data) {
  const p = palette.value
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ₽ ({d}%)', ...tooltipStyle() },
    legend: { bottom: 0, type: 'scroll', textStyle: { color: p.chartLabel } },
    color: chartColors.value,
    series: [{
      type: 'pie',
      radius: ['38%', '65%'],
      center: ['50%', '44%'],
      data: data.map(d => ({ name: d.category, value: Math.round(d.amount) })),
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: p.chartShadow } },
      label: { color: p.chartLabel, formatter: '{b}\n{d}%' },
      labelLine: { lineStyle: { color: p.chartLabel } },
    }],
  }
}

const expensePieOption = computed(() => makePieOption(expensePieData.value))
const incomePieOption  = computed(() => makePieOption(incomePieData.value))

const MONTH_NAMES = ['Янв','Фев','Мар','Апр','Май','Июн','Июл','Авг','Сен','Окт','Ноя','Дек']

const monthlyBarOption = computed(() => {
  const p = palette.value
  return {
    tooltip: { trigger: 'axis', ...tooltipStyle() },
    legend: { data: ['Доходы', 'Расходы'], textStyle: { color: p.chartLabel } },
    color: [primaryColor.value, p.expense],
    xAxis: {
      type: 'category', data: MONTH_NAMES,
      axisLabel: { color: p.chartAxis },
      axisLine: { lineStyle: { color: p.chartGrid } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => v.toLocaleString('ru'), color: p.chartAxis },
      splitLine: { lineStyle: { color: p.chartGrid } },
      axisLine: { lineStyle: { color: p.chartGrid } },
    },
    series: [
      { name: 'Доходы',  type: 'bar', data: monthlyData.value.map(m => Math.round(m.income)),  barMaxWidth: 32 },
      { name: 'Расходы', type: 'bar', data: monthlyData.value.map(m => Math.round(m.expense)), barMaxWidth: 32 },
    ],
  }
})

onMounted(() => {
  loadData()
  loadMonthly()
})
</script>
