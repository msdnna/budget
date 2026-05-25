<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    :style="{ width: '480px', maxWidth: '92vw' }"
    :bordered="false"
    :mask-closable="!loading"
    :close-on-esc="!loading"
    @update:show="(v) => !v && emit('close')"
  >
    <div class="split-row split-header">
      <n-text>Делить на:</n-text>
      <n-input-number
        v-model:value="count"
        :min="2"
        :max="10"
        :precision="0"
        size="small"
        style="width: 80px"
        @update:value="onCountChange"
      />
    </div>

    <n-alert v-if="parentSummary" type="info" style="margin: 8px 0 12px">
      <n-text>{{ parentSummary }}</n-text>
    </n-alert>

    <div class="split-list">
      <div v-for="(p, i) in parts" :key="i" class="split-item">
        <n-text class="split-index" depth="3">#{{ i + 1 }}</n-text>
        <n-input-number
          v-model:value="p.amount"
          :min="0.01"
          :precision="2"
          size="small"
          style="flex: 1"
          placeholder="0.00"
          @update:value="onAmountChange(i)"
        />
        <DepositChip
          :model-value="p.deposit"
          editable
          with-label
          @update:model-value="(v) => (p.deposit = v)"
        />
      </div>
    </div>

    <div class="split-sum" :class="{ 'split-sum-mismatch': !sumMatches }">
      <n-text depth="2">Сумма частей:</n-text>
      <n-text strong>{{ sum.toLocaleString('ru-RU') }} ₽</n-text>
      <n-text v-if="!sumMatches" depth="3" style="margin-left: 6px">
        ({{ delta > 0 ? '+' : '' }}{{ delta.toLocaleString('ru-RU') }} ₽)
      </n-text>
    </div>

    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <n-button :disabled="loading" @click="emit('close')">Отмена</n-button>
        <n-button
          type="primary"
          :loading="loading"
          :disabled="!sumMatches || hasInvalidPart"
          @click="onConfirm"
        >
          Разделить
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { NAlert, NButton, NInputNumber, NModal, NText } from 'naive-ui'
import DepositChip from '@/components/DepositChip.vue'
import { DEPOSIT_BANK, DEPOSIT_CASH } from '@/utils/deposit'

const props = defineProps({
  show: { type: Boolean, default: false },
  transaction: { type: Object, default: null },
})

const emit = defineEmits(['close', 'confirmed'])

const count = ref(2)
const parts = ref([])
const loading = ref(false)

const total = computed(() => props.transaction?.amount || 0)

// On every show, build prefilled parts. Default split: 50/50 across bank/cash;
// the user can drag the slider or edit amounts to taste.
watch(
  () => props.show,
  (v) => {
    if (!v) return
    count.value = 2
    rebuild(2)
  },
)

function rebuild(n) {
  const base = total.value
  // Even split rounded to 2 decimals; remainder lands in the last slot so
  // the sum stays exact (validation forbids any drift).
  const each = Math.round((base / n) * 100) / 100
  const rows = []
  let used = 0
  for (let i = 0; i < n; i++) {
    const amt = i === n - 1 ? Math.round((base - used) * 100) / 100 : each
    used += amt
    rows.push({
      amount: amt,
      deposit: i === 0 ? DEPOSIT_BANK : DEPOSIT_CASH,
    })
  }
  parts.value = rows
}

function onCountChange(v) {
  if (!v || v < 2) return
  rebuild(v)
}

// When the user types in any row except the last, the last row auto-balances
// so the sum stays equal to the parent amount. If the user edits the last
// row, leave it alone (the «sum mismatch» warning catches drift).
function onAmountChange(idx) {
  if (idx === parts.value.length - 1) return
  const sumExceptLast = parts.value.slice(0, -1).reduce((a, p) => a + (p.amount || 0), 0)
  const remainder = Math.round((total.value - sumExceptLast) * 100) / 100
  parts.value[parts.value.length - 1].amount = remainder
}

const sum = computed(() => parts.value.reduce((acc, p) => acc + (Number(p.amount) || 0), 0))
const delta = computed(() => Math.round((sum.value - total.value) * 100) / 100)
const sumMatches = computed(() => Math.abs(delta.value) < 0.01)
const hasInvalidPart = computed(() => parts.value.some((p) => !p.amount || p.amount <= 0))

const title = computed(() => `Разделить доход на части`)
const parentSummary = computed(() => {
  const t = props.transaction
  if (!t) return ''
  const date = new Date(t.date).toLocaleDateString('ru-RU')
  const source = t.source ? ` · ${t.source}` : ''
  return `${date} · ${t.category}${source} — ${t.amount.toLocaleString('ru-RU')} ₽`
})

async function onConfirm() {
  if (!sumMatches.value || hasInvalidPart.value) return
  loading.value = true
  try {
    const splits = parts.value.map((p) => ({
      amount: Number(p.amount),
      deposit: p.deposit,
    }))
    emit('confirmed', splits)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.split-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.split-header {
  margin-bottom: 4px;
}
.split-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 4px 0 12px;
}
.split-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.split-index {
  width: 24px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.split-sum {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0 4px;
  border-top: 1px solid var(--n-border-color, rgba(0, 0, 0, 0.08));
}
.split-sum-mismatch :deep(.n-text):nth-child(2) {
  color: var(--error-color, #e88080);
}
</style>
