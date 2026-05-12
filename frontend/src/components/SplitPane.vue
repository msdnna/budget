<template>
  <div ref="container" class="split-pane">
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
import { ref, computed, onUnmounted } from 'vue'

const props = defineProps({
  storageKey: { type: String, default: '' },
  defaultLeft: { type: Number, default: 45 },
  minLeft: { type: Number, default: 20 },
  maxLeft: { type: Number, default: 75 },
})

const stored = props.storageKey ? parseFloat(localStorage.getItem(props.storageKey)) : NaN
const leftPct = ref(isNaN(stored) ? props.defaultLeft : stored)
const container = ref(null)

const leftStyle = computed(() => ({ width: leftPct.value + '%' }))

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

@media (max-width: 768px) {
  .split-pane {
    flex-direction: column;
  }
  .split-left {
    width: 100% !important;
  }
  .split-right {
    width: 100%;
  }
  .split-divider {
    display: none;
  }
}
</style>
