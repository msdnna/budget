<template>
  <n-popover
    :show="open"
    @update:show="onPopoverShow"
    trigger="click"
    placement="bottom-start"
    :show-arrow="false"
    raw
    style="background: transparent"
  >
    <template #trigger>
      <n-input
        :value="formatted"
        :placeholder="placeholder"
        :size="size"
        :clearable="clearable && value != null"
        :readonly="true"
        :style="inputStyle"
        @clear="clear"
      >
        <template #suffix>
          <n-icon size="16" :depth="3"><CalendarOutline /></n-icon>
        </template>
      </n-input>
    </template>

    <div class="tpp-panel" :style="cssVars">
      <div class="tpp-header">
        <span class="tpp-arrow" @click="navPrev">‹</span>
        <span class="tpp-title">{{ headerLabel }}</span>
        <span class="tpp-arrow" @click="navNext">›</span>
      </div>
      <div class="tpp-grid">
        <div
          v-for="(cell, i) in cells"
          :key="i"
          class="tpp-cell"
          :class="{ active: cell.active, today: cell.today, muted: cell.muted }"
          @click="select(cell)"
        >
          {{ cell.label }}
        </div>
      </div>
      <div class="tpp-actions">
        <n-button size="tiny" tertiary @click="setNow">{{ type === 'month' ? 'Текущий' : 'Этот год' }}</n-button>
        <n-button v-if="clearable && value != null" size="tiny" quaternary type="error" @click="clear">Очистить</n-button>
      </div>
    </div>
  </n-popover>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { NPopover, NInput, NButton, NIcon } from 'naive-ui'
import { CalendarOutline } from '@vicons/ionicons5'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'

const props = defineProps({
  value: { type: [Number, Object, null], default: null },
  type: { type: String, default: 'month' }, // 'month' | 'year'
  placeholder: { type: String, default: '' },
  clearable: { type: Boolean, default: false },
  size: { type: String, default: 'medium' },
  width: { type: String, default: '' },
})
const emit = defineEmits(['update:value'])

const { primaryColor, palette } = storeToRefs(useThemeStore())

const open = ref(false)
const cursor = ref(new Date(props.value || Date.now()))

watch(() => props.value, v => {
  if (v != null) cursor.value = new Date(v)
})

function onPopoverShow(v) {
  open.value = v
  if (v) cursor.value = new Date(props.value || Date.now())
}

const MONTH_NAMES_FULL = ['Январь','Февраль','Март','Апрель','Май','Июнь','Июль','Август','Сентябрь','Октябрь','Ноябрь','Декабрь']
const MONTH_NAMES_SHORT = ['Янв','Фев','Мар','Апр','Май','Июн','Июл','Авг','Сен','Окт','Ноя','Дек']

const formatted = computed(() => {
  if (props.value == null) return ''
  const d = new Date(props.value)
  if (props.type === 'month') {
    return `${MONTH_NAMES_FULL[d.getMonth()]} ${d.getFullYear()}`
  }
  return String(d.getFullYear())
})

const headerLabel = computed(() => {
  if (props.type === 'month') return String(cursor.value.getFullYear())
  const y = cursor.value.getFullYear()
  const start = Math.floor(y / 10) * 10
  return `${start}–${start + 9}`
})

const cells = computed(() => {
  if (props.type === 'month') {
    const cy = cursor.value.getFullYear()
    const sel = props.value != null ? new Date(props.value) : null
    const today = new Date()
    return MONTH_NAMES_SHORT.map((label, i) => ({
      label, year: cy, month: i,
      active: !!sel && sel.getFullYear() === cy && sel.getMonth() === i,
      today: today.getFullYear() === cy && today.getMonth() === i,
      muted: false,
    }))
  }
  const cy = cursor.value.getFullYear()
  const start = Math.floor(cy / 10) * 10 - 1
  const sel = props.value != null ? new Date(props.value) : null
  const today = new Date().getFullYear()
  return Array.from({ length: 12 }, (_, i) => {
    const y = start + i
    return {
      label: String(y), year: y, month: 0,
      active: !!sel && sel.getFullYear() === y,
      today: today === y,
      muted: i === 0 || i === 11,
    }
  })
})

function select(cell) {
  const d = new Date(cell.year, cell.month, 1)
  emit('update:value', d.getTime())
  open.value = false
}

function navPrev() {
  const d = new Date(cursor.value)
  if (props.type === 'month') d.setFullYear(d.getFullYear() - 1)
  else d.setFullYear(d.getFullYear() - 10)
  cursor.value = d
}

function navNext() {
  const d = new Date(cursor.value)
  if (props.type === 'month') d.setFullYear(d.getFullYear() + 1)
  else d.setFullYear(d.getFullYear() + 10)
  cursor.value = d
}

function setNow() {
  const now = new Date()
  const d = props.type === 'month'
    ? new Date(now.getFullYear(), now.getMonth(), 1)
    : new Date(now.getFullYear(), 0, 1)
  emit('update:value', d.getTime())
  open.value = false
}

function clear() {
  emit('update:value', null)
  open.value = false
}

const inputStyle = computed(() => ({
  width: props.width || (props.type === 'month' ? '180px' : '120px'),
  cursor: 'pointer',
}))

const cssVars = computed(() => ({
  '--tpp-primary': primaryColor.value,
  '--tpp-bg': palette.value.surfaceAlt,
  '--tpp-fg': palette.value.text2,
  '--tpp-fg-muted': palette.value.text3,
  '--tpp-border': palette.value.border,
  '--tpp-hover': palette.value.hover,
  '--tpp-shadow': palette.value.chartShadow,
}))
</script>

<style scoped>
.tpp-panel {
  background: var(--tpp-bg);
  color: var(--tpp-fg);
  border: 1px solid var(--tpp-border);
  border-radius: 4px;
  padding: 6px;
  width: 240px;
  box-shadow: 0 4px 16px var(--tpp-shadow);
  font-size: 13px;
}
.tpp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 6px;
  font-weight: 600;
  border-bottom: 1px solid var(--tpp-border);
  margin-bottom: 6px;
}
.tpp-title {
  flex: 1;
  text-align: center;
  font-size: 14px;
  letter-spacing: 0.02em;
}
.tpp-arrow {
  cursor: pointer;
  user-select: none;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  font-size: 18px;
  line-height: 1;
  color: var(--tpp-fg-muted);
  transition: background 0.12s, color 0.12s;
}
.tpp-arrow:hover {
  background: var(--tpp-hover);
  color: var(--tpp-fg);
}
.tpp-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
}
.tpp-cell {
  padding: 9px 6px;
  text-align: center;
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
  transition: background 0.12s, color 0.12s, outline-color 0.12s;
  outline: 1px solid transparent;
}
.tpp-cell:hover {
  background: var(--tpp-hover);
}
.tpp-cell.active {
  background: var(--tpp-primary);
  color: #fff;
  font-weight: 600;
}
.tpp-cell.today:not(.active) {
  outline-color: var(--tpp-primary);
  color: var(--tpp-primary);
}
.tpp-cell.muted {
  opacity: 0.4;
}
.tpp-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px solid var(--tpp-border);
}
</style>
