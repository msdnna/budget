<template>
  <div>
    <SplitPane storage-key="income-split" :default-left="45" :min-left="25" :max-left="60">
      <template #left>
        <!-- Initial balance card. Sits outside the add-form wrapper below
             so the mobile layout shows it above the history list (matches
             ExpensesView's "Лимит расходов" placement). Visibility: always
             on desktop; on mobile only in history mode (hidden during add
             and edit — the IB is foreign context when the user is filling
             a single record). -->
        <n-card v-show="!isMobile || (!mobileAdding && !mobileEditing)" style="margin-bottom: 12px">
          <template #header>
            <n-space align="center" justify="space-between" style="width: 100%">
              <n-text strong>Баланс на начало месяца</n-text>
              <TilePeriodPicker
                v-model:value="ibMonth"
                type="month"
                size="small"
                width="170px"
                @update:value="loadInitialBalance"
              />
            </n-space>
          </template>
          <div class="ib-rows">
            <div v-for="d in DEPOSITS" :key="d.value" class="ib-row">
              <span class="ib-row-label">
                <n-icon :component="d.icon" :size="16" />
                <span>{{ d.label }}</span>
              </span>
              <span
                v-if="ibByDeposit[d.value]"
                :style="`color:${incomeColor};font-weight:600;font-size:14px${valuesHidden ? ';filter:blur(7px);user-select:none' : ''}`"
              >
                {{ ibByDeposit[d.value].amount.toLocaleString('ru-RU') }} ₽
              </span>
              <n-text v-else depth="3" style="font-size: 13px">Не задан</n-text>
              <n-button
                v-if="ibByDeposit[d.value]"
                size="tiny"
                quaternary
                type="error"
                title="Удалить"
                @click="deleteIb(d.value)"
              >
                <template #icon><n-icon :component="TrashOutline" /></template>
              </n-button>
            </div>
          </div>
          <n-button size="small" type="primary" block style="margin-top: 8px" @click="openIbForm">
            {{ ibByDeposit.bank || ibByDeposit.cash ? 'Изменить' : 'Задать' }}
          </n-button>
        </n-card>

        <!-- На мобильном левый pane по умолчанию скрыт. Кнопка `+ Добавить
             доход` (см. #right) переключает в add-view; стрелка ← в header
             n-card возвращает в историю. На десктопе обе панели видны рядом.
             `<Transition>` даёт mobile-only slide+fade при swap'е (см.
             theme.css `.mobile-slide-*`). На desktop'е CSS-классы no-op
             через `@media (max-width:767px)`-гейт. -->
        <Transition name="mobile-slide">
          <div v-show="!isMobile || mobileAdding || mobileEditing">
            <n-card>
              <template #header>
                <div v-if="isMobile && (mobileAdding || mobileEditing)" class="card-back-header">
                  <n-button text size="small" @click="exitMobileAddEdit">
                    <template #icon><n-icon :component="ArrowBackOutline" /></template>
                  </n-button>
                  <n-text strong style="font-size: 16px">
                    {{ mobileEditing ? 'Изменить доход' : 'Новый доход' }}
                  </n-text>
                </div>
                <span v-else>Добавить доход</span>
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
                    <n-form-item label="Источник">
                      <!-- NAutoComplete тянет options из localStorage-кэша
                           (см. utils/inputHistory.js). `get-show: () => true`
                           показывает dropdown даже при пустом input'е — на
                           focus юзер сразу видит свои частые «Магнит /
                           Зарплата / ...» без необходимости начать печатать. -->
                      <n-auto-complete
                        v-model:value="form.source"
                        :options="sourceHistoryOptions"
                        :get-show="() => true"
                        placeholder="Откуда получен доход"
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
                  <n-grid-item span="2">
                    <n-form-item label="Описание">
                      <n-input
                        v-model:value="form.description"
                        placeholder="Дополнительно (необязательно)"
                      />
                    </n-form-item>
                  </n-grid-item>
                </n-grid>
                <!-- Edit-режим на мобильном: Удалить + Сохранить в одну строку
                   (red ghost слева, primary справа). Иначе — один full-width
                   submit-button. -->
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
                  Добавить доход
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
            <n-card title="История доходов">
              <n-space style="margin-bottom: 12px" wrap align="center" justify="space-between">
                <!-- Desktop: фильтры inline. -->
                <n-space v-if="!isMobile" wrap>
                  <n-date-picker
                    v-model:value="filterRange"
                    type="daterange"
                    clearable
                    size="small"
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
                  <n-checkbox v-model:checked="showSplit" @update:checked="applyFilters">
                    Показать разделённые
                  </n-checkbox>
                </n-space>
                <!-- Mobile: иконка-кнопка с popover, чтобы фильтры не съедали
                   две полные строки в шапке списка. -->
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
                    <div class="filter-popover-label">Разделённые</div>
                    <n-checkbox v-model:checked="showSplit" @update:checked="applyFilters">
                      Показать
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
                       действия дублируются в `<BulkFabRow>` снизу-справа
                       (см. ниже). -->
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
              <!-- Desktop: NDataTable с инлайн-редактированием по ячейкам.
                 Обёрнут в div с ref для ResizeObserver — отслеживает
                 фактическую ширину pane для compact-переключения. -->
              <div v-if="!isMobile" ref="tablePaneRef">
                <n-data-table
                  size="small"
                  :columns="columns"
                  :data="store.items"
                  :loading="store.loading"
                  :pagination="pagination"
                  :row-props="getRowProps"
                  :row-class-name="getRowClass"
                  remote
                  @update:page="store.setPage"
                />
              </div>
              <!-- Mobile: список карточек. Тап → edit-вид (та же форма
                 «Добавить», pre-fill'енная). Долгий тап → bulk-режим. -->
              <div v-else class="tx-cards">
                <n-spin v-if="store.loading && !store.items.length" style="margin: 24px 0" />
                <n-empty
                  v-else-if="!store.items.length"
                  description="Записей нет"
                  style="padding: 32px 0"
                />
                <!-- `<TransitionGroup>` оборачивает листинг для collapse-leave
                   анимации (см. theme.css `.tx-list-*`). enter не анимируем —
                   при первом рендере страницы это создаёт водопад flicker'ов.
                   Только leave: удалённая карточка схлопывается по высоте +
                   уезжает влево с opacity 0. -->
                <TransitionGroup v-else name="tx-list" tag="div" class="tx-cards-list">
                  <SwipeableCard
                    v-for="row in store.items"
                    :key="row.id"
                    :data-focus-id="row.id"
                    :class="{ 'tx-card-focus': focusedId === row.id }"
                    :long-press-ms="bulkMode ? 0 : 1000"
                    :radius="3"
                    :reveal-left-width="canMobileSplitAction(row) ? 96 : 0"
                    @tap="onCardTap(row)"
                    @longpress="onCardLongPress(row.id)"
                  >
                    <template v-if="canMobileSplitAction(row)" #actionsLeft>
                      <button
                        v-if="row.excluded_from_stats && !row.parent_id"
                        class="swipe-action swipe-action-warning"
                        title="Расформировать"
                        @click="onUnsplit(row)"
                      >
                        <n-icon :component="GitMergeOutline" :size="20" />
                        <span class="swipe-action-label">Расформ.</span>
                      </button>
                      <button
                        v-else-if="!row.parent_id && !row.excluded_from_stats"
                        class="swipe-action swipe-action-primary"
                        title="Разделить доход"
                        @click="openSplit(row)"
                      >
                        <n-icon :component="GitMergeOutline" :size="20" />
                        <span class="swipe-action-label">Разделить</span>
                      </button>
                    </template>
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
                    <!-- n-card embedded — единый visual style с Forecast'овыми
                     карточками (тот же surface bg, border, radius от Naive
                     темы). Flex-разметка живёт во вложенном `.tx-row`. -->
                    <n-card size="small" :bordered="true" embedded :style="cardStyle(row)">
                      <div class="tx-row" :class="{ hidden: row.hidden }">
                        <div class="tx-card-left">
                          <!-- Avatar ↔ bulk-circle fade-swap; см. theme.css
                           `.bulk-icon-*`. mode="out-in" даёт sequential
                           fade-out → fade-in вместо одновременного. -->
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
                              style="cursor: pointer"
                              @click.stop="openReassign(row)"
                            />
                          </Transition>
                        </div>
                        <div class="tx-card-body">
                          <div class="tx-card-row1">
                            <span class="tx-card-date">
                              {{ new Date(row.date).toLocaleDateString('ru-RU') }}
                            </span>
                            <!-- Mobile: read-only deposit indicator. Editing
                                 lives in the edit-form (open via tap on row);
                                 a dropdown here was confusing because the tap
                                 target overlaps the row's tap-to-edit gesture. -->
                            <DepositChip :model-value="row.deposit" :icon-size="14" />
                            <CategoryLabel
                              class="tx-card-category"
                              :name="row.category"
                              :category="catStore.findByName('income', row.category)"
                              :size="14"
                            />
                          </div>
                          <div
                            v-if="
                              row.source ||
                              row.description ||
                              row.parent_id ||
                              (row.excluded_from_stats && !row.detail_request_status)
                            "
                            class="tx-card-desc"
                          >
                            <n-tag
                              v-if="row.parent_id"
                              size="small"
                              round
                              :bordered="false"
                              style="cursor: pointer; margin-right: 4px"
                              title="Перейти к родительской записи"
                              @click.stop="goToParent(row)"
                            >
                              <template #icon>
                                <n-icon :component="ArrowUpOutline" />
                              </template>
                              К родителю
                            </n-tag>
                            <n-tag
                              v-else-if="row.excluded_from_stats && !row.detail_request_status"
                              type="warning"
                              size="small"
                              round
                              :bordered="false"
                              style="margin-right: 4px"
                              title="Родитель разделения (дочерние — записи с этим же цветом группы)"
                            >
                              <template #icon>
                                <n-icon :component="GitMergeOutline" />
                              </template>
                              Разделено
                            </n-tag>
                            {{ [row.source, row.description].filter(Boolean).join(' · ') }}
                          </div>
                        </div>
                        <div
                          class="tx-card-amount"
                          :style="{
                            color: incomeColor,
                            filter: valuesHidden ? 'blur(7px)' : 'none',
                          }"
                        >
                          +{{ row.amount.toLocaleString('ru-RU') }} ₽
                        </div>
                      </div>
                    </n-card>
                  </SwipeableCard>
                </TransitionGroup>
                <n-pagination
                  v-if="store.total > store.limit"
                  v-model:page="mobilePage"
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

    <!-- Mobile FAB — поверх списка истории, открывает add-вид. Скрыт во
         время add/edit, потому что они уже видны вместо списка. -->
    <!-- В bulk-mode на mobile FAB-«+» подменяется на ряд action-FAB'ов
         (mirror Android Scaffold floatingActionButton — IncomeScreen.kt).
         `<Transition mode="out-in">` делает sequential fade+scale свап
         (см. theme.css `.fab-swap-*`). -->
    <Transition name="fab-swap" mode="out-in">
      <FabButton
        v-if="isMobile && !mobileAdding && !mobileEditing && !bulkMode"
        key="add"
        title="Добавить доход"
        @click="enterMobileAdd"
      />
      <BulkFabRow
        v-else-if="isMobile && bulkMode && !mobileAdding && !mobileEditing"
        key="bulk"
        :actions="mobileBulkActions"
      />
    </Transition>

    <!-- Initial balance modal -->
    <n-modal
      v-model:show="showIbForm"
      preset="card"
      title="Баланс на начало месяца"
      style="max-width: 360px"
    >
      <n-tabs v-model:value="ibTab" type="line" animated class="ib-tabs">
        <n-tab-pane v-for="d in DEPOSITS" :key="d.value" :name="d.value">
          <template #tab>
            <span class="ib-tab-label">
              <n-icon :component="d.icon" />
              {{ d.label }}
            </span>
          </template>
          <n-form label-placement="top" class="ib-tab-form">
            <n-form-item :label="`Сумма на ${d.label.toLowerCase()} (₽)`">
              <n-input-number
                v-model:value="ibFormAmount[d.value]"
                :min="0"
                :precision="2"
                style="width: 100%"
                placeholder="0.00"
                clearable
              />
            </n-form-item>
          </n-form>
        </n-tab-pane>
      </n-tabs>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showIbForm = false">Отмена</n-button>
          <n-button type="primary" :loading="ibSaving" @click="saveIb">Сохранить</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- Split income modal -->
    <SplitIncomeModal
      :show="splitModal.show"
      :transaction="splitModal.tx"
      @close="splitModal = { show: false, tx: null }"
      @confirmed="onSplitConfirm"
    />

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
import { ref, computed, nextTick, onMounted, onUnmounted, watch, h } from 'vue'
import { useRoute } from 'vue-router'
function toLocalDateString(ts) {
  const d = new Date(ts)
  const offset = d.getTimezoneOffset()
  return new Date(d.getTime() - offset * 60000).toISOString()
}
import { useMessage, useDialog } from 'naive-ui'
import {
  NCard,
  NCheckbox,
  NGrid,
  NGridItem,
  NForm,
  NFormItem,
  NAutoComplete,
  NInput,
  NInputNumber,
  NSelect,
  NTag,
  NDatePicker,
  NButton,
  NDataTable,
  NSpace,
  NText,
  NPopconfirm,
  NTooltip,
  NModal,
  NSpin,
  NEmpty,
  NPagination,
  NPopover,
  NList,
  NListItem,
  NIcon,
  NRadioGroup,
  NRadioButton,
  NTabs,
  NTabPane,
} from 'naive-ui'
import {
  ArrowBackOutline,
  CheckmarkOutline,
  CloseOutline,
  CopyOutline,
  EyeOffOutline,
  EyeOutline,
  FunnelOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import { useTransactionsStore } from '@/stores/transactions'
import { useThemeStore } from '@/stores/theme'
import { useCategoriesStore } from '@/stores/categories'
import { storeToRefs } from 'pinia'
import SplitPane from '@/components/SplitPane.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import TilePeriodPicker from '@/components/TilePeriodPicker.vue'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'
import FabButton from '@/components/FabButton.vue'
import BulkFabRow from '@/components/BulkFabRow.vue'
import SwipeableCard from '@/components/SwipeableCard.vue'
import CategoryLabel from '@/components/CategoryLabel.vue'
import DepositChip from '@/components/DepositChip.vue'
import { DEPOSITS, DEPOSIT_DEFAULT, normalizeDeposit } from '@/utils/deposit'
import { historyOptions, pushHistory } from '@/utils/inputHistory'
import { users as usersApi, transactions as txApi } from '@/api'
import { useAdaptiveTable, plainTextCell, renderActionsRow } from '@/utils/adaptiveTable'
import SplitIncomeModal from '@/components/SplitIncomeModal.vue'
import { groupClass, groupSolidBg } from '@/utils/groupColor'
import { ArrowUpOutline, GitMergeOutline } from '@vicons/ionicons5'

const store = useTransactionsStore('income')
const { palette, valuesHidden, primaryColor } = storeToRefs(useThemeStore())
const incomeColor = computed(() => palette.value.income)
const message = useMessage()
const dialog = useDialog()
const splitModal = ref({ show: false, tx: null })
const showSplit = ref(false)
const formRef = ref(null)
const saving = ref(false)
const filterRange = ref(null)
const filterCategories = ref([])
const filterDeposit = ref('')
const route = useRoute()

const form = ref({
  amount: null,
  date: Date.now(),
  category: '',
  source: '',
  description: '',
  deposit: DEPOSIT_DEFAULT,
})

// LocalStorage-кэш недавно введённых «Источников» (NAutoComplete options).
// Re-load после submit'a — чтобы только что добавленное значение всплыло в
// списке без перезагрузки страницы. Ref, не computed: handler в submit()
// должен иметь возможность мутировать после pushHistory.
const sourceHistoryOptions = ref(historyOptions('income-source'))
function refreshSourceHistory() {
  sourceHistoryOptions.value = historyOptions('income-source')
}

// ── Mobile add/edit nav ─────────────────────────────────────────────
// На мобильном левый pane (форма + initial balance) спрятан, история
// видна сразу. «+ Добавить» открывает add-вид; тап по карточке записи
// открывает edit-вид (та же форма, pre-fill). Submit-success возвращает
// в историю. Долгий тап на карточке включает bulk-режим + select.
const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
const isMobile = computed(() => windowWidth.value < 768)
const mobileAdding = ref(false)
const mobileEditing = ref(null) // row being edited, null otherwise
const mobilePage = computed(() => store.page)

// Responsive desktop-таблица: ниже 740px ширины ResizeObserver включает
// compact-режим — actions сворачиваются в «•••» popover, pencil-карандашики
// скрываются, текстовые колонки получают ellipsis с tooltip'ом.
const { paneRef: tablePaneRef, compact: tableCompact } = useAdaptiveTable()

function onWinResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

function enterMobileAdd() {
  mobileEditing.value = null
  form.value = {
    amount: null,
    date: Date.now(),
    category: '',
    source: '',
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
    source: row.source || '',
    description: row.description || '',
    deposit: normalizeDeposit(row.deposit),
  }
}

function exitMobileAddEdit() {
  mobileAdding.value = false
  mobileEditing.value = null
}

// ── Card events (long-press / tap эмитят SwipeableCard) ─────────────
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

// ── Initial balance ───────────────────────────────────────────────────────────
//
// Initial balance lives independently per deposit scope (bank / cash) — each
// scope has its own running balance, so a single sum doesn't make sense.
// We keep one record per scope per month; the modal lets the user edit both
// at once via tabs, and the header card surfaces both values stacked.

const ibMonth = ref(Date.now())
const ibByDeposit = ref({ bank: null, cash: null })
const showIbForm = ref(false)
const ibTab = ref(DEPOSIT_DEFAULT)
const ibFormAmount = ref({ bank: null, cash: null })
const ibSaving = ref(false)

async function loadInitialBalance() {
  const d = new Date(ibMonth.value)
  const from = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
  const lastDay = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()
  const to = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`
  try {
    const { data } = await txApi.list({ type: 'initial_balance', from, to, limit: 10 })
    const rows = data.data || []
    // Pick the latest record per deposit (server returns desc by date).
    const next = { bank: null, cash: null }
    for (const r of rows) {
      const dep = normalizeDeposit(r.deposit)
      if (!next[dep]) next[dep] = r
    }
    ibByDeposit.value = next
  } catch {
    ibByDeposit.value = { bank: null, cash: null }
  }
}

function openIbForm() {
  ibFormAmount.value = {
    bank: ibByDeposit.value.bank?.amount ?? null,
    cash: ibByDeposit.value.cash?.amount ?? null,
  }
  ibTab.value = DEPOSIT_DEFAULT
  showIbForm.value = true
}

async function saveIb() {
  ibSaving.value = true
  try {
    const d = new Date(ibMonth.value)
    const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
    // Upsert each scope only when its amount actually changed (avoids
    // bumping updated_at on the untouched record and the noise that brings
    // through sync to Android clients).
    for (const dep of ['bank', 'cash']) {
      const next = ibFormAmount.value[dep]
      const existing = ibByDeposit.value[dep]
      const prev = existing?.amount ?? null
      if (next === prev || next == null) continue
      if (existing) {
        await txApi.update(existing.id, { amount: next, deposit: dep })
      } else {
        await txApi.create({
          type: 'initial_balance',
          amount: next,
          date,
          category: 'Начальный баланс',
          deposit: dep,
        })
      }
    }
    await loadInitialBalance()
    showIbForm.value = false
    message.success('Начальный баланс сохранён')
  } catch (e) {
    message.error(e.message)
  } finally {
    ibSaving.value = false
  }
}

async function deleteIb(deposit) {
  const row = ibByDeposit.value[deposit]
  if (!row) return
  try {
    await txApi.remove(row.id)
    await loadInitialBalance()
    message.success('Начальный баланс удалён')
  } catch (e) {
    message.error(e.message)
  }
}

const catStore = useCategoriesStore()
const categoryOptions = computed(() => catStore.options('income'))

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
            await catStore.remove(option.id, 'income')
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
// tag selects also call this for the in-input chip via Naive's internal
// rendering, so we don't need a separate `render-tag` there — adding one was
// the root cause of the empty-placeholder chip bug.
function renderCategoryLabel(option) {
  return h(CategoryLabel, {
    name: option.value,
    category: catStore.findByName('income', option.value),
    size: 14,
  })
}

// Multi-select filter pills: wrap CategoryLabel inside NTag so the chip
// inherits Naive's small-size dimensions and matches the date-picker
// height next to it. Guarded against empty option — Naive can invoke
// render-tag with a placeholder ghost option, which previously produced a
// stray empty chip with a close button.
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
          category: catStore.findByName('income', option.value),
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
    if (cat && !catStore.bySection.income.find((c) => c.name === cat)) {
      await catStore.add('income', cat).catch(() => {})
    }
    const payload = {
      amount: form.value.amount,
      date: toLocalDateString(form.value.date).split('T')[0],
      category: cat,
      source: form.value.source,
      description: form.value.description,
      deposit: normalizeDeposit(form.value.deposit),
    }
    if (mobileEditing.value) {
      await store.update(mobileEditing.value.id, payload)
      message.success('Сохранено')
    } else {
      await store.create({ type: 'income', ...payload })
      message.success('Доход добавлен')
    }
    catStore.recordUse('income', cat)
    // Кладём «Источник» в localStorage-историю для autocomplete'a на
    // следующем добавлении (см. utils/inputHistory.js). Пустые значения
    // отфильтрует сам pushHistory.
    pushHistory('income-source', form.value.source)
    refreshSourceHistory()
    form.value = {
      amount: null,
      date: Date.now(),
      category: '',
      source: '',
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

// Mobile swipe-right gating: only canonical rows + split-parents get a left
// action; split-children expose nothing on swipe-right.
function canMobileSplitAction(row) {
  return !row.parent_id
}

// Mobile card background: solid composite so the swipe rail doesn't bleed
// through during gesture. Priority: bulk-selected > group tint.
function cardStyle(row) {
  if (bulkMode.value && selectedIds.value.has(row.id)) {
    return `background:${primaryColor.value}1f`
  }
  const bg = groupSolidBg(row, palette.value.cardSurface || '#FFFFFF')
  return bg ? `background:${bg}` : ''
}

// Desktop row props: opacity for hidden + bulk-selected style. Group tint is
// applied via `row-class-name` (see [getRowClass]) because Naive paints td
// backgrounds that override row inline-style.
function getRowProps(row) {
  const styles = []
  if (row.hidden) styles.push('opacity:0.4')
  if (bulkMode.value && selectedIds.value.has(row.id)) {
    styles.push(`background:${primaryColor.value}1f`)
  }
  return { style: styles.join(';') }
}

function getRowClass(row) {
  const cls = []
  if (row.id === focusedId.value) cls.push('tx-row-focus')
  const g = groupClass(row)
  if (g) cls.push(g)
  return cls.join(' ')
}

// ── Parent-navigation: jump from a split child to its split parent (hidden by
// default). Flip «показать разделённые», wait for the refetch, focus + scroll.
const focusedId = ref('')

async function waitForListIdle() {
  await nextTick()
  for (let i = 0; i < 60 && store.loading; i++) {
    await new Promise((r) => setTimeout(r, 40))
  }
}

async function focusRowById(id) {
  focusedId.value = id
  await nextTick()
  await new Promise((r) => setTimeout(r, 60))
  // Mobile cards carry `data-focus-id`; desktop rows get `.tx-row-focus`.
  const el =
    document.querySelector(`[data-focus-id="${id}"]`) ||
    document.querySelector('.n-data-table-tr.tx-row-focus')
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function goToParent(row) {
  if (!row.parent_id) return
  if (!showSplit.value) {
    showSplit.value = true
    applyFilters()
    await waitForListIdle()
  }
  await focusRowById(row.parent_id)
}

// «↑ к родителю» on split children; static «Разделено» marker on the split
// parent so parent vs child are tellable apart (same group colour links them).
function groupRoleTag(row) {
  if (row.parent_id) {
    return h(
      NTag,
      {
        size: 'small',
        round: true,
        bordered: false,
        style: 'cursor:pointer',
        title: 'Перейти к родительской записи',
        onClick: (e) => {
          e.stopPropagation()
          goToParent(row)
        },
      },
      {
        icon: () => h(NIcon, null, { default: () => h(ArrowUpOutline) }),
        default: () => 'К родителю',
      },
    )
  }
  // Split parent: income + excluded + no DR status.
  if (row.excluded_from_stats && !row.parent_id && !row.detail_request_status) {
    return h(
      NTag,
      {
        type: 'warning',
        size: 'small',
        round: true,
        bordered: false,
        title: 'Родитель разделения (дочерние — записи с этим же цветом группы)',
      },
      {
        icon: () => h(NIcon, null, { default: () => h(GitMergeOutline) }),
        default: () => 'Разделено',
      },
    )
  }
  return null
}

function fillFromTemplate(row) {
  form.value = {
    amount: row.amount,
    date: Date.now(),
    category: row.category,
    source: row.source || '',
    description: row.description || '',
    deposit: normalizeDeposit(row.deposit),
  }
  if (isMobile.value) {
    // На мобилке форма скрыта пока не нажат FAB-«+» / тап по записи —
    // см. ExpensesView.fillFromTemplate для развёрнутой мотивации.
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
  const f = { type: 'income', categories: filterCategories.value }
  if (filterRange.value) {
    f.from = fmtLocalDate(filterRange.value[0])
    f.to = fmtLocalDate(filterRange.value[1])
  }
  if (filterDeposit.value) f.deposit = filterDeposit.value
  if (showSplit.value) f.includeSplit = true
  store.setFilters(f)
}

// Сколько фильтров активно — для badge на мобильной кнопке «Фильтр».
const activeFilterCount = computed(() => {
  let n = 0
  if (filterRange.value) n += 1
  if (filterCategories.value?.length) n += 1
  if (filterDeposit.value) n += 1
  if (showSplit.value) n += 1
  return n
})

function resetFilters() {
  filterRange.value = null
  filterCategories.value = []
  filterDeposit.value = ''
  showSplit.value = false
  applyFilters()
}

const depositFilterOptions = [
  { label: 'Все', value: '' },
  ...DEPOSITS.map((d) => ({ label: d.label, value: d.value })),
]

// ── Inline cell editing ───────────────────────────────────────────────────────

const editingCell = ref(null) // { id, field }
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
    if (field === 'category') catStore.recordUse('income', editCellValue.value)
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

// ── Split / unsplit ──────────────────────────────────────────────────────────

function openSplit(row) {
  splitModal.value = { show: true, tx: row }
}

async function onSplitConfirm(splits) {
  const tx = splitModal.value.tx
  if (!tx) return
  try {
    await store.split(tx.id, splits)
    message.success('Доход разделён')
    splitModal.value = { show: false, tx: null }
  } catch (e) {
    message.error(e.message)
  }
}

function onUnsplit(row) {
  dialog.warning({
    title: 'Расформировать разделённый доход?',
    content: 'Все части будут удалены, исходная запись восстановится.',
    positiveText: 'Расформировать',
    negativeText: 'Отмена',
    onPositiveClick: async () => {
      try {
        await store.unsplit(row.id)
        message.success('Запись восстановлена')
      } catch (e) {
        message.error(e.message)
      }
    },
  })
}

// Single delete handler that branches: split children/parents go through the
// unsplit flow with a confirmation dialog, regular rows take the plain delete
// path (popconfirm inside the action button itself).
function onDeleteRow(row) {
  if (row.parent_id) {
    dialog.warning({
      title: 'Удалить часть разделённого дохода?',
      content:
        'Все части будут удалены, а исходная запись восстановится. Затем её можно удалить или разделить заново.',
      positiveText: 'Расформировать',
      negativeText: 'Отмена',
      onPositiveClick: async () => {
        try {
          await store.unsplit(row.parent_id)
          message.success('Запись восстановлена')
        } catch (e) {
          message.error(e.message)
        }
      },
    })
    return
  }
  if (row.excluded_from_stats) {
    onUnsplit(row)
    return
  }
  store.remove(row.id)
}

async function changeDeposit(row, deposit) {
  try {
    await store.update(row.id, { deposit: normalizeDeposit(deposit) })
    message.success(`Счёт: ${deposit === 'cash' ? 'Наличные' : 'Банковская карта'}`)
  } catch (e) {
    message.error(e.message)
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
// строим список — пустую `selectedIds` отдаём пустой массив, чтобы row
// не маячил без причины. Cancel-FAB не requires confirm (он не
// destructive); delete — да, чтобы случайный тап не снес выбор.
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

// Шаблон редактируемой ячейки. Editing → input + ok/cancel; wide → content
// (flex:1) + pencil top-right; compact → только displayNode (Naive's column
// ellipsis truncate'ит без обёртки).
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
              category: catStore.findByName('income', row.category),
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
              const isNew = v && !catStore.bySection.income.find((c) => c.name === v)
              if (isNew) await catStore.add('income', v)
            },
          }),
        )
      },
    },
    {
      title: 'Источник',
      key: 'source',
      minWidth: compact ? 70 : 120,
      ...(compact ? { ellipsis: { tooltip: true } } : {}),
      render: (row) =>
        renderCell(
          row,
          'source',
          plainTextCell(row.source || ''),
          h(NInput, {
            value: editCellValue.value,
            size: 'small',
            style: 'min-width:140px',
            'onUpdate:value': (v) => {
              editCellValue.value = v
            },
            onKeydown: (e) => {
              if (e.key === 'Enter') confirmCellEdit(row, 'source')
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
        const roleTag = groupRoleTag(row)
        const descNode = roleTag
          ? h('div', { style: 'display:flex;flex-direction:column;gap:4px;min-width:0' }, [
              roleTag,
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
            style: `color:${incomeColor.value};font-weight:600;transition:filter .25s${valuesHidden.value ? ';filter:blur(7px);user-select:none' : ''};white-space:nowrap`,
          },
          { default: () => `+${row.amount.toLocaleString('ru-RU')} ₽` },
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
      width: compact ? 44 : 110,
      align: 'right',
      render: (row) => {
        const isSplitParent = row.excluded_from_stats && !row.parent_id
        const isSplitChild = !!row.parent_id
        // Quick: только показать/скрыть и шаблон (две частые операции,
        // template на split-child тоже валиден — делает копию-черновик).
        const quick = [
          {
            icon: row.hidden ? EyeOffOutline : EyeOutline,
            label: row.hidden ? 'Показать' : 'Скрыть',
            type: row.hidden ? 'warning' : 'default',
            onClick: () => store.toggle(row.id, !row.hidden),
          },
          {
            icon: CopyOutline,
            label: 'Добавить как шаблон',
            type: 'info',
            onClick: () => fillFromTemplate(row),
          },
        ]
        // More: split/unsplit вверху, удалить — всегда последним.
        const more = []
        if (!isSplitChild && !isSplitParent) {
          more.push({
            icon: GitMergeOutline,
            label: 'Разделить доход',
            type: 'success',
            onClick: () => openSplit(row),
          })
        } else if (isSplitParent) {
          more.push({
            icon: GitMergeOutline,
            label: 'Расформировать',
            type: 'warning',
            onClick: () => onUnsplit(row),
          })
        }
        more.push({
          icon: TrashOutline,
          label: 'Удалить',
          type: 'error',
          confirm: isSplitChild || isSplitParent ? undefined : 'Удалить запись?',
          onClick: () => onDeleteRow(row),
        })
        return renderActionsRow({ quick, more, compact })
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
    if (route.path === '/income') hydrateFromQuery()
  },
)

onMounted(() => {
  catStore.load('income')
  const hydrated = hydrateFromQuery()
  if (!hydrated) store.setFilters({ type: 'income' })
  loadInitialBalance()
})
</script>

<style scoped>
/* Mobile-only n-card header content: back-arrow + title in the same row,
   gives the add-form the same one-frame look as AdminCategoriesView's
   editor (single bordered surface, no extra header above the card). */
.card-back-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-back-header :deep(.n-button) {
  /* small extra inset так, чтобы стрелка не липла к рамке/заголовку */
  margin-right: 2px;
}

/* Card header с inline + кнопкой (как в UsersAdminView): заголовок слева,
   кнопка «+ Добавить» справа. */
.card-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

/* Edit-режим: Удалить + Сохранить в одну строку (flex: 1 каждой). */
.form-actions-row {
  display: flex;
  gap: 8px;
}

/* Mobile filter popover — компактные стек'и контролов. */
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
  /* На тёмной теме var(--text-3) сливался с фоном — берём --text-1 для
     read'ability, цвет автоматом подхватывается через `currentColor`. */
  color: var(--text-1, currentColor);
  opacity: 0.85;
  margin: 6px 0 2px;
}

/* Mobile card-list (replaces NDataTable). Tap = edit-view, long-press =
   bulk-mode + select. Скрытые записи (`row.hidden`) дополнительно
   притуплены чтобы соответствовать desktop'ому отображению. */
.tx-cards,
.tx-cards-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
/* Flex-разметка содержимого n-card. n-card сам красит фон/рамку/радиус
   через тему Naive (как в Forecast) — здесь только layout строки. */
.tx-row {
  display: flex;
  align-items: center;
  gap: 10px;
  -webkit-tap-highlight-color: transparent;
}
/* Hidden-записи: затемняем только содержимое, фон n-card остаётся
   непрозрачным — иначе во время swipe цветной action просвечивает. */
.tx-row.hidden > * {
  opacity: 0.45;
}
/* n-card транзишен radius'а правых углов + сглаживание в square при
   swipe (тот же приём, что в Forecast). */
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

/* Swipe-reveal action buttons под карточкой записи. Каждая ~60px,
   три помещаются в reveal-зоне SwipeableCard (180px по умолчанию). */
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
/* Naive UI палитра — Hide=info (синий, нейтральный toggle), Template=warning
   (оранжевый, привлекает внимание т.к. меняет форму), Delete=error. См.
   ExpensesView.vue для развёрнутой семантики. */
.swipe-action-info {
  background: #2080f0;
}
.swipe-action-warning {
  background: #f0a020;
}
.swipe-action-danger {
  background: #d03050;
}
.swipe-action-primary {
  /* Naive's primary green — matches the desktop ⋯-menu «Разделить» entry. */
  background: #18a058;
}
/* Group tint background — applied as a class on the row so Naive's
   per-cell background overrides don't strip it. Alpha kept low so the
   row colour reads as "this belongs to a group" without dominating the
   text. Palette + algorithm mirror utils/groupColor.js. */
/* Each group class sets an RGB triplet on the row; a shared rule paints the
   cells. Hover keeps the same tint (slightly stronger) instead of swapping to
   Naive's grey hover — that abrupt swap looked like a "blink". */
:deep(.n-data-table-tr.tx-grp-0) {
  --grp: 179, 157, 219;
}
:deep(.n-data-table-tr.tx-grp-1) {
  --grp: 255, 183, 77;
}
:deep(.n-data-table-tr.tx-grp-2) {
  --grp: 129, 199, 132;
}
:deep(.n-data-table-tr.tx-grp-3) {
  --grp: 79, 195, 247;
}
:deep(.n-data-table-tr.tx-grp-4) {
  --grp: 229, 115, 115;
}
:deep(.n-data-table-tr.tx-grp-5) {
  --grp: 255, 213, 79;
}
:deep(.n-data-table-tr.tx-grp-6) {
  --grp: 161, 136, 127;
}
:deep(.n-data-table-tr.tx-grp-7) {
  --grp: 240, 98, 146;
}
:deep(.n-data-table-tr.tx-grp-8) {
  --grp: 121, 134, 203;
}
:deep(.n-data-table-tr.tx-grp-9) {
  --grp: 174, 213, 129;
}
:deep(.n-data-table-tr.tx-grp-10) {
  --grp: 77, 208, 225;
}
:deep(.n-data-table-tr.tx-grp-11) {
  --grp: 255, 138, 101;
}
:deep(.n-data-table-tr[class*='tx-grp-'] > .n-data-table-td) {
  background: rgba(var(--grp), 0.13);
  transition: background-color 0.18s ease;
}
:deep(.n-data-table-tr[class*='tx-grp-']:hover > .n-data-table-td) {
  background: rgba(var(--grp), 0.24);
}

/* Parent-navigation focus ring — single 1px primary border around the whole
   row (no per-cell verticals); group tint background preserved. See
   ExpensesView for the edge-inset rationale. */
:deep(.n-data-table-tr.tx-row-focus > .n-data-table-td) {
  box-shadow:
    inset 0 1px 0 v-bind(primaryColor),
    inset 0 -1px 0 v-bind(primaryColor);
}
:deep(.n-data-table-tr.tx-row-focus > .n-data-table-td:first-child) {
  box-shadow:
    inset 0 1px 0 v-bind(primaryColor),
    inset 0 -1px 0 v-bind(primaryColor),
    inset 1px 0 0 v-bind(primaryColor);
}
:deep(.n-data-table-tr.tx-row-focus > .n-data-table-td:last-child) {
  box-shadow:
    inset 0 1px 0 v-bind(primaryColor),
    inset 0 -1px 0 v-bind(primaryColor),
    inset -1px 0 0 v-bind(primaryColor);
}

/* Mobile card focus — outer 1px primary ring around the opaque card. */
.tx-card-focus {
  border-radius: 8px;
  box-shadow: 0 0 0 1px v-bind(primaryColor);
}

.swipe-action-success {
  background: #18a058;
}

/* Deposit radio-button content (icon + label inline). */
.dep-radio-content {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  line-height: 1;
}

/* Initial balance rows — two scopes stacked, each row label + amount + del btn. */
.ib-rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ib-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ib-row-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  font-size: 13px;
  color: var(--text-3, currentColor);
  opacity: 0.85;
}

/* IB modal tabs: bump the tab-trigger height + give the content below the
   tab strip its own breathing room (the default tab pane sat flush against
   the input, no gap). The focused n-input-number's 2px primary border was
   still getting clipped because the n-tabs pane-wrapper has
   overflow: hidden for slide-animation purposes — we override that AND
   add horizontal margin/padding so the ring has 6px to breathe on each
   side without colliding with the modal edges. */
:deep(.ib-tabs .n-tabs-tab) {
  padding-top: 10px;
  padding-bottom: 10px;
}
.ib-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  line-height: 1;
}
:deep(.ib-tabs .n-tabs-pane-wrapper) {
  overflow: visible;
}
:deep(.ib-tabs .n-tab-pane) {
  /* Outer padding + a tiny inner margin on the form below provides the
     symmetric 6dp gutter the focus ring needs without nudging the input
     visually off-centre. */
  padding: 14px 6px 6px;
  overflow: visible;
}
.ib-tab-form {
  margin: 0 2px;
}

/* Bulk-mode marker (replaces author avatar on selected cards). */
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
