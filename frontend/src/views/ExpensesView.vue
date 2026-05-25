<template>
  <div>
    <SplitPane storage-key="expenses-split" :default-left="45" :min-left="25" :max-left="60">
      <template #left>
        <!-- Лимит расходов за текущий календарный месяц (read-only). Скрыт
             на мобильном в add/edit, чтобы не отвлекать от формы. -->
        <n-card
          v-if="limitsTotalLimit > 0 && (!isMobile || !mobileAdding) && !mobileEditing"
          v-show="!isMobile || !mobileAdding"
          style="margin-bottom: 12px"
        >
          <template #header>
            <n-space align="center" justify="space-between" style="width: 100%">
              <n-text strong>Лимит расходов</n-text>
              <n-space align="center" :size="6">
                <n-tag
                  v-if="filteredPeriodDiffersFromCurrent"
                  size="tiny"
                  type="success"
                  :bordered="false"
                  round
                >
                  тек. месяц
                </n-tag>
                <n-text depth="3" style="font-size: 13px">{{ currentMonthLabel }}</n-text>
              </n-space>
            </n-space>
          </template>
          <div class="global-limit-body">
            <n-space align="center" justify="space-between" style="width: 100%">
              <n-text
                :style="`color:${limitProgressColor(limitsTotalPercent)};font-weight:600;font-size:18px${valuesHidden ? ';filter:blur(7px);user-select:none' : ''}`"
              >
                {{ formatMoney(limitsTotalSpent) }} / {{ formatMoney(limitsTotalLimit) }} ₽
              </n-text>
              <n-text depth="2" style="font-size: 13px">
                {{ Math.round(limitsTotalPercent) }}%
              </n-text>
            </n-space>
            <n-progress
              type="line"
              :percentage="Math.min(100, limitsTotalPercent)"
              :show-indicator="false"
              :color="limitProgressColor(limitsTotalPercent)"
              :height="8"
              :border-radius="4"
              style="margin-top: 8px"
            />
          </div>
        </n-card>
        <!-- Mobile add/edit slide-swap (см. theme.css `.mobile-slide-*`).
             На desktop'е CSS-классы no-op через `@media (max-width:767px)`. -->
        <Transition name="mobile-slide">
          <div v-show="!isMobile || mobileAdding || mobileEditing">
            <n-card>
              <template #header>
                <div v-if="isMobile && (mobileAdding || mobileEditing)" class="card-back-header">
                  <n-button text size="small" @click="exitMobileAddEdit">
                    <template #icon><n-icon :component="ArrowBackOutline" /></template>
                  </n-button>
                  <n-text strong style="font-size: 16px">
                    {{ mobileEditing ? 'Изменить расход' : 'Новый расход' }}
                  </n-text>
                </div>
                <span v-else>Добавить расход</span>
              </template>
              <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
                <n-grid :cols="2" :x-gap="12" :item-responsive="true">
                  <n-grid-item span="2 s:1">
                    <n-form-item label="Сумма (₽)" path="amount">
                      <n-input-number
                        v-model:value="form.amount"
                        :min="0.01"
                        :precision="2"
                        style="width: 100%"
                        placeholder="0.00"
                      />
                    </n-form-item>
                  </n-grid-item>
                  <n-grid-item span="2 s:1">
                    <n-form-item label="Дата" path="date">
                      <n-date-picker v-model:value="form.date" type="date" style="width: 100%" />
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
                        :render-label="renderCategoryLabel"
                        to="body"
                        placeholder="Выберите или введите категорию"
                      />
                    </n-form-item>
                  </n-grid-item>
                  <n-grid-item span="2 s:1">
                    <n-form-item label="Назначение">
                      <!-- NAutoComplete тянет options из localStorage-кэша
                           (см. utils/inputHistory.js). `get-show: () => true`
                           показывает dropdown даже при пустом input'е. -->
                      <n-auto-complete
                        v-model:value="form.purpose"
                        :options="purposeHistoryOptions"
                        :get-show="() => true"
                        placeholder="Куда потрачено"
                      />
                    </n-form-item>
                  </n-grid-item>
                  <n-grid-item span="2">
                    <n-form-item label="Описание">
                      <n-input
                        v-model:value="form.description"
                        placeholder="Дополнительно (необязательно)"
                      />
                    </n-form-item>
                  </n-grid-item>
                  <n-grid-item span="2">
                    <n-form-item label="Счёт">
                      <n-radio-group v-model:value="form.deposit" size="small">
                        <n-radio-button v-for="d in DEPOSITS" :key="d.value" :value="d.value">
                          <span class="dep-radio-content">
                            <n-icon :component="d.icon" />
                            {{ d.label }}
                          </span>
                        </n-radio-button>
                      </n-radio-group>
                    </n-form-item>
                  </n-grid-item>
                </n-grid>
                <div v-if="isMobile && mobileEditing" class="form-actions-row">
                  <n-popconfirm @positive-click="deleteEditingRow">
                    <template #trigger>
                      <n-button type="error" ghost :disabled="saving" style="flex: 1">
                        <template #icon><n-icon :component="TrashOutline" /></template>
                        Удалить
                      </n-button>
                    </template>
                    Удалить запись?
                  </n-popconfirm>
                  <n-button type="primary" :loading="saving" style="flex: 1" @click="submit">
                    Сохранить
                  </n-button>
                </div>
                <n-button v-else type="primary" :loading="saving" block @click="submit">
                  Добавить расход
                </n-button>
              </n-form>
            </n-card>
          </div>
        </Transition>
        <!-- /mobile-add wrap -->
      </template>

      <template #right>
        <Transition name="mobile-slide">
          <div v-show="!isMobile || (!mobileAdding && !mobileEditing)">
            <n-card title="История расходов">
              <!-- Pinned: my open detail-requests -->
              <div v-if="myOpenParents.length" class="dr-pinned" :style="pinnedStyle">
                <div class="dr-pinned-title">Открытые запросы на детализацию</div>
                <div
                  v-for="p in myOpenParents"
                  :key="p.id"
                  class="dr-pinned-row"
                  @click="openDetailRequest(p.detail_request_id)"
                >
                  <div>
                    <div style="font-weight: 600">
                      {{ p.category }} · {{ p.amount.toLocaleString('ru-RU') }} ₽
                    </div>
                    <div style="font-size: 11px; opacity: 0.65">
                      {{ p.purpose || p.description || 'без описания' }} ·
                      {{ new Date(p.date).toLocaleDateString('ru-RU') }}
                    </div>
                  </div>
                  <n-tag size="small" type="warning" round>заполнить</n-tag>
                </div>
              </div>

              <n-space style="margin-bottom: 12px" wrap align="center" justify="space-between">
                <n-space v-if="!isMobile" wrap align="center">
                  <n-date-picker
                    v-model:value="filterRange"
                    type="daterange"
                    clearable
                    size="small"
                    placeholder="Фильтр по дате"
                    @update:value="applyFilters"
                  />
                  <n-select
                    v-model:value="filterCategories"
                    :options="categoryOptions"
                    multiple
                    clearable
                    size="small"
                    style="width: 230px"
                    placeholder="Все категории"
                    :max-tag-count="1"
                    :render-label="renderCategoryLabel"
                    :render-tag="renderCategoryTag"
                    to="body"
                    @update:value="applyFilters"
                  />
                  <n-select
                    v-model:value="filterDeposit"
                    :options="depositFilterOptions"
                    size="small"
                    style="width: 170px"
                    to="body"
                    @update:value="applyFilters"
                  />
                  <n-checkbox
                    v-model:checked="showDetailed"
                    size="small"
                    @update:checked="applyFilters"
                  >
                    <span style="font-size: 12px">Показать закрытые запросы</span>
                  </n-checkbox>
                </n-space>
                <n-popover v-else trigger="click" placement="bottom-start" :show-arrow="false">
                  <template #trigger>
                    <n-button size="small">
                      <template #icon><n-icon :component="FunnelOutline" /></template>
                      Фильтр{{ activeFilterCount ? ` · ${activeFilterCount}` : '' }}
                    </n-button>
                  </template>
                  <div class="filter-popover">
                    <div class="filter-popover-label">Период</div>
                    <n-date-picker
                      v-model:value="filterRange"
                      type="daterange"
                      clearable
                      size="small"
                      style="width: 100%"
                      @update:value="applyFilters"
                    />
                    <div class="filter-popover-label">Категории</div>
                    <n-select
                      v-model:value="filterCategories"
                      :options="categoryOptions"
                      multiple
                      clearable
                      size="small"
                      style="width: 100%"
                      placeholder="Все категории"
                      :max-tag-count="2"
                      :render-label="renderCategoryLabel"
                      :render-tag="renderCategoryTag"
                      to="body"
                      @update:value="applyFilters"
                    />
                    <div class="filter-popover-label">Счёт</div>
                    <n-select
                      v-model:value="filterDeposit"
                      :options="depositFilterOptions"
                      size="small"
                      style="width: 100%"
                      to="body"
                      @update:value="applyFilters"
                    />
                    <n-checkbox
                      v-model:checked="showDetailed"
                      style="margin-top: 8px"
                      @update:checked="applyFilters"
                    >
                      <span style="font-size: 13px">Показать закрытые запросы</span>
                    </n-checkbox>
                    <n-button
                      v-if="activeFilterCount > 0"
                      size="small"
                      quaternary
                      block
                      style="margin-top: 8px"
                      @click="resetFilters"
                    >
                      Сбросить
                    </n-button>
                  </div>
                </n-popover>
                <n-space align="center" :size="8">
                  <template v-if="!bulkMode">
                    <n-button v-if="!isMobile" size="small" @click="enterBulkMode">
                      Пакетное редактирование
                    </n-button>
                  </template>
                  <template v-else>
                    <n-text v-if="selectedIds.size" depth="2" style="font-size: 12px">
                      Выбрано: {{ selectedIds.size }}
                    </n-text>
                    <!-- Inline-кнопки только на десктопе. На мобилке те же
                       действия дублируются в `<BulkFabRow>` снизу-справа. -->
                    <template v-if="!isMobile && selectedIds.size">
                      <ConfirmActionButton
                        :label="allSelectedHidden ? 'Показать' : 'Скрыть'"
                        :type="allSelectedHidden ? 'default' : 'warning'"
                        :loading="bulkBusy"
                        @confirm="bulkToggleHidden"
                      />
                      <ConfirmActionButton
                        label="Удалить"
                        type="error"
                        :loading="bulkBusy"
                        @confirm="bulkDelete"
                      />
                    </template>
                    <n-button v-if="!isMobile" size="small" quaternary @click="exitBulkMode">
                      Отмена
                    </n-button>
                  </template>
                </n-space>
              </n-space>
              <!-- Desktop: NDataTable c адаптивным responsive-режимом
                 (ResizeObserver внутри useAdaptiveTable). Mobile: card-list. -->
              <div v-if="!isMobile" ref="tablePaneRef">
                <n-data-table
                  size="small"
                  :columns="columns"
                  :data="store.items"
                  :loading="store.loading"
                  :pagination="pagination"
                  :row-props="getRowProps"
                  remote
                  @update:page="store.setPage"
                />
              </div>
              <div v-else class="tx-cards">
                <n-spin v-if="store.loading && !store.items.length" style="margin: 24px 0" />
                <n-empty
                  v-else-if="!store.items.length"
                  description="Записей нет"
                  style="padding: 32px 0"
                />
                <!-- `<TransitionGroup>` для collapse-leave анимации удалённой
                   карточки (см. theme.css `.tx-list-*`). enter не анимируем. -->
                <TransitionGroup v-else name="tx-list" tag="div" class="tx-cards-list">
                  <SwipeableCard
                    v-for="row in store.items"
                    :key="row.id"
                    :long-press-ms="bulkMode ? 0 : 1000"
                    :radius="3"
                    @tap="onCardTap(row)"
                    @longpress="onCardLongPress(row.id)"
                  >
                    <template #actions>
                      <button
                        class="swipe-action swipe-action-info"
                        :title="row.hidden ? 'Показать' : 'Скрыть'"
                        @click="store.toggle(row.id, !row.hidden)"
                      >
                        <n-icon :component="row.hidden ? EyeOffOutline : EyeOutline" :size="20" />
                        <span class="swipe-action-label">
                          {{ row.hidden ? 'Показать' : 'Скрыть' }}
                        </span>
                      </button>
                      <button
                        class="swipe-action swipe-action-warning"
                        title="Добавить как шаблон"
                        @click="fillFromTemplate(row)"
                      >
                        <n-icon :component="CopyOutline" :size="20" />
                        <span class="swipe-action-label">Шаблон</span>
                      </button>
                      <button
                        class="swipe-action swipe-action-danger"
                        title="Удалить"
                        @click="confirmDeleteRow(row)"
                      >
                        <n-icon :component="TrashOutline" :size="20" />
                        <span class="swipe-action-label">Удалить</span>
                      </button>
                    </template>
                    <!-- n-card embedded — единый visual style с Forecast. -->
                    <n-card
                      size="small"
                      :bordered="true"
                      embedded
                      :style="
                        bulkMode && selectedIds.has(row.id) ? `background:${primaryColor}1f` : ''
                      "
                    >
                      <div class="tx-row" :class="{ hidden: row.hidden }">
                        <div class="tx-card-left">
                          <!-- Avatar ↔ bulk-circle fade-swap; см. theme.css
                           `.bulk-icon-*`. -->
                          <Transition name="bulk-icon" mode="out-in">
                            <div
                              v-if="bulkMode"
                              key="bulk"
                              class="bulk-circle"
                              :class="{ checked: selectedIds.has(row.id) }"
                            >
                              <svg
                                v-if="selectedIds.has(row.id)"
                                width="14"
                                height="14"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                stroke-width="3"
                                stroke-linecap="round"
                                stroke-linejoin="round"
                              >
                                <polyline points="20 6 9 17 4 12" />
                              </svg>
                            </div>
                            <UserAvatar
                              v-else
                              key="avatar"
                              :display-name="row.created_by?.display_name || ''"
                              :avatar-url="row.created_by?.avatar_url || ''"
                              :size="32"
                            />
                          </Transition>
                        </div>
                        <div class="tx-card-body">
                          <div class="tx-card-row1">
                            <span class="tx-card-date">
                              {{ new Date(row.date).toLocaleDateString('ru-RU') }}
                            </span>
                            <DepositChip
                              :model-value="row.deposit"
                              editable
                              :icon-size="14"
                              @change="(v) => changeDeposit(row, v)"
                            />
                            <CategoryLabel
                              class="tx-card-category"
                              :name="row.category"
                              :category="catStore.findByName('expense', row.category)"
                              :size="14"
                            />
                          </div>
                          <div
                            v-if="row.purpose || row.description || row.wishlist_id"
                            class="tx-card-desc"
                          >
                            <n-tag
                              v-if="wishlistById.get(row.wishlist_id)"
                              type="info"
                              size="small"
                              round
                              :bordered="false"
                              style="cursor: pointer; margin-right: 4px"
                              :title="`Открыть «${wishlistById.get(row.wishlist_id).name}» в Прогнозе`"
                              @click.stop="
                                router.push({
                                  path: '/forecast',
                                  query: { focus: row.wishlist_id },
                                })
                              "
                            >
                              <template #icon>
                                <n-icon :component="LinkOutline" />
                              </template>
                              {{
                                wishlistById.get(row.wishlist_id).frequency &&
                                wishlistById.get(row.wishlist_id).frequency !== 'once'
                                  ? 'Регулярный'
                                  : 'Желание'
                              }}: {{ wishlistById.get(row.wishlist_id).name }}
                            </n-tag>
                            {{ [row.purpose, row.description].filter(Boolean).join(' · ') }}
                          </div>
                        </div>
                        <div
                          class="tx-card-amount"
                          :style="{
                            color: expenseColor,
                            filter: valuesHidden ? 'blur(7px)' : 'none',
                          }"
                        >
                          −{{ row.amount.toLocaleString('ru-RU') }} ₽
                        </div>
                      </div>
                    </n-card>
                  </SwipeableCard>
                </TransitionGroup>
                <n-pagination
                  v-if="store.total > store.limit"
                  :page="store.page"
                  :page-count="Math.ceil(store.total / store.limit)"
                  :page-slot="5"
                  size="small"
                  style="justify-content: center; margin-top: 12px"
                  @update:page="store.setPage"
                />
              </div>
            </n-card>
          </div>
        </Transition>
        <!-- /mobile-history wrap -->
      </template>
    </SplitPane>

    <!-- В bulk-mode на mobile FAB-«+» подменяется на ряд action-FAB'ов
         (mirror Android Scaffold floatingActionButton — IncomeScreen.kt).
         `<Transition mode="out-in">` делает sequential fade+scale свап
         (см. theme.css `.fab-swap-*`). -->
    <Transition name="fab-swap" mode="out-in">
      <FabButton
        v-if="isMobile && !mobileAdding && !mobileEditing && !bulkMode"
        key="add"
        title="Добавить расход"
        @click="enterMobileAdd"
      />
      <BulkFabRow
        v-else-if="isMobile && bulkMode && !mobileAdding && !mobileEditing"
        key="bulk"
        :actions="mobileBulkActions"
      />
    </Transition>

    <!-- Reassign user modal -->
    <n-modal
      v-model:show="showReassign"
      preset="card"
      title="Изменить автора"
      style="max-width: 320px"
    >
      <n-spin :show="loadingUsers">
        <n-list hoverable clickable>
          <n-list-item v-for="u in usersList" :key="u.user_id" @click="doReassign(u)">
            <n-space align="center">
              <UserAvatar
                :displayName="u.display_name"
                :avatarUrl="u.avatar_url || ''"
                :size="28"
              />
              <n-text>{{ u.display_name }}</n-text>
            </n-space>
          </n-list-item>
        </n-list>
      </n-spin>
    </n-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, h } from 'vue'
function toLocalDateString(ts) {
  const d = new Date(ts)
  const offset = d.getTimezoneOffset()
  return new Date(d.getTime() - offset * 60000).toISOString()
}
import { useMessage } from 'naive-ui'
import {
  NCard,
  NGrid,
  NGridItem,
  NForm,
  NFormItem,
  NAutoComplete,
  NInput,
  NInputNumber,
  NSelect,
  NDatePicker,
  NButton,
  NDataTable,
  NSpace,
  NPopconfirm,
  NText,
  NTooltip,
  NModal,
  NSpin,
  NEmpty,
  NPagination,
  NPopover,
  NList,
  NListItem,
  NTag,
  NCheckbox,
  NIcon,
  NProgress,
  NRadioGroup,
  NRadioButton,
} from 'naive-ui'
import {
  ArrowBackOutline,
  CheckmarkOutline,
  CloseOutline,
  CopyOutline,
  EyeOffOutline,
  EyeOutline,
  FunnelOutline,
  LinkOutline,
  ListOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import { useTransactionsStore } from '@/stores/transactions'
import { useWishlistStore } from '@/stores/wishlist'
import { useThemeStore } from '@/stores/theme'
import { useCategoriesStore } from '@/stores/categories'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import SplitPane from '@/components/SplitPane.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'
import FabButton from '@/components/FabButton.vue'
import BulkFabRow from '@/components/BulkFabRow.vue'
import SwipeableCard from '@/components/SwipeableCard.vue'
import CategoryLabel from '@/components/CategoryLabel.vue'
import DepositChip from '@/components/DepositChip.vue'
import { DEPOSITS, DEPOSIT_DEFAULT, normalizeDeposit } from '@/utils/deposit'
import { historyOptions, pushHistory } from '@/utils/inputHistory'
import {
  users as usersApi,
  transactions as txApi,
  detailRequests as drApi,
  categories as catApi,
} from '@/api'
import { useDetailRequestsStore } from '@/stores/detailRequests'
import { useNotificationsStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import {
  useAdaptiveTable,
  plainTextCell,
  renderActionsPopover,
  renderActionButton,
} from '@/utils/adaptiveTable'

const store = useTransactionsStore('expenses')
const drStore = useDetailRequestsStore()
const notifStore = useNotificationsStore()
const wlStore = useWishlistStore()
const auth = useAuthStore()
const { palette, valuesHidden, primaryColor } = storeToRefs(useThemeStore())
const expenseColor = computed(() => palette.value.expense)
const message = useMessage()
const formRef = ref(null)
const saving = ref(false)
const filterRange = ref(null)
const filterCategories = ref([])
const filterDeposit = ref('')
const route = useRoute()
const router = useRouter()
const showDetailed = ref(false)

// id→wishlist map used by the «привязано к…» badge on each tx row. Items
// without a `wishlist_id` skip the lookup; rows linked to a wishlist that
// hasn't loaded yet just don't show the tag (it reappears when the store
// fetch resolves). Linking-info is read-only on this view — to unlink, the
// user follows the tag into Прогноз.
const wishlistById = computed(() => {
  const map = new Map()
  for (const w of wlStore.items) map.set(w.id, w)
  return map
})

function linkedWishlistTag(row) {
  if (!row.wishlist_id) return null
  const wl = wishlistById.value.get(row.wishlist_id)
  if (!wl) return null
  const isRegular = wl.frequency && wl.frequency !== 'once'
  const label = `${isRegular ? 'Регулярный' : 'Желание'}: ${wl.name}`
  return h(
    NTag,
    {
      type: 'info',
      size: 'small',
      round: true,
      bordered: false,
      style: 'cursor:pointer;max-width:100%',
      title: `Открыть «${wl.name}» в Прогнозе`,
      onClick: (e) => {
        e.stopPropagation()
        router.push({ path: '/forecast', query: { focus: wl.id } })
      },
    },
    {
      icon: () => h(NIcon, null, { default: () => h(LinkOutline) }),
      default: () => label,
    },
  )
}

// Global monthly limit progress (current calendar month, family-wide).
// Read-only here — admin edits live in /settings/categories. Reload on
// mount and after every CRUD via watchers on store.items below.
const limitsTotalLimit = ref(0)
const limitsTotalSpent = ref(0)
const limitsTotalPercent = computed(() =>
  limitsTotalLimit.value > 0 ? (limitsTotalSpent.value / limitsTotalLimit.value) * 100 : 0,
)
const currentMonthLabel = computed(() => {
  const d = new Date()
  return d.toLocaleString('ru-RU', { month: 'long', year: 'numeric' })
})
// Whether the user's active period filter covers a range that doesn't
// fully overlap the current calendar month. Drives the "тек. месяц"
// badge — the global limit always reflects the current month even when
// the history table is showing a different period.
const filteredPeriodDiffersFromCurrent = computed(() => {
  const range = filterRange.value
  if (!range || !Array.isArray(range)) return false
  const now = new Date()
  const curStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime()
  const curEnd = new Date(now.getFullYear(), now.getMonth() + 1, 0, 23, 59, 59, 999).getTime()
  return range[0] < curStart || range[1] > curEnd
})

// Limit-progress tint: theme-aware palette.income for safe spending so the
// sum visually matches the green «Баланс на начало месяца» in IncomeView;
// amber for the «approaching» band; palette.expense once the limit is
// blown — same red used for the per-row expense amounts on this page.
function limitProgressColor(percent) {
  if (percent >= 100) return palette.value.expense
  if (percent >= 80) return '#F59E0B'
  return palette.value.income
}
function formatMoney(value) {
  if (value == null) return ''
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value)
}

async function loadLimitsProgress() {
  try {
    const { data } = await catApi.limitsProgress()
    limitsTotalLimit.value = data?.total_limit || 0
    limitsTotalSpent.value = data?.total_spent || 0
  } catch {
    // Soft-fail: the badge just disappears if the endpoint is down.
    limitsTotalLimit.value = 0
    limitsTotalSpent.value = 0
  }
}

const form = ref({
  amount: null,
  date: Date.now(),
  category: '',
  purpose: '',
  description: '',
  deposit: DEPOSIT_DEFAULT,
})

// LocalStorage-кэш недавно введённых «Назначений» для NAutoComplete.
// Ref + явный refresh после submit'a — чтобы новое значение всплыло без
// перезагрузки страницы.
const purposeHistoryOptions = ref(historyOptions('expense-purpose'))
function refreshPurposeHistory() {
  purposeHistoryOptions.value = historyOptions('expense-purpose')
}

// Mobile add/edit nav — см. IncomeView.
const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
const isMobile = computed(() => windowWidth.value < 768)
const mobileAdding = ref(false)
const mobileEditing = ref(null)
function onWinResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

// Responsive desktop-таблица — ниже 740px ширины pane включается compact-режим
// (actions-popover, скрытые карандаши, ellipsis на текстовых колонках).
const { paneRef: tablePaneRef, compact: tableCompact } = useAdaptiveTable()

function enterMobileAdd() {
  mobileEditing.value = null
  form.value = {
    amount: null,
    date: Date.now(),
    category: '',
    purpose: '',
    description: '',
    deposit: DEPOSIT_DEFAULT,
  }
  mobileAdding.value = true
}

function enterMobileEdit(row) {
  mobileAdding.value = false
  mobileEditing.value = row
  form.value = {
    amount: row.amount,
    date: new Date(row.date).getTime(),
    category: row.category,
    purpose: row.purpose || '',
    description: row.description || '',
    deposit: normalizeDeposit(row.deposit),
  }
}

function exitMobileAddEdit() {
  mobileAdding.value = false
  mobileEditing.value = null
}

// Card events: SwipeableCard эмитит tap / longpress, gesture-логика —
// в самом компоненте.
function onCardLongPress(id) {
  if (!bulkMode.value) bulkMode.value = true
  toggleSelect(id)
}

function onCardTap(row) {
  if (bulkMode.value) {
    toggleSelect(row.id)
    return
  }
  enterMobileEdit(row)
}

function confirmDeleteRow(row) {
  if (!window.confirm('Удалить запись?')) return
  store.remove(row.id)
}

async function deleteEditingRow() {
  if (!mobileEditing.value) return
  saving.value = true
  try {
    await store.remove(mobileEditing.value.id)
    message.success('Запись удалена')
    exitMobileAddEdit()
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

const catStore = useCategoriesStore()
const categoryOptions = computed(() => catStore.options('expense'))

function handleCategoryCreate(value) {
  return { label: value, value, id: null, is_default: false }
}

const trashIcon = () =>
  h(
    'svg',
    {
      width: 13,
      height: 13,
      viewBox: '0 0 24 24',
      fill: 'none',
      stroke: 'currentColor',
      'stroke-width': '2',
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round',
      style: 'display:block',
    },
    [
      h('polyline', { points: '3 6 5 6 21 6' }),
      h('path', { d: 'M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6' }),
      h('path', { d: 'M10 11v6' }),
      h('path', { d: 'M14 11v6' }),
      h('path', { d: 'M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2' }),
    ],
  )

function renderCategoryOption({ node, option }) {
  // Default categories and unsaved pending tags render normally
  if (option.is_default || !option.id) return node
  return h('div', { style: 'display:flex;align-items:center;width:100%' }, [
    h('span', { style: 'flex:1;min-width:0' }, [node]),
    h(
      'span',
      {
        style: `opacity:0.55;cursor:pointer;flex-shrink:0;padding:2px 4px;margin-right:14px;display:inline-flex;align-items:center;transition:opacity .15s;color:${palette.value.text2}`,
        title: 'Удалить категорию',
        onClick: async (e) => {
          e.stopPropagation()
          try {
            await catStore.remove(option.id, 'expense')
            if (form.value.category === option.value) form.value.category = ''
            if (filterCategories.value.includes(option.value)) {
              filterCategories.value = filterCategories.value.filter((v) => v !== option.value)
              applyFilters()
            }
          } catch {
            message.error('Не удалось удалить категорию')
          }
        },
        onMouseenter: (e) => {
          e.currentTarget.style.opacity = '1'
        },
        onMouseleave: (e) => {
          e.currentTarget.style.opacity = '0.55'
        },
      },
      [trashIcon()],
    ),
  ])
}

// `render-label` swaps the dropdown row text (and, for non-tag selects, the
// selected-value display in the trigger) for the inline icon + name. Single-
// tag selects must NOT set `render-tag` — Naive invokes it with a placeholder
// ghost option in the empty state, leaving a stray empty chip with a close
// button. The icon still appears in the dropdown rows via render-label and,
// once selected, in the single-tag input via Naive's default chip rendering
// that consults render-label for content.
function renderCategoryLabel(option) {
  return h(CategoryLabel, {
    name: option.value,
    category: catStore.findByName('expense', option.value),
    size: 14,
  })
}

// Multi-select filter pills: wrap CategoryLabel inside NTag so the chip
// inherits Naive's small-size dimensions and matches the date-picker
// height next to it. Guarded against empty option.
function renderCategoryTag({ option, handleClose }) {
  if (!option || option.value == null || option.value === '') return null
  // No margin / style overrides — Naive applies its own internal spacing
  // between tag pills inside n-select's tag area. Adding `margin: 2px 0`
  // (the previous attempt) pushed n-select's control height past the
  // sibling n-date-picker's, breaking the row baseline.
  return h(
    NTag,
    {
      size: 'small',
      round: false,
      closable: !!handleClose,
      onClose: (e) => {
        e?.stopPropagation?.()
        handleClose?.()
      },
    },
    {
      default: () =>
        h(CategoryLabel, {
          name: option.value,
          category: catStore.findByName('expense', option.value),
          size: 12,
        }),
    },
  )
}

const rules = {
  amount: [{ required: true, type: 'number', message: 'Введите сумму', trigger: 'blur' }],
  date: [{ required: true, type: 'number', message: 'Выберите дату', trigger: 'change' }],
  category: [{ required: true, message: 'Выберите категорию', trigger: 'change' }],
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const cat = form.value.category
    if (cat && !catStore.bySection.expense.find((c) => c.name === cat)) {
      await catStore.add('expense', cat).catch(() => {})
    }
    const payload = {
      amount: form.value.amount,
      date: toLocalDateString(form.value.date).split('T')[0],
      category: cat,
      purpose: form.value.purpose,
      description: form.value.description,
      deposit: normalizeDeposit(form.value.deposit),
    }
    if (mobileEditing.value) {
      await store.update(mobileEditing.value.id, payload)
      message.success('Сохранено')
    } else {
      await store.create({ type: 'expense', ...payload })
      message.success('Расход добавлен')
    }
    catStore.recordUse('expense', cat)
    // Пушим «Назначение» в localStorage-историю для autocomplete'a.
    pushHistory('expense-purpose', form.value.purpose)
    refreshPurposeHistory()
    form.value = {
      amount: null,
      date: Date.now(),
      category: '',
      purpose: '',
      description: '',
      deposit: DEPOSIT_DEFAULT,
    }
    if (isMobile.value) {
      mobileAdding.value = false
      mobileEditing.value = null
    }
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

function getRowProps(row) {
  const styles = []
  if (row.hidden) styles.push('opacity:0.4')
  if (bulkMode.value && selectedIds.value.has(row.id)) {
    styles.push(`background:${primaryColor.value}1f`)
  }
  if (hasMyOpenRequest(row)) {
    // Yellow highlight for transactions where the current user has an open
    // detail-request waiting to be filled in.
    styles.push('background:rgba(240,160,32,0.16)')
  }
  return { style: styles.join(';') }
}

const myOpenRequestParentIds = computed(
  () =>
    new Set(
      drStore.items
        .filter((r) => r.status === 'open' && r.assignee?.user_id === auth.user?.user_id)
        .map((r) => r.parent_transaction_id),
    ),
)

function hasMyOpenRequest(row) {
  return (
    myOpenRequestParentIds.value.has(row.id) ||
    (row.detail_request_status === 'open' &&
      drStore.items.find((r) => r.id === row.detail_request_id)?.assignee?.user_id ===
        auth.user?.user_id)
  )
}

// Parent transactions of my open requests — fetched eagerly so the pinned
// banner can render even when the parent isn't on the current page.
const myOpenParents = ref([])
async function loadMyOpenParents() {
  const mine = drStore.items.filter(
    (r) => r.status === 'open' && r.assignee?.user_id === auth.user?.user_id,
  )
  const out = []
  for (const r of mine) {
    try {
      const { data } = await drApi.get(r.id)
      if (data?.parent) out.push(data.parent)
    } catch {
      // best-effort fetch; skip silently if the detail-request can't be loaded
    }
  }
  myOpenParents.value = out
}

const pinnedStyle = computed(() => ({
  background: 'rgba(240,160,32,0.10)',
  border: '1px solid rgba(240,160,32,0.35)',
  borderRadius: '6px',
  padding: '8px 10px',
  marginBottom: '12px',
}))

function openDetailRequest(id) {
  drStore.openRequest(id)
}

function startCreateDetailRequest(row) {
  drStore.startCreate(row)
}

function fillFromTemplate(row) {
  form.value = {
    amount: row.amount,
    date: Date.now(),
    category: row.category,
    purpose: row.purpose || '',
    description: row.description || '',
    deposit: normalizeDeposit(row.deposit),
  }
  if (isMobile.value) {
    // На мобилке форма скрыта пока не нажат FAB-«+» / тап по записи —
    // pre-fill без переключения в mobileAdding оставлял пользователя
    // на экране списка без видимой формы и со снэк'ом «форма заполнена».
    // Открываем форму добавления сразу с заполненными полями.
    mobileEditing.value = null
    mobileAdding.value = true
  } else {
    message.info('Форма заполнена по шаблону')
  }
}

function fmtLocalDate(ts) {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function applyFilters() {
  const f = {
    type: 'expense',
    categories: filterCategories.value,
    includeDetailed: showDetailed.value,
  }
  if (filterRange.value) {
    f.from = fmtLocalDate(filterRange.value[0])
    f.to = fmtLocalDate(filterRange.value[1])
  }
  if (filterDeposit.value) f.deposit = filterDeposit.value
  store.setFilters(f)
}

const activeFilterCount = computed(() => {
  let n = 0
  if (filterRange.value) n += 1
  if (filterCategories.value?.length) n += 1
  if (showDetailed.value) n += 1
  if (filterDeposit.value) n += 1
  return n
})

function resetFilters() {
  filterRange.value = null
  filterCategories.value = []
  filterDeposit.value = ''
  showDetailed.value = false
  applyFilters()
}

const depositFilterOptions = [
  { label: 'Все', value: '' },
  ...DEPOSITS.map((d) => ({ label: d.label, value: d.value })),
]

async function changeDeposit(row, deposit) {
  try {
    await store.update(row.id, { deposit: normalizeDeposit(deposit) })
    message.success(`Счёт: ${deposit === 'cash' ? 'Наличные' : 'Банковская карта'}`)
  } catch (e) {
    message.error(e.message)
  }
}

// ── Inline cell editing ───────────────────────────────────────────────────────

const editingCell = ref(null)
const editCellValue = ref(null)

function startCellEdit(id, field, value) {
  editingCell.value = { id, field }
  editCellValue.value = value
}

function cancelCellEdit() {
  editingCell.value = null
  editCellValue.value = null
}

async function confirmCellEdit(row, field) {
  const payload = { [field]: editCellValue.value }
  try {
    await store.update(row.id, payload)
    if (field === 'category') catStore.recordUse('expense', editCellValue.value)
    message.success('Сохранено')
  } catch (e) {
    message.error(e.message)
  } finally {
    cancelCellEdit()
  }
}

function isEditing(id, field) {
  return editingCell.value?.id === id && editingCell.value?.field === field
}

// ── Reassign ──────────────────────────────────────────────────────────────────

const showReassign = ref(false)
const loadingUsers = ref(false)
const usersList = ref([])
const reassignRow = ref(null)

async function openReassign(row) {
  reassignRow.value = row
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
    await store.update(reassignRow.value.id, {
      created_by: {
        user_id: user.user_id,
        display_name: user.display_name,
        avatar_url: user.avatar_url || '',
      },
    })
    message.success(`Автор: ${user.display_name}`)
  } catch (e) {
    message.error(e.message)
  } finally {
    showReassign.value = false
  }
}

// ── Render helpers ────────────────────────────────────────────────────────────

const pencilSvg = () =>
  h(
    'svg',
    {
      width: 12,
      height: 12,
      viewBox: '0 0 24 24',
      fill: 'none',
      stroke: 'currentColor',
      'stroke-width': '2',
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round',
      style: 'display:block',
    },
    [
      h('path', { d: 'M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7' }),
      h('path', { d: 'M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z' }),
    ],
  )

const pencilBtn = (onClick) =>
  h(
    'span',
    {
      style:
        'opacity:0.28;cursor:pointer;display:inline-flex;align-items:center;margin-left:3px;vertical-align:middle;transition:opacity .15s;color:inherit',
      onMouseenter: (e) => {
        e.currentTarget.style.opacity = '0.72'
      },
      onMouseleave: (e) => {
        e.currentTarget.style.opacity = '0.28'
      },
      onClick,
    },
    [pencilSvg()],
  )

const okBtn = (onClick) =>
  h(
    NButton,
    {
      size: 'tiny',
      type: 'primary',
      style: 'padding:0 4px;min-width:22px;height:22px',
      onClick,
    },
    { icon: () => h(NIcon, null, { default: () => h(CheckmarkOutline) }) },
  )

const cancelBtn = (onClick) =>
  h(
    NButton,
    {
      size: 'tiny',
      style: 'padding:0 4px;min-width:22px;height:22px',
      onClick,
    },
    { icon: () => h(NIcon, null, { default: () => h(CloseOutline) }) },
  )

const userPlaceholder = (onClick) =>
  h(
    'div',
    {
      style:
        'cursor:pointer;display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;border:1px dashed currentColor;opacity:0.35;transition:opacity .15s',
      onMouseenter: (e) => {
        e.currentTarget.style.opacity = '0.7'
      },
      onMouseleave: (e) => {
        e.currentTarget.style.opacity = '0.35'
      },
      onClick,
    },
    [
      h(
        'svg',
        {
          width: 12,
          height: 12,
          viewBox: '0 0 24 24',
          fill: 'none',
          stroke: 'currentColor',
          'stroke-width': '2',
          style: 'display:block',
        },
        [
          h('path', { d: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2' }),
          h('circle', { cx: 12, cy: 7, r: 4 }),
        ],
      ),
    ],
  )

// ── Bulk edit ─────────────────────────────────────────────────────────────────

const bulkMode = ref(false)
const selectedIds = ref(new Set())
const bulkBusy = ref(false)

function enterBulkMode() {
  bulkMode.value = true
  selectedIds.value = new Set()
}

function exitBulkMode() {
  bulkMode.value = false
  selectedIds.value = new Set()
}

function toggleSelect(id) {
  const s = new Set(selectedIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  selectedIds.value = s
}

const allSelectedHidden = computed(() => {
  if (!selectedIds.value.size) return false
  const sel = store.items.filter((r) => selectedIds.value.has(r.id))
  return sel.length > 0 && sel.every((r) => r.hidden)
})

async function bulkToggleHidden() {
  const target = !allSelectedHidden.value
  bulkBusy.value = true
  try {
    await Promise.all(
      Array.from(selectedIds.value).map((id) => txApi.update(id, { hidden: target })),
    )
    exitBulkMode()
    await store.fetch()
    message.success(target ? 'Записи скрыты' : 'Записи показаны')
  } catch (e) {
    message.error(e.message)
  } finally {
    bulkBusy.value = false
  }
}

async function bulkDelete() {
  bulkBusy.value = true
  try {
    await Promise.all(Array.from(selectedIds.value).map((id) => txApi.remove(id)))
    exitBulkMode()
    await store.fetch()
    message.success('Записи удалены')
  } catch (e) {
    message.error(e.message)
  } finally {
    bulkBusy.value = false
  }
}

// Mobile-only bulk action FAB-row (см. BulkFabRow.vue). Динамически
// строим список — пустую `selectedIds` отдаём только cancel-FAB.
const mobileBulkActions = computed(() => {
  if (!selectedIds.value.size) {
    return [
      {
        icon: CloseOutline,
        title: 'Отмена выбора',
        variant: 'default',
        onClick: exitBulkMode,
      },
    ]
  }
  return [
    {
      icon: allSelectedHidden.value ? EyeOutline : EyeOffOutline,
      title: allSelectedHidden.value ? 'Показать выбранные' : 'Скрыть выбранные',
      variant: 'primary',
      loading: bulkBusy.value,
      onClick: bulkToggleHidden,
    },
    {
      icon: TrashOutline,
      title: 'Удалить выбранные',
      variant: 'danger',
      confirm: true,
      loading: bulkBusy.value,
      onClick: bulkDelete,
    },
    {
      icon: CloseOutline,
      title: 'Отмена выбора',
      variant: 'default',
      onClick: exitBulkMode,
    },
  ]
})

const selectionCheckbox = (row) => {
  const sel = selectedIds.value.has(row.id)
  return h(
    'div',
    {
      style: `cursor:pointer;display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;border:2px solid ${sel ? primaryColor.value : palette.value.text3};background:${sel ? primaryColor.value : 'transparent'};color:#fff;transition:background .15s,border-color .15s;box-sizing:border-box`,
      onClick: () => toggleSelect(row.id),
    },
    sel
      ? [
          h(
            'svg',
            {
              width: 12,
              height: 12,
              viewBox: '0 0 24 24',
              fill: 'none',
              stroke: 'currentColor',
              'stroke-width': '3',
              'stroke-linecap': 'round',
              'stroke-linejoin': 'round',
              style: 'display:block',
            },
            [h('polyline', { points: '20 6 9 17 4 12' })],
          ),
        ]
      : [],
  )
}

// ── Columns ───────────────────────────────────────────────────────────────────

// Шаблон редактируемой ячейки: editing → input + ok/cancel; wide → content
// (flex:1) + pencil top-right; compact → только displayNode (Naive column
// ellipsis truncate'ит).
function renderCell(row, field, displayNode, inputNode, opts = {}) {
  if (isEditing(row.id, field)) {
    return h('div', { style: 'display:flex;align-items:center;gap:2px;min-width:0' }, [
      inputNode,
      okBtn(() => confirmCellEdit(row, field)),
      cancelBtn(cancelCellEdit),
    ])
  }
  if (tableCompact.value || opts.hidePencil) return displayNode
  return h('div', { style: 'display:flex;align-items:flex-start;gap:4px;min-width:0;width:100%' }, [
    h('span', { style: 'flex:1;min-width:0' }, [displayNode]),
    pencilBtn(() => startCellEdit(row.id, field, opts.editValue ?? row[field] ?? '')),
  ])
}

const columns = computed(() => {
  const compact = tableCompact.value
  return [
    {
      title: '',
      key: 'created_by',
      width: 36,
      align: 'center',
      render: (row) => {
        if (bulkMode.value) return selectionCheckbox(row)
        if (!row.created_by) {
          return h(NTooltip, null, {
            trigger: () => userPlaceholder(() => openReassign(row)),
            default: () => 'Назначить автора',
          })
        }
        return h(NTooltip, null, {
          trigger: () =>
            h(
              'div',
              {
                style: 'cursor:pointer',
                onClick: () => openReassign(row),
              },
              h(UserAvatar, {
                displayName: row.created_by.display_name,
                avatarUrl: row.created_by.avatar_url || '',
                size: 24,
              }),
            ),
          default: () => `${row.created_by.display_name} · нажмите для смены`,
        })
      },
    },
    {
      title: '',
      key: 'deposit',
      width: 36,
      align: 'center',
      render: (row) =>
        h(DepositChip, {
          modelValue: row.deposit,
          editable: true,
          iconSize: 16,
          onChange: (v) => changeDeposit(row, v),
        }),
    },
    {
      title: 'Дата',
      key: 'date',
      width: 100,
      render: (row) => new Date(row.date).toLocaleDateString('ru-RU'),
    },
    {
      title: 'Категория',
      key: 'category',
      minWidth: compact ? 70 : 120,
      ...(compact ? { ellipsis: { tooltip: true } } : {}),
      render: (row) => {
        const display = row.category
          ? h(CategoryLabel, {
              name: row.category,
              category: catStore.findByName('expense', row.category),
              size: 14,
            })
          : plainTextCell('—')
        return renderCell(
          row,
          'category',
          display,
          h(NSelect, {
            value: editCellValue.value,
            options: categoryOptions.value,
            filterable: true,
            tag: true,
            renderOption: renderCategoryOption,
            renderLabel: renderCategoryLabel,
            onCreate: (val) => ({ label: val, value: val, id: null, is_default: false }),
            size: 'small',
            to: 'body',
            style: 'min-width:140px',
            'onUpdate:value': async (v) => {
              editCellValue.value = v
              const isNew = v && !catStore.bySection.expense.find((c) => c.name === v)
              if (isNew) await catStore.add('expense', v)
            },
          }),
        )
      },
    },
    {
      title: 'Назначение',
      key: 'purpose',
      minWidth: compact ? 70 : 120,
      ...(compact ? { ellipsis: { tooltip: true } } : {}),
      render: (row) =>
        renderCell(
          row,
          'purpose',
          plainTextCell(row.purpose || ''),
          h(NInput, {
            value: editCellValue.value,
            size: 'small',
            style: 'min-width:140px',
            'onUpdate:value': (v) => {
              editCellValue.value = v
            },
            onKeydown: (e) => {
              if (e.key === 'Enter') confirmCellEdit(row, 'purpose')
              if (e.key === 'Escape') cancelCellEdit()
            },
          }),
        ),
    },
    {
      title: 'Описание',
      key: 'description',
      minWidth: compact ? 70 : 120,
      ...(compact ? { ellipsis: { tooltip: true } } : {}),
      render: (row) => {
        const linkedTag = linkedWishlistTag(row)
        const descNode = linkedTag
          ? h('div', { style: 'display:flex;flex-direction:column;gap:4px;min-width:0' }, [
              linkedTag,
              row.description
                ? plainTextCell(row.description, 'opacity:0.85;font-size:12px')
                : null,
            ])
          : plainTextCell(row.description || '')
        return renderCell(
          row,
          'description',
          descNode,
          h(NInput, {
            value: editCellValue.value,
            size: 'small',
            style: 'min-width:140px',
            'onUpdate:value': (v) => {
              editCellValue.value = v
            },
            onKeydown: (e) => {
              if (e.key === 'Enter') confirmCellEdit(row, 'description')
              if (e.key === 'Escape') cancelCellEdit()
            },
          }),
        )
      },
    },
    {
      title: 'Сумма',
      key: 'amount',
      width: compact ? 110 : 150,
      render: (row) => {
        const amountNode = h(
          NText,
          {
            style: `color:${expenseColor.value};font-weight:600;transition:filter .25s${valuesHidden.value ? ';filter:blur(7px);user-select:none' : ''};white-space:nowrap`,
          },
          { default: () => `−${row.amount.toLocaleString('ru-RU')} ₽` },
        )
        return renderCell(
          row,
          'amount',
          amountNode,
          h(NInputNumber, {
            value: editCellValue.value,
            min: 0.01,
            precision: 2,
            size: 'small',
            style: 'width:120px',
            'onUpdate:value': (v) => {
              editCellValue.value = v
            },
            onKeydown: (e) => {
              if (e.key === 'Enter') confirmCellEdit(row, 'amount')
              if (e.key === 'Escape') cancelCellEdit()
            },
          }),
          { editValue: row.amount },
        )
      },
    },
    {
      title: '',
      key: 'actions',
      width: compact ? 44 : 140,
      align: 'right',
      render: (row) => {
        // Detail-request — единственный «опциональный» action (только у
        // parent-расходов). В wide-режиме он живёт в фиксированном leftmost-
        // слоте, чтобы Eye/Copy/Trash оставались в одной вертикали по всем
        // строкам (мирор Forecast'овского Refresh-placeholder pattern).
        let detailDesc = null
        if (!row.parent_id) {
          detailDesc = row.detail_request_id
            ? {
                icon: ListOutline,
                label:
                  row.detail_request_status === 'open'
                    ? 'Открыть запрос на детализацию'
                    : 'Закрытый запрос на детализацию',
                type: 'warning',
                onClick: () => openDetailRequest(row.detail_request_id),
              }
            : {
                icon: ListOutline,
                label: 'Создать запрос на детализацию',
                type: 'primary',
                onClick: () => startCreateDetailRequest(row),
              }
        }
        const eyeDesc = {
          icon: row.hidden ? EyeOffOutline : EyeOutline,
          label: row.hidden ? 'Показать' : 'Скрыть',
          type: row.hidden ? 'warning' : 'default',
          onClick: () => store.toggle(row.id, !row.hidden),
        }
        const copyDesc = {
          icon: CopyOutline,
          label: 'Добавить как шаблон',
          type: 'info',
          onClick: () => fillFromTemplate(row),
        }
        const trashDesc = {
          icon: TrashOutline,
          label: 'Удалить',
          type: 'error',
          confirm: 'Удалить запись?',
          onClick: () => store.remove(row.id),
        }
        if (compact) {
          return renderActionsPopover([detailDesc, eyeDesc, copyDesc, trashDesc].filter(Boolean))
        }
        return h(
          'div',
          { style: 'display:flex;justify-content:flex-end;align-items:center;gap:2px' },
          [
            detailDesc ? renderActionButton(detailDesc) : h('div', { style: 'width:28px' }),
            renderActionButton(eyeDesc),
            renderActionButton(copyDesc),
            renderActionButton(trashDesc),
          ],
        )
      },
    },
  ]
})

const pagination = computed(() => ({
  page: store.page,
  pageSize: store.limit,
  itemCount: store.total,
  showSizePicker: false,
}))

// When arriving from a Statistics pie-slice drilldown, the route carries
// `categories` (comma-separated) + `from`/`to` (ISO date). Hydrate the
// local filter refs so the popover/inline filter UI also reflects the
// active filter, then push the filter through to the store.
function hydrateFromQuery() {
  const q = route.query || {}
  const cats = typeof q.categories === 'string' ? q.categories : ''
  const from = typeof q.from === 'string' ? q.from : ''
  const to = typeof q.to === 'string' ? q.to : ''
  if (!cats && !from && !to) return false
  filterCategories.value = cats ? cats.split(',').filter(Boolean) : []
  if (from && to) {
    filterRange.value = [new Date(from).getTime(), new Date(to).getTime()]
  } else {
    filterRange.value = null
  }
  applyFilters()
  return true
}

watch(
  () => route.query,
  () => {
    if (route.path === '/expenses') hydrateFromQuery()
  },
)

onMounted(async () => {
  catStore.load('expense')
  const hydrated = hydrateFromQuery()
  if (!hydrated) store.setFilters({ type: 'expense' })
  // Wishlist is fire-and-forget: rows render fine without it (just no badge);
  // once it resolves, the «Привязано к…» tag pops in via reactive map.
  wlStore.fetch()
  await Promise.all([drStore.fetchAll(), loadLimitsProgress()])
  await loadMyOpenParents()
})

// Re-pull progress whenever the transaction list mutates so the bar stays
// in sync with create/update/delete without a page refresh. `deep:true`
// because the store mutates items in place after CRUD. Same hook also
// refreshes the notification badge — overflow alerts only fire after the
// async backend trigger lands, so we give it a brief settle delay.
watch(
  () => store.items,
  () => {
    loadLimitsProgress()
    setTimeout(() => notifStore.fetchAll(), 800)
  },
  { deep: true },
)

watch(
  () => drStore.items,
  async () => {
    await loadMyOpenParents()
  },
  { deep: true },
)
</script>

<style scoped>
.dr-pinned-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  opacity: 0.7;
  margin-bottom: 6px;
}
.dr-pinned-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 4px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
}
.dr-pinned-row:hover {
  background: rgba(240, 160, 32, 0.18);
}
.dr-pinned-row + .dr-pinned-row {
  border-top: 1px dashed rgba(240, 160, 32, 0.25);
}

/* Mobile-only n-card header content (см. IncomeView). */
.card-back-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-back-header :deep(.n-button) {
  margin-right: 2px;
}

.card-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.form-actions-row {
  display: flex;
  gap: 8px;
}

.filter-popover {
  width: min(280px, calc(100vw - 32px));
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.filter-popover-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-weight: 600;
  color: var(--text-1, currentColor);
  opacity: 0.85;
  margin: 6px 0 2px;
}

/* Mobile transaction card list (см. IncomeView для подробностей). */
.tx-cards,
.tx-cards-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
/* Layout строки внутри n-card (см. IncomeView). */
.tx-row {
  display: flex;
  align-items: center;
  gap: 10px;
  -webkit-tap-highlight-color: transparent;
}
.tx-row.hidden > * {
  opacity: 0.45;
}
:deep(.sc-content) .n-card {
  transition: border-radius 0.2s ease-out;
}
:deep(.sc-content.revealed) .n-card {
  border-top-right-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}
.tx-card-left {
  flex-shrink: 0;
  width: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.tx-card-body {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
}
.tx-card-row1 {
  display: flex;
  /* `center` (not `baseline`): CategoryLabel is `inline-flex` with its own
     `line-height: 1`, so its baseline is computed from the icon box, not
     the text — baseline alignment lands the category label visibly above
     the date sibling. `center` aligns by box midline and matches Android. */
  align-items: center;
  gap: 8px;
  font-size: 14px;
  line-height: 1.2;
}
.tx-card-date {
  color: var(--text-3, rgba(127, 127, 127, 0.8));
  font-variant-numeric: tabular-nums;
  /* Date is a fixed 10-char string — keep it on one line so narrow cards
     don't break "17.05.2026" across two lines. */
  white-space: nowrap;
  flex-shrink: 0;
}
.tx-card-category {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.tx-card-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3, rgba(127, 127, 127, 0.75));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tx-card-amount {
  flex-shrink: 0;
  font-weight: 600;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
  transition: filter 0.25s;
  user-select: none;
}

.swipe-action {
  width: 60px;
  height: 100%;
  border: 0;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #fff;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.swipe-action-label {
  font-size: 10px;
  line-height: 1;
  color: #fff;
}
/* Naive UI палитра. Семантика по экшенам: Hide → info (синий, действие
   неразрушительное, лишь меняет видимость); Template → warning (оранжевый,
   привлекает внимание т.к. переносит данные в форму добавления); Delete →
   error. Раньше пара была инвертирована — теперь синий = «нейтральный
   toggle», оранжевый = «действие повлияет на форму». */
.swipe-action-info {
  background: #2080f0;
}
.swipe-action-warning {
  background: #f0a020;
}
.swipe-action-danger {
  background: #d03050;
}

.dep-radio-content {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  line-height: 1;
}

.bulk-circle {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 2px solid var(--text-3, rgba(127, 127, 127, 0.65));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: transparent;
  transition:
    background 0.15s,
    border-color 0.15s;
}
.bulk-circle.checked {
  background: var(--primary, #2080f0);
  border-color: var(--primary, #2080f0);
}
</style>
