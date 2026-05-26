<template>
  <div class="settings-tabs" :style="cssVars">
    <button
      v-for="t in visibleTabs"
      :key="t.key"
      type="button"
      class="settings-tab"
      :class="{ active: activeKey === t.key }"
      @click="go(t.key)"
    >
      {{ t.label }}
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'

// Tabs flagged `adminOnly` hide for regular users; Telegram is visible to
// everyone because the binding is per-user.
const TABS = [
  { key: 'settings/categories', label: 'Категории', adminOnly: true },
  { key: 'settings/users', label: 'Пользователи', adminOnly: true },
  { key: 'settings/glossary', label: 'Глоссарий', adminOnly: true },
  { key: 'settings/portability', label: 'Импорт/экспорт', adminOnly: true },
  { key: 'settings/telegram', label: 'Telegram', adminOnly: false },
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { palette, primaryColor, onPrimaryColor } = storeToRefs(useThemeStore())

const visibleTabs = computed(() => TABS.filter((t) => !t.adminOnly || auth.isAdmin))

const activeKey = computed(() => route.path.replace(/^\//, ''))
function go(key) {
  router.push('/' + key)
}

const cssVars = computed(() => ({
  // `cardSurface` (а не `surface`) совпадает с фоном `.admin-sections`
  // (см. AdminCategoriesView.vue — mobile section-row tab strip). На
  // dark-теме разница заметна: surface = #1e1e1e (светлее), cardSurface
  // = #18181c (темнее, ближе к page bg). Если оставить surface — верхняя
  // панель табов «выпирает» относительно нижней.
  '--st-surface': palette.value.cardSurface,
  '--st-border': palette.value.border,
  '--st-text2': palette.value.text2,
  '--st-text1': palette.value.text1,
  '--st-primary': primaryColor.value,
  '--st-on-primary': onPrimaryColor.value,
}))
</script>

<style scoped>
.settings-tabs {
  display: flex;
  /* Container повторяет `.admin-sections` mobile-tab strip
     (см. AdminCategoriesView.vue): тот же gap, padding, border-radius
     и bg — чтобы верхняя и нижняя tab-полоски в /settings/categories
     визуально читались как один компонент. Активная таба, тем не менее,
     оставлена с solid primary bg + on-primary text (не soft tint как у
     section-row.active) — пользовательский явный выбор. */
  gap: 4px;
  background: var(--st-surface);
  border: 1px solid var(--st-border);
  border-radius: 3px;
  padding: 6px;
  margin-bottom: 12px;
}
.settings-tab {
  flex: 1 1 0;
  border: 0;
  background: transparent;
  color: var(--st-text2);
  padding: 8px 10px;
  border-radius: 3px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background 0.15s,
    color 0.15s;
}
.settings-tab.active {
  background: var(--st-primary);
  color: var(--st-on-primary, #fff);
}
</style>
