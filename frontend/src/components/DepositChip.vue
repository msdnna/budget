<template>
  <NDropdown
    v-if="editable"
    trigger="click"
    placement="bottom-start"
    :options="dropdownOptions"
    @select="onSelect"
  >
    <button
      type="button"
      class="dep-chip dep-chip-btn"
      :title="`Счёт: ${meta.label} (нажмите, чтобы изменить)`"
    >
      <NIcon :component="meta.icon" :size="iconSize" />
      <span v-if="withLabel" class="dep-chip-label">{{ meta.shortLabel }}</span>
    </button>
  </NDropdown>
  <span v-else class="dep-chip" :title="`Счёт: ${meta.label}`">
    <NIcon :component="meta.icon" :size="iconSize" />
    <span v-if="withLabel" class="dep-chip-label">{{ meta.shortLabel }}</span>
  </span>
</template>

<script setup>
import { computed, h } from 'vue'
import { NDropdown, NIcon } from 'naive-ui'
import { DEPOSITS, depositMeta, normalizeDeposit } from '@/utils/deposit'

const props = defineProps({
  modelValue: { type: String, default: 'bank' },
  editable: { type: Boolean, default: false },
  withLabel: { type: Boolean, default: false },
  iconSize: { type: Number, default: 16 },
})

const emit = defineEmits(['update:modelValue', 'change'])

const meta = computed(() => depositMeta(normalizeDeposit(props.modelValue)))

const dropdownOptions = computed(() =>
  DEPOSITS.map((d) => ({
    key: d.value,
    label: d.label,
    icon: () => h(NIcon, { component: d.icon }),
  })),
)

function onSelect(key) {
  const next = normalizeDeposit(key)
  if (next === props.modelValue) return
  emit('update:modelValue', next)
  emit('change', next)
}
</script>

<style scoped>
.dep-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  vertical-align: middle;
  line-height: 1;
  color: var(--text-secondary, currentColor);
}
.dep-chip-btn {
  background: transparent;
  border: none;
  padding: 2px 4px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--text-secondary, currentColor);
}
.dep-chip-btn:hover {
  background: var(--hover-color, rgba(0, 0, 0, 0.06));
}
.dep-chip-label {
  font-size: 12px;
  line-height: 1;
}
</style>
