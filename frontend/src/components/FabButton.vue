<template>
  <button class="fab" :aria-label="title" :title="title" @click="$emit('click')">
    <n-icon :component="AddOutline" :size="28" />
  </button>
</template>

<script setup>
import { NIcon } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'

defineProps({
  title: { type: String, default: 'Добавить' },
})
defineEmits(['click'])

// `primaryColor` подмешиваем в scoped-style через v-bind — FAB красится в
// активный акцент темы автоматически.
const { primaryColor } = storeToRefs(useThemeStore())
</script>

<style scoped>
/* Circular FAB, фиксирован bottom-right поверх bottom-nav. Появляется
   только на мобильных вьюшках; родитель решает через v-if. */
.fab {
  position: fixed;
  bottom: 80px; /* mobile-nav height (64) + ~16px gap */
  right: 16px;
  width: 56px;
  height: 56px;
  /* Квадратная FAB-кнопка со скруглением 3px (как у NCard/NButton в общей
     теме). Раньше был круг — выбивался из общего стиля приложения. */
  border-radius: 3px;
  background: v-bind(primaryColor);
  color: #fff;
  border: 0;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  z-index: 200;
  transition:
    transform 0.12s,
    box-shadow 0.12s;
  -webkit-tap-highlight-color: transparent;
}
.fab:active {
  transform: translateY(1px);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
}
</style>
