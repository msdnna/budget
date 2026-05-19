<template>
  <!-- Bottom-right row of square FAB-style buttons, mirrors the Android
       Scaffold floatingActionButton pattern when selectionMode is active
       (см. IncomeScreen.kt:124-148). На desktop'е bulkMode остаётся
       inline-toolbar — FAB-row актуальна только для mobile. -->
  <div class="bulk-fab-row">
    <button
      v-for="(a, i) in actions"
      :key="i"
      type="button"
      class="bulk-fab"
      :class="[`bulk-fab-${a.variant || 'default'}`, { 'is-pending': pendingIdx === i }]"
      :title="a.title"
      :aria-label="a.title"
      :disabled="!!a.disabled || !!a.loading"
      @click="onClick(a, i)"
    >
      <n-icon :component="a.icon" :size="22" />
    </button>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { NIcon } from 'naive-ui'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'

defineProps({
  // [{ icon: VueComponent, title: string, variant?: 'default'|'danger'|'primary',
  //    confirm?: boolean, disabled?: boolean, loading?: boolean, onClick: Function }]
  actions: { type: Array, required: true },
})

const { palette } = storeToRefs(useThemeStore())

// 2-tap confirm для destructive экшенов: первый тап выставляет
// `pendingIdx`, второй — стреляет onClick. Без него bulk-delete срабатывает
// от случайного тапа без шанса откатить (на FAB не помещается label
// «Подтвердить?»). Авто-сброс через 2.5с.
const pendingIdx = ref(null)
let resetTimer = null

function onClick(a, i) {
  if (a.confirm) {
    if (pendingIdx.value === i) {
      clearTimeout(resetTimer)
      pendingIdx.value = null
      a.onClick()
      return
    }
    pendingIdx.value = i
    clearTimeout(resetTimer)
    resetTimer = setTimeout(() => {
      pendingIdx.value = null
    }, 2500)
    return
  }
  a.onClick()
}
</script>

<style scoped>
.bulk-fab-row {
  position: fixed;
  /* Совпадает с FabButton.vue: 80px = mobile-nav (64) + 16px gap. */
  bottom: 80px;
  right: 16px;
  display: flex;
  gap: 12px;
  z-index: 200;
}
.bulk-fab {
  width: 56px;
  height: 56px;
  /* 3px = единый радиус с FabButton.vue / NCard. */
  border-radius: 3px;
  border: 0;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  transition:
    transform 0.12s,
    box-shadow 0.12s,
    background 0.15s;
  -webkit-tap-highlight-color: transparent;
}
.bulk-fab:active {
  transform: translateY(1px);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
}
.bulk-fab:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
/* Нейтральный FAB (toggle Hide/Show, Cancel selection) — surface bg,
   text-2 цвет, чтобы не конкурировал с destructive-FAB'ом по контрасту. */
.bulk-fab-default {
  background: v-bind('palette.surfaceAlt');
  color: v-bind('palette.text1');
}
.bulk-fab-danger {
  background: #d03050;
}
.bulk-fab-primary {
  background: #2080f0;
}
/* Pending-стейт для confirm-вариантов: пульсирует тем же тоном — намёк, что
   следующий тап подтвердит. */
.bulk-fab.is-pending {
  animation: bulk-fab-pulse 0.9s ease-in-out infinite;
}
@keyframes bulk-fab-pulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(0.92);
  }
}
</style>
