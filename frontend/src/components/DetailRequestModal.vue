<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    class="dr-modal"
    @update:show="(v) => !v && $emit('close')"
  >
    <n-spin :show="loading">
      <div v-if="view" class="dr-body">
        <!-- Progress card -->
        <div class="dr-progress" :style="progressCardStyle">
          <div class="dr-progress-row">
            <span class="dr-progress-label">Прогресс</span>
            <span class="dr-progress-num">
              <span :class="{ blurred: valuesHidden }">
                {{ totalChildren.toLocaleString('ru-RU') }}
              </span>
              /
              <span :class="{ blurred: valuesHidden }">
                {{ targetAmount.toLocaleString('ru-RU') }}
              </span>
              ₽
            </span>
          </div>
          <n-progress
            type="line"
            :percentage="progressPercent"
            :height="6"
            :show-indicator="false"
            :color="progressColor"
            :rail-color="palette.border"
            style="margin-top: 6px"
          />
          <div class="dr-progress-meta">
            <span>Транзакций: {{ view.children?.length || 0 }}</span>
            <span v-if="overshoot > 0" class="dr-overshoot">
              +{{ overshoot.toLocaleString('ru-RU') }} ₽ сверх
            </span>
            <span v-else-if="remainder > 0" class="dr-remainder">
              остаток в баланс: {{ remainder.toLocaleString('ru-RU') }} ₽
            </span>
          </div>
        </div>

        <!-- Two-column body: form on the left, children on the right
             (mirrors the Income/Expenses page layout). -->
        <div class="dr-cols">
          <n-card
            v-if="canEdit"
            title="Добавить расход"
            size="small"
            :bordered="false"
            class="dr-col"
          >
            <n-form ref="childFormRef" :model="childForm" :rules="childRules" label-placement="top">
              <n-grid :cols="2" :x-gap="12" :item-responsive="true">
                <n-grid-item span="2 s:1">
                  <n-form-item label="Сумма (₽)" path="amount">
                    <n-input-number
                      v-model:value="childForm.amount"
                      :min="0.01"
                      :precision="2"
                      style="width: 100%"
                      placeholder="0.00"
                    />
                  </n-form-item>
                </n-grid-item>
                <n-grid-item span="2 s:1">
                  <n-form-item label="Дата" path="date">
                    <n-date-picker v-model:value="childForm.date" type="date" style="width: 100%" />
                  </n-form-item>
                </n-grid-item>
                <n-grid-item span="2">
                  <n-form-item label="Категория" path="category">
                    <n-select
                      v-model:value="childForm.category"
                      :options="categoryOptions"
                      filterable
                      tag
                      :on-create="(v) => ({ label: v, value: v, id: null, is_default: false })"
                      to="body"
                      placeholder="Выберите или введите категорию"
                    />
                  </n-form-item>
                </n-grid-item>
                <n-grid-item span="2">
                  <n-form-item label="Назначение">
                    <n-input v-model:value="childForm.purpose" placeholder="Куда потрачено" />
                  </n-form-item>
                </n-grid-item>
                <n-grid-item span="2">
                  <n-form-item label="Описание">
                    <n-input v-model:value="childForm.description" placeholder="Дополнительно" />
                  </n-form-item>
                </n-grid-item>
              </n-grid>
              <n-button type="primary" :loading="adding" block @click="addChild">
                Добавить расход
              </n-button>
            </n-form>
          </n-card>

          <n-card title="Расходы по запросу" size="small" :bordered="false" class="dr-col">
            <n-empty v-if="!view.children?.length" description="Пока нет расходов" />
            <n-list v-else hoverable bordered>
              <n-list-item v-for="c in view.children" :key="c.id">
                <n-space
                  align="center"
                  justify="space-between"
                  style="width: 100%; flex-wrap: nowrap"
                >
                  <div style="min-width: 0">
                    <n-text strong>{{ c.category }}</n-text>
                    <n-text depth="3" style="font-size: 12px; margin-left: 8px">
                      {{ new Date(c.date).toLocaleDateString('ru-RU') }}
                    </n-text>
                    <div
                      v-if="c.purpose || c.description"
                      style="font-size: 12px; opacity: 0.7; margin-top: 2px"
                    >
                      {{ c.purpose }}
                      <span v-if="c.purpose && c.description">·</span>
                      {{ c.description }}
                    </div>
                  </div>
                  <n-space :size="6" align="center" style="flex-shrink: 0">
                    <n-text
                      :style="{ color: palette.expense, fontWeight: 600 }"
                      :class="{ blurred: valuesHidden }"
                    >
                      −{{ c.amount.toLocaleString('ru-RU') }} ₽
                    </n-text>
                    <n-popconfirm v-if="canEdit" @positive-click="removeChild(c.id)">
                      <template #trigger>
                        <n-button size="tiny" quaternary type="error">✕</n-button>
                      </template>
                      Удалить расход?
                    </n-popconfirm>
                  </n-space>
                </n-space>
              </n-list-item>
            </n-list>
          </n-card>
        </div>
      </div>
    </n-spin>

    <template #footer>
      <n-space justify="space-between" align="center">
        <n-text depth="3" style="font-size: 12px">
          <span v-if="view?.request?.assignee">
            Исполнитель: {{ view.request.assignee.display_name }}
          </span>
          <span v-if="view?.request?.creator">
            · Создал: {{ view.request.creator.display_name }}
          </span>
        </n-text>
        <n-space>
          <n-button @click="$emit('close')">Закрыть</n-button>
          <n-popconfirm v-if="canCancel" @positive-click="cancel">
            <template #trigger>
              <n-button type="error" ghost :loading="busy">Отменить запрос</n-button>
            </template>
            Отменить запрос? Все добавленные расходы будут удалены.
          </n-popconfirm>
          <n-popconfirm v-if="canFinish" @positive-click="finish">
            <template #trigger>
              <n-button type="primary" :loading="busy" :disabled="!view?.children?.length">
                Готово
              </n-button>
            </template>
            Завершить запрос? Исходная транзакция будет заменена внесёнными расходами.
          </n-popconfirm>
        </n-space>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import {
  NModal,
  NSpin,
  NCard,
  NList,
  NListItem,
  NSpace,
  NText,
  NButton,
  NPopconfirm,
  NEmpty,
  NProgress,
  NForm,
  NFormItem,
  NGrid,
  NGridItem,
  NInputNumber,
  NDatePicker,
  NSelect,
  NInput,
  useMessage,
} from 'naive-ui'
import { detailRequests as api, transactions as txApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useCategoriesStore } from '@/stores/categories'
import { storeToRefs } from 'pinia'

const props = defineProps({
  show: Boolean,
  requestId: String,
})
const emit = defineEmits(['close', 'updated', 'closed'])

const message = useMessage()
const auth = useAuthStore()
const { palette, valuesHidden, primaryColor } = storeToRefs(useThemeStore())
const catStore = useCategoriesStore()
const categoryOptions = computed(() => catStore.options('expense'))

const view = ref(null)
const loading = ref(false)
const busy = ref(false)
const adding = ref(false)
const childFormRef = ref(null)
const childForm = ref({
  amount: null,
  date: Date.now(),
  category: '',
  purpose: '',
  description: '',
})

const childRules = {
  amount: [{ required: true, type: 'number', message: 'Введите сумму', trigger: 'blur' }],
  date: [{ required: true, type: 'number', message: 'Выберите дату', trigger: 'change' }],
  category: [{ required: true, message: 'Выберите категорию', trigger: 'change' }],
}

const targetAmount = computed(() => view.value?.request?.target_amount || 0)
const totalChildren = computed(() =>
  (view.value?.children || []).reduce((sum, c) => sum + c.amount, 0),
)
const remainder = computed(() => Math.max(0, targetAmount.value - totalChildren.value))
const overshoot = computed(() => Math.max(0, totalChildren.value - targetAmount.value))
const progressPercent = computed(() => {
  if (!targetAmount.value) return 0
  return Math.min(100, (totalChildren.value / targetAmount.value) * 100)
})
const progressColor = computed(() => {
  if (overshoot.value > 0) return '#f0a020'
  if (progressPercent.value >= 100) return palette.value.income
  return primaryColor.value
})

const progressCardStyle = computed(() => ({
  background: palette.value.surfaceAlt || palette.value.surface,
  border: `1px solid ${palette.value.border}`,
  borderRadius: '8px',
  padding: '12px 14px',
}))

const isAssignee = computed(() => view.value?.request?.assignee?.user_id === auth.user?.user_id)
const isCreator = computed(() => view.value?.request?.creator?.user_id === auth.user?.user_id)
const isOpen = computed(() => view.value?.request?.status === 'open')
const canEdit = computed(() => isOpen.value && isAssignee.value)
const canFinish = computed(() => isOpen.value && isAssignee.value)
const canCancel = computed(() => isOpen.value && isCreator.value)

const title = computed(() => {
  if (!view.value) return 'Запрос на детализацию'
  const status = isOpen.value ? '' : ' (закрыт)'
  return `Запрос на детализацию${status}`
})

async function load() {
  if (!props.requestId) return
  loading.value = true
  try {
    const { data } = await api.get(props.requestId)
    view.value = data
  } catch (e) {
    message.error(e.message)
  } finally {
    loading.value = false
  }
}

async function addChild() {
  try {
    await childFormRef.value?.validate()
  } catch {
    return
  }
  adding.value = true
  try {
    const cat = childForm.value.category
    if (cat && !catStore.bySection.expense.find((c) => c.name === cat)) {
      await catStore.add('expense', cat).catch(() => {})
    }
    const d = new Date(childForm.value.date)
    const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    await api.addChild(props.requestId, {
      type: 'expense',
      amount: childForm.value.amount,
      date,
      category: cat,
      purpose: childForm.value.purpose,
      description: childForm.value.description,
    })
    catStore.recordUse('expense', cat)
    childForm.value = { amount: null, date: Date.now(), category: '', purpose: '', description: '' }
    await load()
    emit('updated')
  } catch (e) {
    message.error(e.message)
  } finally {
    adding.value = false
  }
}

async function removeChild(id) {
  try {
    await txApi.remove(id)
    await load()
    emit('updated')
  } catch (e) {
    message.error(e.message)
  }
}

async function finish() {
  busy.value = true
  try {
    await api.close(props.requestId)
    message.success('Запрос завершён')
    emit('closed')
    emit('close')
  } catch (e) {
    message.error(e.message)
  } finally {
    busy.value = false
  }
}

async function cancel() {
  busy.value = true
  try {
    await api.cancel(props.requestId)
    message.success('Запрос отменён')
    emit('closed')
    emit('close')
  } catch (e) {
    message.error(e.message)
  } finally {
    busy.value = false
  }
}

watch(
  () => [props.show, props.requestId],
  ([s, id]) => {
    if (s && id) load()
    else if (!s) view.value = null
  },
  { immediate: true },
)
</script>

<style scoped>
.dr-progress-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.dr-progress-label {
  font-size: 12px;
  opacity: 0.7;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.dr-progress-num {
  font-size: 16px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.dr-progress-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
  opacity: 0.65;
}
.dr-overshoot {
  color: var(--n-color-warning, #f0a020);
}
.dr-remainder {
  font-style: italic;
}
.blurred {
  filter: blur(7px);
  user-select: none;
}

.dr-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dr-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  align-items: start;
}
.dr-col {
  min-width: 0;
}
@media (max-width: 720px) {
  .dr-cols {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
/* Top-level (un-scoped) — affects only this modal via the unique class.
   Naive UI centers the modal via flex; setting `margin` would knock it off
   center. Constrain size only — viewport gap comes from the calc(). */
.n-modal.dr-modal {
  width: min(1024px, calc(100vw - 48px));
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
}
.n-modal.dr-modal > .n-card__content {
  overflow-y: auto;
}
</style>
