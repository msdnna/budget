<template>
  <n-modal
    :show="show"
    preset="card"
    :title="modalTitle"
    style="max-width: 640px"
    @update:show="(v) => emit('update:show', v)"
  >
    <template v-if="item">
      <p style="margin: 0 0 12px; opacity: 0.8; font-size: 13px">
        Выберите существующий расход, который относится к
        <strong>«{{ item.name }}»</strong>
        . Категория и связь с прогнозом будут проставлены автоматически.
      </p>
      <n-input
        v-model:value="query"
        placeholder="Поиск: назначение, категория, сумма…"
        clearable
        style="margin-bottom: 12px"
      />
      <n-spin :show="loading">
        <n-empty
          v-if="!loading && !filteredCandidates.length"
          :description="
            query
              ? 'Ничего не найдено'
              : 'Нет несвязанных расходов — все расходы уже привязаны или относятся к запросам детализации'
          "
          style="padding: 24px 0"
        />
        <n-scrollbar v-else style="max-height: 460px">
          <div class="lx-list">
            <div
              v-for="tx in filteredCandidates"
              :key="tx.id"
              class="lx-row"
              :class="{ 'lx-row-busy': busyId === tx.id }"
            >
              <div class="lx-row-main">
                <div class="lx-row-top">
                  <span class="lx-date">{{ formatDate(tx.date) }}</span>
                  <span class="lx-amount" :style="{ color: palette.expense }">
                    {{ Math.round(tx.amount).toLocaleString('ru-RU') }} ₽
                  </span>
                </div>
                <div class="lx-row-bot">
                  <CategoryLabel
                    v-if="tx.category"
                    :name="tx.category"
                    :category="catStore.findAcrossSections(tx.category)"
                    :size="12"
                  />
                  <span v-if="tx.purpose" class="lx-purpose">· {{ tx.purpose }}</span>
                  <span v-if="tx.description" class="lx-desc">· {{ tx.description }}</span>
                </div>
              </div>
              <ConfirmActionButton
                label="Привязать"
                confirm-label="Подтвердить?"
                type="primary"
                :loading="busyId === tx.id"
                :disabled="busyId !== null && busyId !== tx.id"
                @confirm="linkOne(tx)"
              />
            </div>
          </div>
        </n-scrollbar>
      </n-spin>
    </template>
  </n-modal>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { NModal, NInput, NSpin, NEmpty, NScrollbar, useMessage } from 'naive-ui'
import { storeToRefs } from 'pinia'
import { transactions as txApi, wishlist as wlApi } from '@/api'
import { useCategoriesStore } from '@/stores/categories'
import { useThemeStore } from '@/stores/theme'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'
import CategoryLabel from '@/components/CategoryLabel.vue'

const props = defineProps({
  show: { type: Boolean, default: false },
  item: { type: Object, default: null },
})
const emit = defineEmits(['update:show', 'linked'])

const catStore = useCategoriesStore()
const { palette } = storeToRefs(useThemeStore())
const message = useMessage()

const loading = ref(false)
const candidates = ref([])
const query = ref('')
const busyId = ref(null)

const modalTitle = computed(() => {
  if (!props.item) return 'Привязать существующий расход'
  return props.item.frequency && props.item.frequency !== 'once'
    ? 'Привязать оплату'
    : 'Привязать покупку'
})

const filteredCandidates = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return candidates.value
  return candidates.value.filter((tx) => {
    return (
      (tx.purpose || '').toLowerCase().includes(q) ||
      (tx.description || '').toLowerCase().includes(q) ||
      (tx.category || '').toLowerCase().includes(q) ||
      String(Math.round(tx.amount)).includes(q)
    )
  })
})

async function loadCandidates() {
  loading.value = true
  try {
    // Limit 100 — modal isn't paginated; 100 unlinked expenses is plenty for
    // the typical family-budget timescale. If a user has more, the search box
    // narrows it.
    const { data } = await txApi.list({ unlinked: true, limit: 100 })
    candidates.value = data?.data || []
  } catch (e) {
    message.error(`Не удалось загрузить расходы: ${e.message || e}`)
    candidates.value = []
  } finally {
    loading.value = false
  }
}

async function linkOne(tx) {
  if (!props.item) return
  busyId.value = tx.id
  try {
    await wlApi.linkExisting(props.item.id, tx.id)
    message.success('Расход привязан')
    emit('linked', { itemId: props.item.id, txId: tx.id })
    emit('update:show', false)
  } catch (e) {
    message.error(e.message || 'Не удалось привязать расход')
  } finally {
    busyId.value = null
  }
}

function formatDate(d) {
  if (!d) return ''
  try {
    return new Date(d).toLocaleDateString('ru-RU')
  } catch {
    return String(d).slice(0, 10)
  }
}

watch(
  () => props.show,
  (v) => {
    if (v) {
      query.value = ''
      busyId.value = null
      loadCandidates()
    }
  },
)
</script>

<style scoped>
.lx-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lx-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--st-border);
  border-radius: 6px;
  background: var(--st-surface);
  transition: background 0.15s ease;
}
.lx-row:hover {
  background: var(--st-hover);
}
.lx-row-busy {
  opacity: 0.7;
}
.lx-row-main {
  flex: 1;
  min-width: 0;
}
.lx-row-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}
.lx-date {
  opacity: 0.75;
}
.lx-amount {
  font-weight: 600;
  white-space: nowrap;
}
.lx-row-bot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.85;
  min-width: 0;
}
.lx-purpose,
.lx-desc {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
