<template>
  <div>
    <div v-if="!items.length" class="dr-empty">{{ empty }}</div>
    <div v-else class="dr-rows">
      <div
        v-for="r in items"
        :key="r.id"
        class="dr-row"
        @click="$emit('open', r.id)"
      >
        <UserAvatar
          :displayName="r.assignee?.display_name || ''"
          :avatarUrl="r.assignee?.avatar_url || ''"
          :size="22"
        />
        <div class="dr-row-text">
          <div class="dr-row-amount">{{ r.target_amount.toLocaleString('ru-RU') }} ₽</div>
          <div class="dr-row-meta">
            {{ r.assignee?.display_name }} · {{ new Date(r.created_at).toLocaleDateString('ru-RU') }}
          </div>
        </div>
        <span class="dr-row-tag" :class="r.status === 'open' ? 'open' : 'closed'">
          {{ r.status === 'open' ? 'открыт' : 'закрыт' }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import UserAvatar from '@/components/UserAvatar.vue'

defineProps({ items: { type: Array, default: () => [] }, empty: String })
defineEmits(['open'])
</script>

<style scoped>
.dr-empty {
  font-size: 13px; opacity: 0.6;
  text-align: center; padding: 16px 0;
}
.dr-rows { display: flex; flex-direction: column; gap: 4px; max-height: 320px; overflow-y: auto; }
.dr-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 6px; border-radius: 4px; cursor: pointer;
  transition: background 0.15s;
}
.dr-row:hover { background: var(--hover, rgba(128,128,128,0.08)); }
.dr-row-text { flex: 1; min-width: 0; }
.dr-row-amount { font-size: 13px; font-weight: 600; }
.dr-row-meta { font-size: 11px; opacity: 0.65; }
.dr-row-tag {
  font-size: 10px;
  padding: 1px 6px; border-radius: 8px;
  font-weight: 600; text-transform: lowercase;
}
.dr-row-tag.open { background: rgba(240,160,32,0.18); color: #c97c10; }
.dr-row-tag.closed { background: rgba(128,128,128,0.18); color: #888; }
</style>
