<template>
  <div>
    <div v-if="!items.length" class="notif-empty">Уведомлений нет</div>
    <div v-else class="notif-list">
      <div
        v-for="n in items"
        :key="n.id"
        class="notif-row"
        :class="{ unread: !n.read }"
        :title="formatTitle(n)"
      >
        <div class="notif-row-icon" :style="{ background: iconBg(n) }">
          <span style="font-size: 14px">⚠</span>
        </div>
        <div class="notif-row-body">
          <div class="notif-row-title">{{ titleFor(n) }}</div>
          <div class="notif-row-meta">
            {{ formatMoney(n.spent) }} / {{ formatMoney(n.limit) }} ₽ · {{ n.period }}
          </div>
        </div>
      </div>
    </div>
    <div class="notif-footer">
      <n-button size="tiny" text type="primary" :disabled="!hasUnread" @click="$emit('read-all')">
        Прочитать все
      </n-button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { NButton } from 'naive-ui'

const props = defineProps({
  items: { type: Array, required: true },
})
defineEmits(['read-all'])

const hasUnread = computed(() => props.items.some((n) => !n.read))

function titleFor(n) {
  if (n.type === 'global_limit_exceeded') return 'Превышен общий лимит расходов'
  if (n.type === 'category_limit_exceeded')
    return `Превышен лимит: ${n.category_name || 'категория'}`
  return 'Уведомление'
}

function formatTitle(n) {
  const d = new Date(n.created_at)
  return d.toLocaleString('ru-RU')
}

function iconBg(_n) {
  // Reuse the same red/amber/green palette as the limit progress bars.
  // Exceeded = red; the rest of the future notification types can override
  // via a per-type branch when they land.
  return '#EF4444'
}

function formatMoney(value) {
  if (value == null) return ''
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value)
}
</script>

<style scoped>
.notif-empty {
  padding: 18px 6px;
  text-align: center;
  font-size: 13px;
  opacity: 0.7;
}
.notif-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 320px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(127, 127, 127, 0.3) transparent;
}
.notif-list::-webkit-scrollbar {
  width: 6px;
}
.notif-list::-webkit-scrollbar-thumb {
  background: rgba(127, 127, 127, 0.25);
  border-radius: 3px;
}
.notif-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 6px;
  transition: background 0.12s;
}
.notif-row.unread {
  background: rgba(239, 68, 68, 0.06);
}
.notif-row-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
}
.notif-row-body {
  flex: 1 1 auto;
  min-width: 0;
}
.notif-row-title {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notif-row-meta {
  font-size: 11px;
  opacity: 0.7;
}
.notif-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
  padding-right: 4px;
}
</style>
