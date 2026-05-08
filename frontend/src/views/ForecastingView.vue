<template>
  <div>
    <!-- Forecast summary — 4 cards: total / 3-mo avg / regular / wishlist.
         The two contribution numbers come from the api 1.12.0 split:
         regular_contrib (recurring only) and wishlist_contrib − regular_contrib
         (one-off only). -->
    <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true" style="margin-bottom: 16px;">
      <n-grid-item span="4 s:2 m:1">
        <n-card>
          <n-statistic label="Прогноз на месяц" :value="Math.round(forecast.total_monthly)" :precision="0">
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="4 s:2 m:1">
        <n-card>
          <n-statistic label="Среднее (3 мес.)" :value="Math.round(forecast.historical_avg)" :precision="0">
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="4 s:2 m:1">
        <n-card>
          <n-statistic label="Регулярные расходы / мес" :value="Math.round(forecast.regular_contrib || 0)" :precision="0">
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="4 s:2 m:1">
        <n-card>
          <n-statistic label="Список желаний / мес" :value="Math.round(wishlistOnlyContrib)" :precision="0">
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
    </n-grid>

    <n-grid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true" style="margin-bottom: 16px;">
      <n-grid-item span="2 m:1">
        <n-card title="Прогноз по категориям">
          <n-spin :show="loadingForecast">
            <v-chart v-if="forecast.breakdown?.length" :option="forecastPieOption" style="height:300px" autoresize />
            <n-empty v-else description="Добавьте транзакции или позиции в список желаний" style="padding: 60px 0;" />
          </n-spin>
        </n-card>
      </n-grid-item>

      <n-grid-item span="2 m:1">
        <!-- Outer card holds the section title; inner sub-cards render each
             item with consistent flex columns (title / amount / actions). -->
        <n-card title="Регулярные расходы">
          <n-spin :show="loadingForecast">
            <n-empty v-if="!forecast.regular_items?.length" description="Нет регулярных позиций" style="padding: 30px 0;" />
            <n-space v-else vertical :size="8">
              <n-card v-for="item in forecast.regular_items" :key="item.id" size="small" :bordered="true" embedded>
                <div class="regular-row">
                  <div class="regular-row__main">
                    <div class="regular-row__title">
                      <n-text :style="{
                        fontWeight: 500,
                        textDecoration: item.paid_this_period ? 'line-through' : 'none',
                        color: item.paid_this_period ? palette.text3 : 'inherit',
                      }">{{ item.name }}</n-text>
                      <n-tag type="info" size="small" round>{{ freqLabel(item.frequency) }}</n-tag>
                      <n-tag v-if="item.paid_this_period" type="success" size="small" round>
                        Оплачено · {{ Math.round(item.paid_amount).toLocaleString('ru-RU') }} ₽
                      </n-tag>
                    </div>
                    <n-text depth="3" style="font-size:12px">
                      {{ item.category }}<template v-if="item.paid_this_period && item.next_due_date">
                        &nbsp;·&nbsp;следующая оплата: {{ formatDueDate(item.next_due_date) }}
                      </template>
                    </n-text>
                  </div>
                  <div class="regular-row__amount">
                    <n-text strong :style="{
                      color: item.paid_this_period ? palette.text3 : palette.expense,
                      textDecoration: item.paid_this_period ? 'line-through' : 'none',
                      whiteSpace: 'nowrap',
                    }">
                      {{ Math.round(item.monthly_cost).toLocaleString('ru-RU') }} {{ freqUnit(item.frequency) }}
                    </n-text>
                  </div>
                  <div class="regular-row__actions">
                    <n-button size="small" type="success" @click="openPayRegular(item)">Оплачено</n-button>
                    <ConfirmActionButton
                      v-if="item.paid_this_period"
                      label="Отменить"
                      type="default"
                      size="small"
                      :loading="cancelingId === item.id"
                      @confirm="cancelRegularPaid(item)"
                    />
                    <n-popconfirm @positive-click="wlStore.remove(item.id).then(loadForecast)">
                      <template #trigger>
                        <n-button size="small" type="error" quaternary title="Удалить">✕</n-button>
                      </template>
                      Удалить эту позицию?
                    </n-popconfirm>
                  </div>
                </div>
              </n-card>
            </n-space>
          </n-spin>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Wishlist management -->
    <n-grid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true">
      <n-grid-item span="2 m:1">
        <n-card title="Добавить">
          <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
            <!-- Type segmented selector decides whether the entry goes into
                 «Список желаний» (frequency=once) or «Регулярные расходы»
                 (monthly/quarterly/yearly). The frequency picker only
                 appears for the recurring branch. -->
            <n-form-item label="Тип" :show-feedback="false">
              <n-radio-group v-model:value="form.kind" name="kind">
                <n-radio-button value="wishlist">Желаемая покупка</n-radio-button>
                <n-radio-button value="regular">Регулярный расход</n-radio-button>
              </n-radio-group>
            </n-form-item>
            <n-grid :cols="2" :x-gap="12" :item-responsive="true" style="margin-top:12px">
              <n-grid-item span="2">
                <n-form-item label="Название" path="name">
                  <n-input v-model:value="form.name" placeholder="Что хочу купить" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2 s:1">
                <n-form-item label="Оценочная стоимость (₽)" path="estimated_cost">
                  <n-input-number v-model:value="form.estimated_cost" :min="1" style="width:100%" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2 s:1">
                <n-form-item label="Категория" path="category">
                  <n-select
                    v-model:value="form.category"
                    :options="categoryOptions"
                    filterable
                    tag
                    :on-create="handleCategoryCreate"
                    :render-option="renderCategoryOption"
                    to="body"
                    placeholder="Выберите или введите категорию"
                  />
                </n-form-item>
              </n-grid-item>
              <n-grid-item v-if="form.kind === 'regular'" span="2 s:1">
                <n-form-item label="Частота">
                  <n-select v-model:value="form.frequency" :options="recurringFrequencyOptions" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2">
                <n-form-item label="Заметки">
                  <n-input v-model:value="form.notes" placeholder="Необязательно" />
                </n-form-item>
              </n-grid-item>
            </n-grid>
            <n-button type="primary" :loading="saving" @click="submit" block>
              {{ form.kind === 'regular' ? 'Добавить в регулярные' : 'Добавить в список' }}
            </n-button>
          </n-form>
        </n-card>
      </n-grid-item>

      <!-- Wishlist table -->
      <n-grid-item span="2 m:1">
        <n-card>
          <template #header>
            <n-space align="center" justify="space-between" style="width:100%">
              <n-text strong>Список желаний</n-text>
              <n-space align="center" :size="8">
                <template v-if="!bulkMode">
                  <n-button size="small" :disabled="!wishlistOnly.length" @click="enterBulkMode">
                    Пакетное редактирование
                  </n-button>
                </template>
                <template v-else>
                  <n-text v-if="selectedIds.size" depth="2" style="font-size:12px">
                    Выбрано: {{ selectedIds.size }}
                  </n-text>
                  <template v-if="selectedIds.size">
                    <ConfirmActionButton
                      v-if="purchasableSelectedCount > 0"
                      :label="allPurchasableSelectedPurchased ? 'Не куплено' : 'Куплено'"
                      :type="allPurchasableSelectedPurchased ? 'default' : 'success'"
                      :loading="bulkBusy"
                      @confirm="bulkTogglePurchased"
                    />
                    <ConfirmActionButton
                      label="Удалить"
                      type="error"
                      :loading="bulkBusy"
                      @confirm="bulkDelete"
                    />
                  </template>
                  <n-button size="small" quaternary @click="exitBulkMode">Отмена</n-button>
                </template>
              </n-space>
            </n-space>
          </template>
          <n-spin :show="wlStore.loading">
            <n-empty v-if="!wishlistOnly.length" description="Список пуст" style="padding: 40px 0;" />
            <n-space v-else vertical :size="8">
              <n-card
                v-for="item in wishlistOnly"
                :key="item.id"
                size="small"
                :bordered="true"
                embedded
                :style="bulkMode && selectedIds.has(item.id) ? `background:${primaryColor}1f` : ''"
              >
                <div class="regular-row">
                  <div class="regular-row__avatar">
                    <template v-if="bulkMode">
                      <div
                        class="bulk-checkbox"
                        :class="{ checked: selectedIds.has(item.id) }"
                        :style="checkboxStyle(selectedIds.has(item.id))"
                        @click="toggleSelect(item.id)"
                      >
                        <svg v-if="selectedIds.has(item.id)" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                          <polyline points="20 6 9 17 4 12" />
                        </svg>
                      </div>
                    </template>
                    <n-tooltip v-else :delay="300">
                      <template #trigger>
                        <div
                          class="user-assign-btn"
                          :class="{ 'no-user': !item.created_by }"
                          @click="openReassign(item.id, 'wishlist')"
                        >
                          <UserAvatar
                            v-if="item.created_by"
                            :displayName="item.created_by.display_name"
                            :avatarUrl="item.created_by.avatar_url || ''"
                            :size="28"
                          />
                          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
                          </svg>
                        </div>
                      </template>
                      {{ item.created_by ? item.created_by.display_name + ' · нажмите для смены' : 'Назначить автора' }}
                    </n-tooltip>
                  </div>
                  <div class="regular-row__main">
                    <div class="regular-row__title">
                      <n-text :style="{
                        fontWeight: 500,
                        textDecoration: item.purchased ? 'line-through' : 'none',
                        color: item.purchased ? palette.text3 : 'inherit',
                      }">{{ item.name }}</n-text>
                      <n-tag v-if="item.purchased" type="success" size="small" round>Куплено</n-tag>
                    </div>
                    <n-text depth="3" style="font-size:12px">
                      {{ item.category }}<template v-if="item.notes">&nbsp;·&nbsp;{{ item.notes }}</template>
                    </n-text>
                  </div>
                  <div class="regular-row__amount">
                    <template v-if="editingId === item.id && !bulkMode">
                      <n-space :size="4" align="center">
                        <n-input-number
                          v-model:value="editValue"
                          :min="1"
                          size="small"
                          style="width:120px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button size="tiny" type="primary" style="padding:0 5px;min-width:24px" @click="confirmEdit(item)">✓</n-button>
                        <n-button size="tiny" style="padding:0 5px;min-width:24px" @click="cancelEdit">✗</n-button>
                      </n-space>
                    </template>
                    <template v-else>
                      <n-text strong :style="{ color: item.purchased ? palette.text3 : primaryColor, whiteSpace: 'nowrap', textDecoration: item.purchased ? 'line-through' : 'none' }">
                        {{ item.estimated_cost.toLocaleString('ru-RU') }} ₽
                      </n-text>
                      <span v-if="!bulkMode" class="inline-edit-icon" @click="startEdit(item)" title="Редактировать стоимость">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                          <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                          <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                        </svg>
                      </span>
                    </template>
                  </div>
                  <div v-if="!bulkMode" class="regular-row__actions">
                    <n-button size="small" @click="wlStore.togglePurchased(item)">
                      {{ item.purchased ? 'Не куплено' : 'Куплено' }}
                    </n-button>
                    <n-popconfirm @positive-click="wlStore.remove(item.id)">
                      <template #trigger>
                        <n-button size="small" type="error" quaternary title="Удалить">✕</n-button>
                      </template>
                      Удалить позицию?
                    </n-popconfirm>
                  </div>
                </div>
              </n-card>
            </n-space>
          </n-spin>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Prefilled expense modal for "Оплачено" on a recurring item.
         Mirrors the same field set as the regular expense form so values
         copy verbatim: purpose ← item.name, description ← item.notes. -->
    <n-modal v-model:show="showPayRegular" preset="card" title="Зафиксировать оплату" style="max-width:460px">
      <template v-if="payRegularItem">
        <n-form label-placement="top">
          <n-form-item label="Сумма (₽)">
            <n-input-number v-model:value="payForm.amount" :min="1" style="width:100%" />
          </n-form-item>
          <n-form-item label="Дата">
            <n-date-picker v-model:formatted-value="payForm.date" value-format="yyyy-MM-dd" type="date" style="width:100%" />
          </n-form-item>
          <n-form-item label="Категория">
            <n-select
              v-model:value="payForm.category"
              :options="expenseCategoryOptions"
              filterable
              tag
              :on-create="handleCategoryCreate"
              to="body"
              placeholder="Выберите или введите категорию"
            />
          </n-form-item>
          <n-form-item label="Назначение">
            <n-input v-model:value="payForm.purpose" placeholder="Например, Интернет" />
          </n-form-item>
          <n-form-item label="Описание">
            <n-input v-model:value="payForm.description" placeholder="Необязательно" />
          </n-form-item>
        </n-form>
        <n-space justify="end">
          <n-button @click="showPayRegular = false">Отмена</n-button>
          <n-button type="primary" :loading="payingBusy" :disabled="!payForm.amount || !payForm.category" @click="confirmPayRegular">
            Сохранить
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- Reassign user modal -->
    <n-modal v-model:show="showReassign" preset="card" title="Изменить автора" style="max-width:320px">
      <n-spin :show="loadingUsers">
        <n-list hoverable clickable>
          <n-list-item v-for="u in usersList" :key="u.user_id" @click="doReassign(u)">
            <n-space align="center">
              <UserAvatar :displayName="u.display_name" :avatarUrl="u.avatar_url || ''" :size="28" />
              <n-text>{{ u.display_name }}</n-text>
            </n-space>
          </n-list-item>
        </n-list>
      </n-spin>
    </n-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, h } from 'vue'
import { useMessage } from 'naive-ui'
import { use } from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import {
  NCard, NGrid, NGridItem, NStatistic, NSpin, NEmpty, NList, NListItem, NThing,
  NText, NTag, NSpace, NButton, NPopconfirm, NForm, NFormItem, NInput,
  NInputNumber, NSelect, NModal, NTooltip, NDatePicker,
  NRadioGroup, NRadioButton
} from 'naive-ui'
import { useWishlistStore } from '@/stores/wishlist'
import { useCategoriesStore } from '@/stores/categories'
import { statistics, users as usersApi, wishlist as wlApi, transactions as txApi } from '@/api'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import UserAvatar from '@/components/UserAvatar.vue'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'

use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

const themeStore = useThemeStore()
const { chartColors, primaryColor, palette } = storeToRefs(themeStore)

const wlStore = useWishlistStore()
const message = useMessage()
const formRef = ref(null)
const saving = ref(false)
const loadingForecast = ref(false)

const forecast = ref({
  total_monthly: 0, historical_avg: 0, wishlist_contrib: 0,
  breakdown: [], regular_items: [], unpurchased_wishlist: []
})

// `kind` selects the destination section: 'wishlist' = one-off purchase,
// 'regular' = recurring expense. The backend still stores both as wishlist
// rows distinguished only by `frequency` — `kind` is purely a UI affordance.
const form = ref({
  kind: 'wishlist', name: '', estimated_cost: null, category: '',
  frequency: 'monthly', notes: ''
})

// Wishlist list excludes recurring items — those live in «Регулярные расходы».
const wishlistOnly = computed(() =>
  wlStore.items.filter(it => !it.frequency || it.frequency === 'once')
)

// ── Inline cost editing ───────────────────────────────────────────────────────

const editingId = ref(null)
const editValue = ref(null)

function startEdit(item) {
  editingId.value = item.id
  editValue.value = item.estimated_cost
}

function cancelEdit() {
  editingId.value = null
  editValue.value = null
}

async function confirmEdit(item) {
  if (!editValue.value) return
  try {
    await wlStore.update(item.id, { estimated_cost: editValue.value })
    await loadForecast()
    message.success('Стоимость обновлена')
  } catch (e) {
    message.error(e.message)
  } finally {
    cancelEdit()
  }
}

// ── Bulk edit ─────────────────────────────────────────────────────────────────

const bulkMode = ref(false)
const selectedIds = ref(new Set())
const bulkBusy = ref(false)

function enterBulkMode() {
  bulkMode.value = true
  selectedIds.value = new Set()
  cancelEdit()
}

function exitBulkMode() {
  bulkMode.value = false
  selectedIds.value = new Set()
}

function toggleSelect(id) {
  const s = new Set(selectedIds.value)
  if (s.has(id)) s.delete(id); else s.add(id)
  selectedIds.value = s
}

const purchasableSelected = computed(() =>
  wlStore.items.filter(it => selectedIds.value.has(it.id) && (!it.frequency || it.frequency === 'once'))
)
const purchasableSelectedCount = computed(() => purchasableSelected.value.length)
const allPurchasableSelectedPurchased = computed(() => {
  const list = purchasableSelected.value
  return list.length > 0 && list.every(it => it.purchased)
})

async function bulkTogglePurchased() {
  const list = purchasableSelected.value
  if (!list.length) return
  const target = !allPurchasableSelectedPurchased.value
  bulkBusy.value = true
  try {
    await Promise.all(list.map(it => wlApi.update(it.id, { purchased: target })))
    exitBulkMode()
    await Promise.all([wlStore.fetch(), loadForecast()])
    message.success(target ? 'Отмечено как куплено' : 'Отмечено как не куплено')
  } catch (e) {
    message.error(e.message)
  } finally {
    bulkBusy.value = false
  }
}

async function bulkDelete() {
  bulkBusy.value = true
  try {
    await Promise.all(Array.from(selectedIds.value).map(id => wlApi.remove(id)))
    exitBulkMode()
    await Promise.all([wlStore.fetch(), loadForecast()])
    message.success('Записи удалены')
  } catch (e) {
    message.error(e.message)
  } finally {
    bulkBusy.value = false
  }
}

function checkboxStyle(checked) {
  const ringColor = checked ? primaryColor.value : palette.value.text3
  const bg = checked ? primaryColor.value : 'transparent'
  return `cursor:pointer;display:flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:50%;border:2px solid ${ringColor};background:${bg};color:#fff;transition:background .15s,border-color .15s;box-sizing:border-box`
}

// ── «Оплачено» / «Отменить» on recurring forecast items ───────────────────────
//
// Recurring items show two buttons:
//   • «Оплачено» — opens a small modal prefilled from the wishlist item
//     (estimated_cost, category, description=name) and POSTs a new expense
//     with `wishlist_id`. Multiple presses = multiple linked transactions
//     (e.g. utility surcharges); the item stays "paid" as long as ≥1 exists.
//   • «Отменить» — clears wishlist_id on every linked tx in the current
//     period via /api/wishlist/:id/unlink-period (one round-trip).

const showPayRegular  = ref(false)
const payRegularItem  = ref(null)
const payingBusy      = ref(false)
const cancelingId     = ref(null)
const payForm = ref({ amount: null, date: '', category: '', purpose: '', description: '' })

const expenseCategoryOptions = computed(() => catStore.options('expense'))

// «Список желаний / мес» = total wishlist contribution minus the recurring
// subset; defaults to 0 while loading. The split lets the user see what's
// driven by recurring obligations vs one-off planned purchases.
const wishlistOnlyContrib = computed(() => {
  const total = forecast.value.wishlist_contrib || 0
  const reg = forecast.value.regular_contrib || 0
  return Math.max(0, total - reg)
})

function todayStr() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function openPayRegular(item) {
  payRegularItem.value = item
  payForm.value = {
    // Prefill with the full bill amount (estimated_cost), not monthly_cost —
    // for quarterly/yearly items the user pays the full bill once per period.
    amount: Math.round(item.estimated_cost || item.monthly_cost) || null,
    date: todayStr(),
    category: item.category || '',
    // Точное копирование: name → «Назначение», notes → «Описание».
    // Раньше name попадал в description — фикс описанной пользователем ошибки.
    purpose: item.name || '',
    description: item.notes || '',
  }
  showPayRegular.value = true
}

async function confirmPayRegular() {
  if (!payRegularItem.value || !payForm.value.amount || !payForm.value.category) return
  payingBusy.value = true
  try {
    // Auto-create the expense category if the user typed a new one (matches
    // the existing wishlist-form behaviour — the backend would accept any
    // string but we keep the per-section catalog in sync).
    const cat = payForm.value.category
    if (cat && !catStore.bySection.expense?.find(c => c.name === cat)) {
      await catStore.add('expense', cat).catch(() => {})
    }
    await txApi.create({
      type: 'expense',
      amount: payForm.value.amount,
      date: payForm.value.date,
      category: cat,
      purpose: payForm.value.purpose || '',
      description: payForm.value.description || '',
      wishlist_id: payRegularItem.value.id,
    })
    catStore.recordUse('expense', cat)
    showPayRegular.value = false
    await loadForecast()
    message.success('Оплата зафиксирована')
  } catch (e) {
    message.error(e.message)
  } finally {
    payingBusy.value = false
  }
}

async function cancelRegularPaid(item) {
  cancelingId.value = item.id
  try {
    await wlApi.unlinkPeriod(item.id)
    await loadForecast()
    message.success('Привязки в текущем периоде сняты')
  } catch (e) {
    message.error(e.message)
  } finally {
    cancelingId.value = null
  }
}

// ── Reassign user ─────────────────────────────────────────────────────────────

const showReassign = ref(false)
const loadingUsers = ref(false)
const usersList = ref([])
const reassignTargetId = ref(null)

async function openReassign(itemId) {
  reassignTargetId.value = itemId
  showReassign.value = true
  if (!usersList.value.length) {
    loadingUsers.value = true
    try {
      const { data } = await usersApi.list()
      usersList.value = data
    } finally {
      loadingUsers.value = false
    }
  }
}

async function doReassign(user) {
  try {
    await wlStore.update(reassignTargetId.value, {
      created_by: { user_id: user.user_id, display_name: user.display_name, avatar_url: user.avatar_url || '' }
    })
    message.success(`Автор: ${user.display_name}`)
  } catch (e) {
    message.error(e.message)
  } finally {
    showReassign.value = false
  }
}

// ── Options ───────────────────────────────────────────────────────────────────

const catStore = useCategoriesStore()
const categoryOptions = computed(() => catStore.options('wishlist'))

function handleCategoryCreate(value) {
  return { label: value, value, id: null, is_default: false }
}

const trashIcon = () => h('svg', {
  width: 13, height: 13, viewBox: '0 0 24 24', fill: 'none',
  stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
  style: 'display:block'
}, [
  h('polyline', { points: '3 6 5 6 21 6' }),
  h('path', { d: 'M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6' }),
  h('path', { d: 'M10 11v6' }),
  h('path', { d: 'M14 11v6' }),
  h('path', { d: 'M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2' }),
])

function renderCategoryOption({ node, option }) {
  if (option.is_default || !option.id) return node
  return h('div', { style: 'display:flex;align-items:center;width:100%' }, [
    h('span', { style: 'flex:1;min-width:0' }, [node]),
    h('span', {
      style: `opacity:0.55;cursor:pointer;flex-shrink:0;padding:2px 4px;margin-right:14px;display:inline-flex;align-items:center;transition:opacity .15s;color:${palette.value.text2}`,
      title: 'Удалить категорию',
      onClick: async (e) => {
        e.stopPropagation()
        try {
          await catStore.remove(option.id, 'wishlist')
          if (form.value.category === option.value) form.value.category = ''
        } catch { message.error('Не удалось удалить категорию') }
      },
      onMouseenter: e => { e.currentTarget.style.opacity = '1' },
      onMouseleave: e => { e.currentTarget.style.opacity = '0.55' },
    }, [trashIcon()]),
  ])
}

// Frequency options shown in the "Регулярный расход" branch — `once` is
// no longer offered through this dropdown (it's implicit for the
// «Желаемая покупка» branch).
const recurringFrequencyOptions = [
  { label: 'Ежемесячно', value: 'monthly' },
  { label: 'Ежеквартально', value: 'quarterly' },
  { label: 'Ежегодно', value: 'yearly' },
]

const rules = {
  name: [{ required: true, message: 'Введите название', trigger: 'blur' }],
  estimated_cost: [{ required: true, type: 'number', message: 'Введите стоимость', trigger: 'blur' }],
  category: [{ required: true, message: 'Выберите категорию', trigger: 'change' }],
}

function freqLabel(f) {
  const map = { once: 'Однократно', monthly: 'Ежемесячно', quarterly: 'Ежеквартально', yearly: 'Ежегодно' }
  return map[f] || f
}

// Per-period suffix for displayed amounts in «Регулярные расходы» rows.
function freqUnit(f) {
  if (f === 'quarterly') return '₽/кв'
  if (f === 'yearly')    return '₽/год'
  return '₽/мес'
}

function formatDueDate(iso) {
  if (!iso) return ''
  // iso is YYYY-MM-DD; render as DD.MM.YYYY for Russian locale.
  const [y, m, d] = iso.split('-')
  return `${d}.${m}.${y}`
}

async function submit() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const cat = form.value.category
    if (cat && !catStore.bySection.wishlist.find(c => c.name === cat)) {
      await catStore.add('wishlist', cat).catch(() => {})
    }
    // Derive frequency from the type pill: wishlist branch is always 'once'.
    const frequency = form.value.kind === 'regular' ? form.value.frequency : 'once'
    await wlStore.create({
      name: form.value.name,
      estimated_cost: form.value.estimated_cost,
      category: cat,
      frequency,
      notes: form.value.notes,
    })
    catStore.recordUse('wishlist', cat)
    await loadForecast()
    message.success(form.value.kind === 'regular' ? 'Добавлено в регулярные расходы' : 'Добавлено в список желаний')
    form.value = {
      kind: form.value.kind, // keep the user's last choice for quick repeat entry
      name: '', estimated_cost: null, category: '',
      frequency: form.value.kind === 'regular' ? form.value.frequency : 'monthly',
      notes: '',
    }
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

async function loadForecast() {
  loadingForecast.value = true
  try {
    const { data } = await statistics.forecast()
    forecast.value = data
  } finally {
    loadingForecast.value = false
  }
}

const forecastPieOption = computed(() => {
  const p = palette.value
  return {
    tooltip: {
      trigger: 'item', formatter: '{b}: {c} ₽/мес ({d}%)',
      backgroundColor: p.tooltipBg, borderColor: p.tooltipBorder,
      textStyle: { color: p.tooltipText },
    },
    legend: { bottom: 0, type: 'scroll', textStyle: { color: p.chartLabel } },
    color: chartColors.value,
    series: [{
      type: 'pie',
      radius: ['38%', '65%'],
      center: ['50%', '44%'],
      data: (forecast.value.breakdown || []).map(d => ({ name: d.category, value: Math.round(d.amount) })),
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: p.chartShadow } },
      label: { color: p.chartLabel, formatter: '{b}\n{d}%' },
      labelLine: { lineStyle: { color: p.chartLabel } },
    }],
  }
})

onMounted(async () => {
  catStore.load('wishlist')
  // Expense categories are needed for the prefilled "Оплачено" form below.
  catStore.load('expense')
  await Promise.all([wlStore.fetch(), loadForecast()])
})
</script>

<style scoped>
.inline-edit-icon {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  opacity: 0.3;
  margin-left: 3px;
  vertical-align: middle;
  transition: opacity 0.15s;
  color: inherit;
}
.inline-edit-icon:hover {
  opacity: 0.75;
}

.user-assign-btn {
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  transition: opacity 0.15s;
}
.user-assign-btn:hover {
  opacity: 0.75;
}
.user-assign-btn.no-user {
  border: 1px dashed currentColor;
  opacity: 0.35;
  color: inherit;
}
.user-assign-btn.no-user:hover {
  opacity: 0.65;
}

/* Sub-card row layout shared by «Регулярные расходы» and «Список желаний»:
   optional avatar | title+meta (flex 1) | amount | actions. Same column
   widths so cards line up regardless of section. */
.regular-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.regular-row__avatar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}
.regular-row__main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.regular-row__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.regular-row__amount {
  flex: 0 0 auto;
  text-align: right;
  display: flex;
  align-items: center;
  gap: 4px;
}
.regular-row__actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
}
@media (max-width: 600px) {
  .regular-row {
    flex-wrap: wrap;
  }
  .regular-row__amount {
    order: 0;
  }
  .regular-row__actions {
    flex-basis: 100%;
    justify-content: flex-end;
  }
}
</style>
