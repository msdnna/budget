<template>
  <div ref="container" class="split-pane" :class="{ stacked }">
    <div class="split-left" :style="leftStyle">
      <slot name="left" />
    </div>
    <div
      class="split-divider"
      title="Потяните для изменения размера"
      @mousedown.prevent="startDrag"
    >
      <div class="split-divider-dots" />
    </div>
    <div class="split-right">
      <slot name="right" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  storageKey: { type: String, default: '' },
  defaultLeft: { type: Number, default: 45 },
  minLeft: { type: Number, default: 20 },
  maxLeft: { type: Number, default: 75 },
  // Ниже этой ширины окна слоты автостек'ятся вертикально (drag-divider
  // прячется). Дефолт 768 — оставляет существующее поведение Income/Expense.
  stackBelow: { type: Number, default: 768 },
})

// Clamp сохранённую позицию против актуальных min/max — иначе после
// ужесточения границ (например, max-left 75 → 60) пользователь со старым
// localStorage'ем продолжал бы видеть таблицу за пределами новых ограничений.
const storedRaw = props.storageKey ? parseFloat(localStorage.getItem(props.storageKey)) : NaN
const initialLeft = isNaN(storedRaw)
  ? props.defaultLeft
  : Math.min(Math.max(storedRaw, props.minLeft), props.maxLeft)
const leftPct = ref(initialLeft)
const container = ref(null)
// Стэкуем по ширине КОНТЕЙНЕРА (не окна), чтобы корректно учитывать сайдбар,
// модальные/popover'ы и любые внешние paddings. ResizeObserver обновляет
// containerWidth на каждый ресайз родителя.
const containerWidth = ref(0)
const stacked = computed(() => containerWidth.value > 0 && containerWidth.value < props.stackBelow)

let ro = null
onMounted(() => {
  if (container.value) containerWidth.value = container.value.offsetWidth
  ro = new ResizeObserver(([entry]) => {
    containerWidth.value = entry.contentRect.width
  })
  if (container.value) ro.observe(container.value)
})
onUnmounted(() => ro?.disconnect())

// В stacked-режиме фиксированная процентная ширина мешает CSS-фоллбэку
// (`width:100%`) — поэтому когда stacked=true, оставляем стиль пустым.
const leftStyle = computed(() => (stacked.value ? {} : { width: leftPct.value + '%' }))

function startDrag() {
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
}

function onDrag(e) {
  if (!container.value) return
  const rect = container.value.getBoundingClientRect()
  const pct = ((e.clientX - rect.left) / rect.width) * 100
  leftPct.value = Math.min(Math.max(pct, props.minLeft), props.maxLeft)
  if (props.storageKey) localStorage.setItem(props.storageKey, leftPct.value)
}

function stopDrag() {
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
}

onUnmounted(stopDrag)
</script>

<style scoped>
.split-pane {
  display: flex;
  align-items: flex-start;
  width: 100%;
}

.split-left {
  flex-shrink: 0;
  min-width: 0;
}

.split-right {
  flex: 1;
  min-width: 0;
}

.split-divider {
  flex-shrink: 0;
  width: 16px;
  align-self: stretch;
  cursor: col-resize;
  display: flex;
  align-items: center;
  justify-content: center;
}

.split-divider-dots {
  width: 4px;
  height: 32px;
  border-radius: 2px;
  background: var(--divider);
  transition:
    background 0.2s,
    transform 0.15s;
}

.split-divider:hover .split-divider-dots {
  background: var(--divider-hover);
  transform: scaleX(1.5);
}

/* Stacked-режим: вертикальный стек слотов, drag-divider скрыт. Триггерится
   JS-флагом `stacked` (зависит от prop `stackBelow`), поэтому работает для
   любых breakpoint'ов, не только моба. */
.split-pane.stacked {
  flex-direction: column;
  /* Отступ между слотами в стек-режиме — иначе сложенные карточки визуально
     слипались в один блок (мирор `n-grid y-gap=16` на остальных view'ах). */
  gap: 16px;
}
.split-pane.stacked .split-left {
  width: 100% !important;
}
.split-pane.stacked .split-right {
  width: 100%;
}
.split-pane.stacked .split-divider {
  display: none;
}
</style>
