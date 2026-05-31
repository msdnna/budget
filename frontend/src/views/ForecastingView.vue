<template>
  <div>
    <!-- Mobile tabs (Аналитика / Регулярные расходы / Список желаний). На
         десктопе скрыты — там видны сразу все секции вертикальным потоком.
         Скрыты во время add-view (mobileFormShown=true) — там работает
         back-навигация через header карточки формы. -->
    <div v-if="isMobile && !mobileFormShown" class="forecast-tabs" :style="tabsCssVars">
      <button
        v-for="t in FORECAST_TABS"
        :key="t.key"
        type="button"
        class="forecast-tab"
        :class="{ active: activeTab === t.key }"
        @click="activeTab = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- ── Аналитика (summary + pie + add-form) ──────────────────────
         Wrapping div видим:
           • на десктопе всегда (нужны summary + pie + form);
           • на мобильном Analytics-табе (но без add-вида);
           • на мобильном add-вид'е (вне зависимости от таба) — чтобы
             вложенная форма-карточка отрендерилась. Внутри отдельные
             блоки прячут лишнее. -->
    <!-- `<Transition>` для mobile tab-fade при переключении Аналитика ↔
         Регулярные ↔ Желания (см. theme.css `.tab-fade-*`, @media gated). -->
    <Transition name="tab-fade">
      <div v-show="!isMobile || activeTab === 'analytics' || mobileFormShown">
        <!-- Forecast summary — 4 cards: total / 3-mo avg / regular / wishlist.
           The two contribution numbers come from the api 1.12.0 split:
           regular_contrib (recurring only) and wishlist_contrib − regular_contrib
           (one-off only). Mobile = 2×2 (span 2 of 4), desktop = 4-в-ряд.
           Скрыты во время add-вида чтобы пользователь сфокусировался на форме. -->
        <n-space
          v-show="!isMobile || !mobileFormShown"
          align="center"
          justify="flex-end"
          style="margin-bottom: 8px"
        >
          <n-text depth="3" style="font-size: 12px">Счёт</n-text>
          <n-select
            v-model:value="forecastDeposit"
            :options="forecastDepositOptions"
            size="small"
            style="width: 180px"
            to="body"
            @update:value="onForecastDepositChange"
          />
        </n-space>
        <n-grid
          v-show="!isMobile || !mobileFormShown"
          :cols="4"
          :x-gap="16"
          :y-gap="16"
          responsive="screen"
          :item-responsive="true"
          style="margin-bottom: 16px"
        >
          <n-grid-item span="2 m:1">
            <n-card>
              <n-statistic
                label="Прогноз на месяц"
                :value="Math.round(forecast.total_monthly)"
                :precision="0"
              >
                <template #suffix>₽</template>
              </n-statistic>
            </n-card>
          </n-grid-item>
          <n-grid-item span="2 m:1">
            <n-card>
              <n-statistic
                label="Среднее (3 мес.)"
                :value="Math.round(forecast.historical_avg)"
                :precision="0"
              >
                <template #suffix>₽</template>
              </n-statistic>
            </n-card>
          </n-grid-item>
          <n-grid-item span="2 m:1">
            <n-card>
              <n-statistic
                label="Регулярные расходы / мес"
                :value="Math.round(forecast.regular_contrib || 0)"
                :precision="0"
              >
                <template #suffix>₽</template>
              </n-statistic>
            </n-card>
          </n-grid-item>
          <n-grid-item span="2 m:1">
            <n-card>
              <n-statistic
                label="Список желаний / мес"
                :value="Math.round(wishlistOnlyContrib)"
                :precision="0"
              >
                <template #suffix>₽</template>
              </n-statistic>
            </n-card>
          </n-grid-item>
        </n-grid>

        <n-grid
          :cols="2"
          :x-gap="16"
          :y-gap="16"
          responsive="screen"
          :item-responsive="true"
          style="margin-bottom: 16px"
        >
          <n-grid-item v-if="!isMobile || !mobileFormShown" span="2 m:1">
            <n-card title="Прогноз по категориям">
              <n-spin :show="loadingForecast">
                <CategoryDonutChart
                  v-if="forecast.breakdown?.length"
                  :data="
                    forecast.breakdown.map((d) => ({ category: d.category, amount: d.amount }))
                  "
                  :category-meta="forecastCategoryMeta"
                  :palette="palette"
                  unit="percent"
                  hide-count
                />
                <n-empty
                  v-else
                  description="Добавьте транзакции или позиции в список желаний"
                  style="padding: 60px 0"
                />
              </n-spin>
            </n-card>
          </n-grid-item>

          <n-grid-item v-if="!isMobile || mobileFormShown" span="2 m:1">
            <n-card>
              <template #header>
                <div v-if="isMobile && mobileFormShown" class="card-back-header">
                  <n-button text size="small" @click="exitForecastForm">
                    <template #icon><n-icon :component="ArrowBackOutline" /></template>
                  </n-button>
                  <n-text strong style="font-size: 16px">
                    {{ mobileFormHeaderTitle }}
                  </n-text>
                </div>
                <span v-else>Добавить</span>
              </template>
              <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
                <!-- Type segmented selector decides whether the entry goes into
                 «Список желаний» (frequency=once) or «Регулярные расходы»
                 (monthly/quarterly/yearly). The frequency picker only
                 appears for the recurring branch. На мобильном add-вид'е
                 kind pre-set'ится по табе, переключатель скрыт. -->
                <n-form-item v-if="!isMobile" label="Тип" :show-feedback="false">
                  <n-radio-group v-model:value="form.kind" name="kind">
                    <n-radio-button value="wishlist">Желаемая покупка</n-radio-button>
                    <n-radio-button value="regular">Регулярный расход</n-radio-button>
                  </n-radio-group>
                </n-form-item>
                <n-grid :cols="2" :x-gap="12" :item-responsive="true" style="margin-top: 12px">
                  <n-grid-item span="2">
                    <n-form-item label="Название" path="name">
                      <!-- NAutoComplete тянет options из localStorage-кэша
                           (см. utils/inputHistory.js); regular + wishlist
                           делят одну форму и один history-ключ — частые
                           «Магнит» / «Интернет» удобны на обеих вкладках. -->
                      <n-auto-complete
                        v-model:value="form.name"
                        :options="nameHistoryOptions"
                        :get-show="() => true"
                        placeholder="Что хочу купить"
                      />
                    </n-form-item>
                  </n-grid-item>
                  <n-grid-item span="2 s:1">
                    <n-form-item label="Оценочная стоимость (₽)" path="estimated_cost">
                      <n-input-number
                        v-model:value="form.estimated_cost"
                        :min="1"
                        style="width: 100%"
                      />
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
                  <n-grid-item v-if="form.kind === 'regular'" span="2 s:1">
                    <n-form-item label="Частота">
                      <n-select
                        v-model:value="form.frequency"
                        :options="recurringFrequencyOptions"
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
                    <n-form-item label="Заметки">
                      <n-input v-model:value="form.notes" placeholder="Необязательно" />
                    </n-form-item>
                  </n-grid-item>
                </n-grid>
                <!-- Edit-режим на мобильном: Удалить + Сохранить в одну строку.
                   Дополнительная строка с Оплачено/Куплено + Отменить (если
                   уже оплачено в текущем периоде). На десктопе/в add-режиме —
                   один full-width submit-button. -->
                <template v-if="isMobile && mobileForecastEditing">
                  <div class="form-actions-row">
                    <n-popconfirm @positive-click="deleteEditingForecast">
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
                  <n-button
                    v-if="!isEditingPaidThisPeriod"
                    type="success"
                    block
                    style="margin-top: 8px"
                    :disabled="saving"
                    @click="payEditingForecast"
                  >
                    {{ form.kind === 'regular' ? 'Оплачено' : 'Куплено' }}
                  </n-button>
                  <n-button
                    v-else
                    block
                    style="margin-top: 8px"
                    :disabled="saving"
                    @click="unpurchaseEditingForecast"
                  >
                    {{ form.kind === 'regular' ? 'Отменить оплату' : 'Не куплено' }}
                  </n-button>
                </template>
                <n-button v-else type="primary" :loading="saving" block @click="submit">
                  {{ form.kind === 'regular' ? 'Добавить в регулярные' : 'Добавить в список' }}
                </n-button>
              </n-form>
            </n-card>
          </n-grid-item>
        </n-grid>
      </div>
    </Transition>
    <!-- /Аналитика -->

    <!-- Регулярные расходы + список желаний.
         Desktop (≥769px): SplitPane c drag-divider'ом + сохранение позиции в
         localStorage. Mobile (≤768px): SplitPane сам сворачивается в колонку,
         плюс v-if-гарды на содержимом слотов оставляют видимым только
         содержимое активной табы. -->
    <SplitPane
      storage-key="forecast-split"
      :default-left="50"
      :min-left="30"
      :max-left="70"
      :stack-below="1280"
      class="forecast-split"
    >
      <template #left>
        <Transition name="tab-fade">
          <div
            v-if="!isMobile || (activeTab === 'regular' && !mobileFormShown)"
            ref="regularPaneRef"
          >
            <!-- Outer card with section title + bulk-edit toggle in the header.
             Inner sub-cards render each item with consistent columns. -->
            <n-card>
              <template #header>
                <n-space align="center" justify="space-between" style="width: 100%">
                  <n-text strong>Регулярные расходы</n-text>
                  <n-space align="center" :size="8">
                    <template v-if="!regularBulkMode">
                      <!-- Desktop: bulk-toggle. Mobile: bulk через long-press,
                       добавление через FAB снизу-справа. -->
                      <n-button
                        v-if="!isMobile"
                        size="small"
                        :disabled="!forecast.regular_items?.length"
                        @click="enterRegularBulkMode"
                      >
                        Пакетное редактирование
                      </n-button>
                    </template>
                    <template v-else>
                      <n-text v-if="regularSelectedIds.size" depth="2" style="font-size: 12px">
                        Выбрано: {{ regularSelectedIds.size }}
                      </n-text>
                      <!-- Inline-кнопки только на десктопе; на мобильном
                         те же действия дублируются в `<BulkFabRow>`. -->
                      <template v-if="!isMobile && regularSelectedIds.size">
                        <ConfirmActionButton
                          v-if="selectedRegularPaidIds.length > 0"
                          label="Отменить"
                          type="default"
                          :loading="regularBulkBusy"
                          @confirm="bulkCancelRegular"
                        />
                        <ConfirmActionButton
                          label="Удалить"
                          type="error"
                          :loading="regularBulkBusy"
                          @confirm="bulkDeleteRegular"
                        />
                      </template>
                      <n-button
                        v-if="!isMobile"
                        size="small"
                        quaternary
                        @click="exitRegularBulkMode"
                      >
                        Отмена
                      </n-button>
                    </template>
                  </n-space>
                </n-space>
              </template>
              <n-spin :show="loadingForecast">
                <n-empty
                  v-if="!forecast.regular_items?.length"
                  description="Нет регулярных позиций"
                  style="padding: 30px 0"
                />
                <template v-else>
                  <!-- Mobile: список карточек (SwipeableCard). На десктопе ниже
                   — NDataTable, мирорит Income/Expense. `<TransitionGroup>`
                   для collapse-leave анимации (см. theme.css `.tx-list-*`). -->
                  <TransitionGroup v-if="isMobile" name="tx-list" tag="div" class="tx-cards-list">
                    <SwipeableCard
                      v-for="item in forecast.regular_items"
                      :key="item.id"
                      :data-focus-id="item.id"
                      :class="{ 'fc-card-focus': focusedId === item.id }"
                      :long-press-ms="regularBulkMode ? 0 : 1000"
                      :reveal-width="180"
                      :radius="3"
                      @tap="onForecastTap('regular', item)"
                      @longpress="onForecastBulkLongPress('regular', item.id)"
                    >
                      <template #actions>
                        <button
                          v-if="!item.paid_this_period"
                          class="swipe-action swipe-action-success"
                          title="Оплачено"
                          @click="openPayRegular(item)"
                        >
                          <n-icon :component="CheckmarkOutline" :size="20" />
                          <span class="swipe-action-label">Оплачено</span>
                        </button>
                        <button
                          v-else
                          class="swipe-action swipe-action-warning"
                          title="Отменить оплату"
                          @click="cancelRegularPaid(item)"
                        >
                          <n-icon :component="CloseOutline" :size="20" />
                          <span class="swipe-action-label">Отменить</span>
                        </button>
                        <button
                          class="swipe-action swipe-action-info"
                          title="Привязать существующий"
                          @click="openLinkExisting(item)"
                        >
                          <n-icon :component="LinkOutline" :size="20" />
                          <span class="swipe-action-label">Привязать</span>
                        </button>
                        <button
                          class="swipe-action swipe-action-danger"
                          title="Удалить"
                          @click="confirmDeleteForecast(item)"
                        >
                          <n-icon :component="TrashOutline" :size="20" />
                          <span class="swipe-action-label">Удалить</span>
                        </button>
                      </template>
                      <n-card
                        size="small"
                        :bordered="true"
                        embedded
                        :style="
                          regularBulkMode && regularSelectedIds.has(item.id)
                            ? `background:${primaryColor}1f`
                            : ''
                        "
                      >
                        <div class="tx-mobile-row" :class="{ paid: item.paid_this_period }">
                          <!-- В Регулярных аватара автора нет (как в Android-клиенте);
                       левый слот появляется только в bulk-режиме с checkbox'ом.
                       `<Transition name="bulk-icon">` даёт fade-in при входе в
                       bulk-mode и fade-out при выходе (см. theme.css). -->
                          <Transition name="bulk-icon">
                            <div v-if="regularBulkMode" class="tx-card-left">
                              <div
                                class="bulk-circle"
                                :class="{ checked: regularSelectedIds.has(item.id) }"
                              >
                                <svg
                                  v-if="regularSelectedIds.has(item.id)"
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
                            </div>
                          </Transition>
                          <div class="tx-card-body">
                            <div class="tx-card-row1">
                              <span class="tx-card-category">{{ item.name }}</span>
                              <n-tag size="tiny" :bordered="false" type="info" round>
                                {{ freqLabel(item.frequency) }}
                              </n-tag>
                              <n-tag
                                v-if="item.paid_this_period"
                                size="tiny"
                                :bordered="false"
                                type="success"
                                round
                              >
                                оплачено
                              </n-tag>
                            </div>
                            <div class="tx-card-desc">
                              <template v-if="item.category">
                                <CategoryLabel
                                  :name="item.category"
                                  :category="catStore.findAcrossSections(item.category)"
                                  :size="12"
                                />
                                <template v-if="item.notes">· {{ item.notes }}</template>
                              </template>
                              <template v-else>
                                {{ item.notes || 'без категории' }}
                              </template>
                              <template v-if="item.next_due_date">
                                · след. оплата {{ formatDueDate(item.next_due_date) }}
                              </template>
                            </div>
                          </div>
                          <div class="tx-card-amount" :style="{ color: palette.expense }">
                            {{ Math.round(item.estimated_cost).toLocaleString('ru-RU') }}
                            {{ freqUnit(item.frequency) }}
                          </div>
                        </div>
                      </n-card>
                    </SwipeableCard>
                  </TransitionGroup>
                  <!-- Desktop: NDataTable c инлайн-pencil'ами по ячейкам, как
                   в Income/Expense. Мобильный путь идёт через SwipeableCard
                   выше. -->
                  <n-data-table
                    v-else
                    size="small"
                    :columns="regularColumns"
                    :data="forecast.regular_items"
                    :pagination="false"
                    :row-props="getRegularRowProps"
                    :row-class-name="getRegularRowClass"
                  />
                </template>
              </n-spin>
            </n-card>
          </div>
        </Transition>
      </template>

      <template #right>
        <!-- Wishlist table -->
        <Transition name="tab-fade">
          <div
            v-if="!isMobile || (activeTab === 'wishlist' && !mobileFormShown)"
            ref="wishlistPaneRef"
          >
            <n-card>
              <template #header>
                <n-space align="center" justify="space-between" style="width: 100%">
                  <n-text strong>Список желаний</n-text>
                  <n-space align="center" :size="8">
                    <template v-if="!bulkMode">
                      <n-button
                        v-if="!isMobile"
                        size="small"
                        :disabled="!wishlistOnly.length"
                        @click="enterBulkMode"
                      >
                        Пакетное редактирование
                      </n-button>
                    </template>
                    <template v-else>
                      <n-text v-if="selectedIds.size" depth="2" style="font-size: 12px">
                        Выбрано: {{ selectedIds.size }}
                      </n-text>
                      <!-- Inline-кнопки только на десктопе. На мобильном
                         те же действия дублируются в `<BulkFabRow>`. -->
                      <template v-if="!isMobile && selectedIds.size">
                        <!-- Only the unlink direction is allowed in bulk. Marking
                         items as bought needs the prefilled-expense modal
                         flow (amount/date/user/category) which doesn't fit
                         a single bulk button — keep that on per-row level. -->
                        <ConfirmActionButton
                          v-if="purchasedSelectedCount > 0"
                          label="Не куплено"
                          type="default"
                          :loading="bulkBusy"
                          @confirm="bulkUnpurchase"
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
              </template>
              <n-spin :show="wlStore.loading">
                <n-empty
                  v-if="!wishlistOnly.length"
                  description="Список пуст"
                  style="padding: 40px 0"
                />
                <template v-else>
                  <!-- Mobile: SwipeableCard список с TransitionGroup для
                     collapse-leave анимации (см. theme.css `.tx-list-*`).
                     Desktop: NDataTable ниже. -->
                  <TransitionGroup v-if="isMobile" name="tx-list" tag="div" class="tx-cards-list">
                    <SwipeableCard
                      v-for="item in wishlistOnly"
                      :key="item.id"
                      :data-focus-id="item.id"
                      :class="{ 'fc-card-focus': focusedId === item.id }"
                      :long-press-ms="bulkMode ? 0 : 1000"
                      :reveal-width="item.purchased ? 120 : 180"
                      :radius="3"
                      @tap="onForecastTap('wishlist', item)"
                      @longpress="onForecastBulkLongPress('wishlist', item.id)"
                    >
                      <template #actions>
                        <button
                          v-if="!item.purchased"
                          class="swipe-action swipe-action-success"
                          title="Куплено"
                          @click="openPayWishlist(item)"
                        >
                          <n-icon :component="CheckmarkOutline" :size="20" />
                          <span class="swipe-action-label">Куплено</span>
                        </button>
                        <button
                          v-else
                          class="swipe-action swipe-action-warning"
                          title="Не куплено"
                          @click="unpurchaseWishlist(item)"
                        >
                          <n-icon :component="CloseOutline" :size="20" />
                          <span class="swipe-action-label">Не куплено</span>
                        </button>
                        <button
                          v-if="!item.purchased"
                          class="swipe-action swipe-action-info"
                          title="Привязать существующий"
                          @click="openLinkExisting(item)"
                        >
                          <n-icon :component="LinkOutline" :size="20" />
                          <span class="swipe-action-label">Привязать</span>
                        </button>
                        <button
                          class="swipe-action swipe-action-danger"
                          title="Удалить"
                          @click="confirmDeleteForecast(item)"
                        >
                          <n-icon :component="TrashOutline" :size="20" />
                          <span class="swipe-action-label">Удалить</span>
                        </button>
                      </template>
                      <n-card
                        size="small"
                        :bordered="true"
                        embedded
                        :style="
                          bulkMode && selectedIds.has(item.id) ? `background:${primaryColor}1f` : ''
                        "
                      >
                        <div class="tx-mobile-row" :class="{ paid: item.purchased }">
                          <div class="tx-card-left">
                            <!-- Avatar ↔ bulk-circle fade-swap; см. theme.css
                               `.bulk-icon-*`. -->
                            <Transition name="bulk-icon" mode="out-in">
                              <div
                                v-if="bulkMode"
                                key="bulk"
                                class="bulk-circle"
                                :class="{ checked: selectedIds.has(item.id) }"
                              >
                                <svg
                                  v-if="selectedIds.has(item.id)"
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
                              <!-- Аватар автора записи (создателя). На регулярных
                                 расходах не показываем, у желаний — да: «кто
                                 хочет купить» — полезный контекст. -->
                              <UserAvatar
                                v-else
                                key="avatar"
                                :display-name="item.created_by?.display_name || ''"
                                :avatar-url="item.created_by?.avatar_url || ''"
                                :size="32"
                              />
                            </Transition>
                          </div>
                          <div class="tx-card-body">
                            <div class="tx-card-row1">
                              <span class="tx-card-category">{{ item.name }}</span>
                              <n-tag
                                v-if="item.purchased"
                                size="tiny"
                                :bordered="false"
                                type="success"
                                round
                              >
                                куплено
                              </n-tag>
                            </div>
                            <div class="tx-card-desc">
                              <template v-if="item.category">
                                <CategoryLabel
                                  :name="item.category"
                                  :category="catStore.findAcrossSections(item.category)"
                                  :size="12"
                                />
                                <template v-if="item.notes">· {{ item.notes }}</template>
                              </template>
                              <template v-else>
                                {{ item.notes || 'без категории' }}
                              </template>
                            </div>
                          </div>
                          <div class="tx-card-amount" :style="{ color: palette.expense }">
                            {{ Math.round(item.estimated_cost).toLocaleString('ru-RU') }} ₽
                          </div>
                        </div>
                      </n-card>
                    </SwipeableCard>
                  </TransitionGroup>
                  <!-- Desktop: NDataTable c инлайн-pencil'ами + reassign user
                   + Куплено/Не куплено/Удалить кнопки в actions. -->
                  <n-data-table
                    v-else
                    size="small"
                    :columns="wishlistColumns"
                    :data="wishlistOnly"
                    :pagination="false"
                    :row-props="getWishlistRowProps"
                    :row-class-name="getWishlistRowClass"
                  />
                </template>
              </n-spin>
            </n-card>
          </div>
        </Transition>
      </template>
    </SplitPane>

    <!-- Prefilled expense modal — single shared modal for "Оплачено"
         (regular расход) and "Куплено" (wishlist purchase). Title swaps
         based on payKind. Values copy verbatim from the source item. -->
    <n-modal
      v-model:show="showPay"
      preset="card"
      :title="payKind === 'wishlist' ? 'Зафиксировать покупку' : 'Зафиксировать оплату'"
      style="max-width: 460px"
    >
      <template v-if="payItem">
        <n-form label-placement="top">
          <n-form-item label="Сумма (₽)">
            <n-input-number v-model:value="payForm.amount" :min="1" style="width: 100%" />
          </n-form-item>
          <n-form-item label="Дата">
            <n-date-picker
              v-model:formatted-value="payForm.date"
              value-format="yyyy-MM-dd"
              type="date"
              style="width: 100%"
            />
          </n-form-item>
          <n-form-item label="Категория">
            <n-select
              v-model:value="payForm.category"
              :options="expenseCategoryOptions"
              filterable
              tag
              :on-create="handleCategoryCreate"
              :render-label="renderCategoryLabel"
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
          <n-button @click="showPay = false">Отмена</n-button>
          <n-button
            type="primary"
            :loading="payingBusy"
            :disabled="!payForm.amount || !payForm.category"
            @click="confirmPay"
          >
            Сохранить
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- Привязка существующего расхода к wishlist/regular-итему. -->
    <LinkExistingExpenseModal
      v-model:show="showLinkExisting"
      :item="linkExistingItem"
      @linked="onLinkExistingDone"
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

    <!-- Mobile FAB: добавить запись текущего таба. На «Аналитике» скрыт
         (там нечего добавлять — только summary + pie). В bulk-mode FAB
         подменяется на ряд action-FAB'ов (mirror Income/Expenses).
         Crossfade при swap'е через `<Transition mode="out-in">` —
         см. theme.css `.fab-swap-*`. -->
    <Transition name="fab-swap" mode="out-in">
      <FabButton
        v-if="
          isMobile &&
          !mobileFormShown &&
          (activeTab === 'regular' || activeTab === 'wishlist') &&
          !(activeTab === 'regular' && regularBulkMode) &&
          !(activeTab === 'wishlist' && bulkMode)
        "
        key="add"
        :title="activeTab === 'regular' ? 'Добавить регулярный расход' : 'Добавить желание'"
        @click="enterForecastAdd(activeTab)"
      />
      <BulkFabRow
        v-else-if="isMobile && !mobileFormShown && activeTab === 'regular' && regularBulkMode"
        key="bulk-regular"
        :actions="mobileRegularBulkActions"
      />
      <BulkFabRow
        v-else-if="isMobile && !mobileFormShown && activeTab === 'wishlist' && bulkMode"
        key="bulk-wishlist"
        :actions="mobileWishlistBulkActions"
      />
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, h, watch } from 'vue'
import { useMessage } from 'naive-ui'
import {
  NCard,
  NGrid,
  NGridItem,
  NStatistic,
  NSpin,
  NEmpty,
  NList,
  NListItem,
  NText,
  NTag,
  NSpace,
  NButton,
  NPopconfirm,
  NForm,
  NFormItem,
  NAutoComplete,
  NInput,
  NInputNumber,
  NSelect,
  NModal,
  NTooltip,
  NDatePicker,
  NRadioGroup,
  NRadioButton,
  NIcon,
  NDataTable,
  NPopover,
} from 'naive-ui'
import {
  ArrowBackOutline,
  CheckmarkOutline,
  CloseOutline,
  EllipsisHorizontalOutline,
  LinkOutline,
  RefreshOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import { useWishlistStore } from '@/stores/wishlist'
import { useCategoriesStore } from '@/stores/categories'
import { statistics, users as usersApi, wishlist as wlApi, transactions as txApi } from '@/api'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import CategoryDonutChart from '@/components/CategoryDonutChart.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'
import FabButton from '@/components/FabButton.vue'
import SwipeableCard from '@/components/SwipeableCard.vue'
import BulkFabRow from '@/components/BulkFabRow.vue'
import SplitPane from '@/components/SplitPane.vue'
import CategoryLabel from '@/components/CategoryLabel.vue'
import LinkExistingExpenseModal from '@/components/LinkExistingExpenseModal.vue'
import { DEPOSITS, DEPOSIT_DEFAULT, normalizeDeposit } from '@/utils/deposit'
import { historyOptions, pushHistory } from '@/utils/inputHistory'

const themeStore = useThemeStore()
const { primaryColor, onPrimaryColor, palette } = storeToRefs(themeStore)
// Soft primary fill for the link-focus row (8-digit hex → ~12% alpha). Bound
// into scoped CSS via v-bind so it tracks theme changes.
const focusBg = computed(() => `${primaryColor.value}1f`)

// ── Mobile tabs (Аналитика / Регулярные / Желания) ───────────────
// На десктопе все 3 секции видны в общем потоке — табы лишь для мобильных.
const FORECAST_TABS = [
  { key: 'analytics', label: 'Аналитика' },
  { key: 'regular', label: 'Регулярные' },
  { key: 'wishlist', label: 'Желания' },
]
const activeTab = ref('analytics')
const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
const isMobile = computed(() => windowWidth.value < 768)
function onWinResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

// ── Per-table responsive thresholds ──────────────────────────────────────────
// Замеряем ширину обёртки каждой секции (n-card + содержимое) и переключаем
// колонки:
//   • compact (≤620px): action-кнопки сворачиваются в «•••» popover.
//   • veryCompact (≤480px): pencil-affordance в inline-edit скрыты — таблица
//     становится read-only без потери основных колонок.
// Replicate'ятся независимо для regular и wishlist, потому что в SplitPane
// можно перетянуть divider в любую сторону.
const regularPaneRef = ref(null)
const wishlistPaneRef = ref(null)
const regularPaneWidth = ref(0)
const wishlistPaneWidth = ref(0)

// Единый порог: ниже него все компактные оптимизации срабатывают одновременно
// (actions → popover «•••», pencils скрыты, Категория/Заметки получают
// ellipsis + tooltip, «След. оплата» скрывается). Раньше три отдельных порога
// создавали «застрявшее» состояние, где actions уже уходили за горизонтальный
// скролл, а след.оплата ещё не пряталась.
const COMPACT_TH = 740

const regularCompact = computed(
  () => regularPaneWidth.value > 0 && regularPaneWidth.value < COMPACT_TH,
)
const wishlistCompact = computed(
  () => wishlistPaneWidth.value > 0 && wishlistPaneWidth.value < COMPACT_TH,
)
// Алиасы — render-функции читаются осмысленнее, когда `hidePencils` vs
// `compactActions` vs `ultraCompact` обращаются к (концептуально) разным
// флагам. По факту все триггерятся одним порогом.
const regularCompactActions = regularCompact
const regularHidePencils = regularCompact
const regularUltraCompact = regularCompact
const wishlistCompactActions = wishlistCompact
const wishlistHidePencils = wishlistCompact

let regularRO = null
let wishlistRO = null
onMounted(() => {
  if (regularPaneRef.value) {
    regularPaneWidth.value = regularPaneRef.value.offsetWidth
    regularRO = new ResizeObserver(([e]) => {
      regularPaneWidth.value = e.contentRect.width
    })
    regularRO.observe(regularPaneRef.value)
  }
  if (wishlistPaneRef.value) {
    wishlistPaneWidth.value = wishlistPaneRef.value.offsetWidth
    wishlistRO = new ResizeObserver(([e]) => {
      wishlistPaneWidth.value = e.contentRect.width
    })
    wishlistRO.observe(wishlistPaneRef.value)
  }
})
onUnmounted(() => {
  regularRO?.disconnect()
  wishlistRO?.disconnect()
})

// Mobile add/edit-навигация. `mobileFormShown` поднимает форму поверх
// списка/summary/tab'ов; `mobileForecastEditing` хранит редактируемый item
// (null = режим добавления). Submit ветвится по этому полю.
const mobileFormShown = ref(false)
const mobileForecastEditing = ref(null)

function enterForecastAdd(kind) {
  mobileForecastEditing.value = null
  form.value = {
    kind,
    name: '',
    estimated_cost: null,
    category: '',
    frequency: kind === 'regular' ? 'monthly' : 'monthly',
    deposit: DEPOSIT_DEFAULT,
    notes: '',
  }
  mobileFormShown.value = true
}

function enterForecastEdit(item) {
  mobileForecastEditing.value = item
  form.value = {
    kind: item.frequency && item.frequency !== 'once' ? 'regular' : 'wishlist',
    name: item.name || '',
    estimated_cost: item.estimated_cost ?? null,
    category: item.category || '',
    frequency: item.frequency && item.frequency !== 'once' ? item.frequency : 'monthly',
    deposit: normalizeDeposit(item.deposit),
    notes: item.notes || '',
  }
  mobileFormShown.value = true
}

function exitForecastForm() {
  mobileFormShown.value = false
  mobileForecastEditing.value = null
}

// Уже оплачено/куплено в текущем периоде? Управляет тем, какая action-кнопка
// (Оплачено vs Отменить оплату) висит в edit-footer'е мобильного вида.
const isEditingPaidThisPeriod = computed(() => {
  const it = mobileForecastEditing.value
  if (!it) return false
  return form.value.kind === 'regular' ? !!it.paid_this_period : !!it.purchased
})

const mobileFormHeaderTitle = computed(() => {
  if (mobileForecastEditing.value) {
    return form.value.kind === 'regular' ? 'Регулярный расход' : 'Желаемая покупка'
  }
  return form.value.kind === 'regular' ? 'Новый регулярный расход' : 'Новая желаемая покупка'
})

// Long-press / tap для мобильных карточек — события эмитит SwipeableCard.
function onForecastBulkLongPress(kind, id) {
  if (kind === 'regular') {
    if (!regularBulkMode.value) regularBulkMode.value = true
    toggleRegularSelect(id)
  } else {
    if (!bulkMode.value) bulkMode.value = true
    toggleSelect(id)
  }
}

// Tap: в bulk-режиме toggle-select, иначе открыть edit. Без этой проверки
// тап по второй карточке в bulk-режиме открывал edit вместо добавления
// её в выбор.
function onForecastTap(kind, item) {
  // Ignore the ghost click that lands right after a link-nav focus (see
  // tapSuppressUntil) — otherwise it immediately opens the focused card's
  // edit sheet, which reads as a stray "double click".
  if (Date.now() < tapSuppressUntil) return
  if (kind === 'regular') {
    if (regularBulkMode.value) {
      toggleRegularSelect(item.id)
      return
    }
  } else if (bulkMode.value) {
    toggleSelect(item.id)
    return
  }
  enterForecastEdit(item)
}

function confirmDeleteForecast(item) {
  if (!window.confirm('Удалить позицию?')) return
  wlStore.remove(item.id).then(loadForecast)
}

const tabsCssVars = computed(() => ({
  '--st-surface': palette.value.surface,
  '--st-border': palette.value.border,
  '--st-text2': palette.value.text2,
  '--st-primary': primaryColor.value,
  '--st-on-primary': onPrimaryColor.value,
}))

const wlStore = useWishlistStore()
const message = useMessage()
const formRef = ref(null)
const saving = ref(false)
const loadingForecast = ref(false)

const forecast = ref({
  total_monthly: 0,
  historical_avg: 0,
  wishlist_contrib: 0,
  breakdown: [],
  regular_items: [],
  unpurchased_wishlist: [],
})

// `kind` selects the destination section: 'wishlist' = one-off purchase,
// 'regular' = recurring expense. The backend still stores both as wishlist
// rows distinguished only by `frequency` — `kind` is purely a UI affordance.
const form = ref({
  kind: 'wishlist',
  name: '',
  estimated_cost: null,
  category: '',
  frequency: 'monthly',
  deposit: DEPOSIT_DEFAULT,
  notes: '',
})

// LocalStorage-кэш недавно введённых «Названий» для NAutoComplete.
// Regular + wishlist делят одну форму и один history-ключ (общий for-name
// для «что хочу купить / какой регулярный расход»).
const nameHistoryOptions = ref(historyOptions('forecast-name'))
function refreshNameHistory() {
  nameHistoryOptions.value = historyOptions('forecast-name')
}

// Wishlist list excludes recurring items — those live in «Регулярные расходы».
const forecastDeposit = ref('')
const forecastDepositOptions = [
  { label: 'Все счета', value: '' },
  ...DEPOSITS.map((d) => ({ label: d.label, value: d.value })),
]

const wishlistOnly = computed(() => {
  let items = wlStore.items.filter((it) => !it.frequency || it.frequency === 'once')
  // The deposit filter is set by the Прогноз scope-chip row. We filter
  // client-side because wlStore mirrors Room/sync state and isn't aware of
  // the screen-level filter — the server-side `forecast.regular_items` slice
  // already comes pre-filtered, so we only have to worry about wishlist.
  if (forecastDeposit.value) {
    const dep = forecastDeposit.value
    items = items.filter((it) => (it.deposit || 'bank') === dep)
  }
  return items
})

// ── Inline editing — per-field pencil icons ─────────────────────────────────
//
// Mirrors the wishlist pattern but generalised to four fields. Only one
// row+field can be in edit mode at a time. `editingField` is one of:
// 'name' | 'cost' | 'category' | 'notes'.
const editingId = ref(null)
const editingField = ref(null)
const editValue = ref(null)

function isEditing(id, field) {
  return editingId.value === id && editingField.value === field
}

function startEdit(item, field) {
  editingId.value = item.id
  editingField.value = field
  editValue.value =
    field === 'cost'
      ? item.estimated_cost
      : field === 'name'
        ? item.name || ''
        : field === 'category'
          ? item.category || ''
          : field === 'notes'
            ? item.notes || ''
            : null
}

function cancelEdit() {
  editingId.value = null
  editingField.value = null
  editValue.value = null
}

async function confirmEdit(item) {
  const field = editingField.value
  if (!field) return
  const apiKey = field === 'cost' ? 'estimated_cost' : field
  const value = editValue.value
  // Block obviously-bad values: empty cost / empty name. Category/notes can
  // be cleared deliberately.
  if (field === 'cost' && (!value || value <= 0)) {
    cancelEdit()
    return
  }
  if (field === 'name' && !String(value).trim()) {
    cancelEdit()
    return
  }
  try {
    await wlStore.update(item.id, { [apiKey]: value })
    await loadForecast()
    message.success('Сохранено')
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
  if (s.has(id)) s.delete(id)
  else s.add(id)
  selectedIds.value = s
}

// Selected items that are currently marked as purchased — these are the
// only ones the bulk «Не куплено» action operates on.
const purchasedSelected = computed(() =>
  wlStore.items.filter(
    (it) =>
      selectedIds.value.has(it.id) && (!it.frequency || it.frequency === 'once') && it.purchased,
  ),
)
const purchasedSelectedCount = computed(() => purchasedSelected.value.length)

// Unlink each selected purchased wishlist item from its expense and reset
// `purchased` — same flow as the single-row «Не куплено» button so server-
// side detail-request bookkeeping stays in sync.
async function bulkUnpurchase() {
  const list = purchasedSelected.value
  if (!list.length) return
  bulkBusy.value = true
  try {
    await Promise.all(
      list.map(async (it) => {
        await wlApi.unlinkPeriod(it.id)
        return wlStore.update(it.id, { purchased: false })
      }),
    )
    exitBulkMode()
    await Promise.all([wlStore.fetch(), loadForecast()])
    message.success('Записи отвязаны')
  } catch (e) {
    message.error(e.message)
  } finally {
    bulkBusy.value = false
  }
}

async function bulkDelete() {
  bulkBusy.value = true
  try {
    await Promise.all(Array.from(selectedIds.value).map((id) => wlApi.remove(id)))
    exitBulkMode()
    await Promise.all([wlStore.fetch(), loadForecast()])
    message.success('Записи удалены')
  } catch (e) {
    message.error(e.message)
  } finally {
    bulkBusy.value = false
  }
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

// Single modal serves both flows: «Оплачено» on a recurring item AND
// «Куплено» on a wishlist item. They are functionally identical (create
// expense linked via wishlist_id) — `payKind` only changes the modal title
// and whether we additionally flip the wishlist item's `purchased` flag
// after the transaction lands.
const showPay = ref(false)
const payItem = ref(null) // wishlist row OR forecast.regular_items entry
const payKind = ref('regular') // 'regular' | 'wishlist'
const payingBusy = ref(false)
const cancelingId = ref(null)
const payForm = ref({ amount: null, date: '', category: '', purpose: '', description: '' })

// Bulk mode for «Регулярные расходы». Mirrors wishlist's bulkMode/selectedIds
// but lives in a separate slot so toggling one doesn't bleed into the other.
const regularBulkMode = ref(false)
const regularSelectedIds = ref(new Set())
const regularBulkBusy = ref(false)

function enterRegularBulkMode() {
  regularBulkMode.value = true
  regularSelectedIds.value = new Set()
  cancelEdit()
}
function exitRegularBulkMode() {
  regularBulkMode.value = false
  regularSelectedIds.value = new Set()
}
function toggleRegularSelect(id) {
  const s = new Set(regularSelectedIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  regularSelectedIds.value = s
}

const selectedRegularPaidIds = computed(() => {
  const ids = regularSelectedIds.value
  return (forecast.value.regular_items || [])
    .filter((it) => ids.has(it.id) && it.paid_this_period)
    .map((it) => it.id)
})

async function bulkCancelRegular() {
  const ids = selectedRegularPaidIds.value
  if (!ids.length) return
  regularBulkBusy.value = true
  try {
    await Promise.all(ids.map((id) => wlApi.unlinkPeriod(id)))
    exitRegularBulkMode()
    await loadForecast()
    message.success('Привязки сняты')
  } catch (e) {
    message.error(e.message)
  } finally {
    regularBulkBusy.value = false
  }
}
async function bulkDeleteRegular() {
  const ids = Array.from(regularSelectedIds.value)
  if (!ids.length) return
  regularBulkBusy.value = true
  try {
    await Promise.all(ids.map((id) => wlApi.remove(id)))
    exitRegularBulkMode()
    await Promise.all([wlStore.fetch(), loadForecast()])
    message.success('Записи удалены')
  } catch (e) {
    message.error(e.message)
  } finally {
    regularBulkBusy.value = false
  }
}

// Mobile-only bulk action FAB-rows (см. BulkFabRow.vue). Wishlist и
// regular ведут разные стейты — два отдельных action-набора.
const mobileWishlistBulkActions = computed(() => {
  if (!selectedIds.value.size) {
    return [
      { icon: CloseOutline, title: 'Отмена выбора', variant: 'default', onClick: exitBulkMode },
    ]
  }
  const actions = []
  if (purchasedSelectedCount.value > 0) {
    actions.push({
      icon: RefreshOutline,
      title: 'Не куплено',
      variant: 'primary',
      loading: bulkBusy.value,
      onClick: bulkUnpurchase,
    })
  }
  actions.push(
    {
      icon: TrashOutline,
      title: 'Удалить выбранные',
      variant: 'danger',
      confirm: true,
      loading: bulkBusy.value,
      onClick: bulkDelete,
    },
    { icon: CloseOutline, title: 'Отмена выбора', variant: 'default', onClick: exitBulkMode },
  )
  return actions
})

const mobileRegularBulkActions = computed(() => {
  if (!regularSelectedIds.value.size) {
    return [
      {
        icon: CloseOutline,
        title: 'Отмена выбора',
        variant: 'default',
        onClick: exitRegularBulkMode,
      },
    ]
  }
  const actions = []
  if (selectedRegularPaidIds.value.length > 0) {
    actions.push({
      icon: RefreshOutline,
      title: 'Отменить оплату',
      variant: 'primary',
      loading: regularBulkBusy.value,
      onClick: bulkCancelRegular,
    })
  }
  actions.push(
    {
      icon: TrashOutline,
      title: 'Удалить выбранные',
      variant: 'danger',
      confirm: true,
      loading: regularBulkBusy.value,
      onClick: bulkDeleteRegular,
    },
    {
      icon: CloseOutline,
      title: 'Отмена выбора',
      variant: 'default',
      onClick: exitRegularBulkMode,
    },
  )
  return actions
})

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
  payItem.value = item
  payKind.value = 'regular'
  payForm.value = {
    // Prefill with the full bill amount (estimated_cost), not monthly_cost —
    // for quarterly/yearly items the user pays the full bill once per period.
    amount: Math.round(item.estimated_cost || item.monthly_cost) || null,
    date: todayStr(),
    category: item.category || '',
    // Точное копирование: name → «Назначение», notes → «Описание».
    purpose: item.name || '',
    description: item.notes || '',
  }
  showPay.value = true
}

function openPayWishlist(item) {
  payItem.value = item
  payKind.value = 'wishlist'
  payForm.value = {
    amount: Math.round(item.estimated_cost) || null,
    date: todayStr(),
    category: item.category || '',
    purpose: item.name || '',
    description: item.notes || '',
  }
  showPay.value = true
}

async function confirmPay() {
  if (!payItem.value || !payForm.value.amount || !payForm.value.category) return
  payingBusy.value = true
  try {
    const cat = payForm.value.category
    if (cat && !catStore.bySection.expense?.find((c) => c.name === cat)) {
      await catStore.add('expense', cat).catch(() => {})
    }
    await txApi.create({
      type: 'expense',
      amount: payForm.value.amount,
      date: payForm.value.date,
      category: cat,
      purpose: payForm.value.purpose || '',
      description: payForm.value.description || '',
      wishlist_id: payItem.value.id,
    })
    catStore.recordUse('expense', cat)
    // For wishlist items the «Куплено» action also flips `purchased` —
    // recurring items don't have that concept. Doing it after the tx
    // lands keeps the two-step flow atomic enough for the UI.
    if (payKind.value === 'wishlist') {
      await wlStore.update(payItem.value.id, { purchased: true })
    }
    showPay.value = false
    await loadForecast()
    message.success(payKind.value === 'wishlist' ? 'Покупка зафиксирована' : 'Оплата зафиксирована')
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

// ── «Привязать существующий расход» ─────────────────────────────────────────
//
// Opens a modal listing unlinked expense transactions; on confirm the chosen
// tx is attached via wishlist_id, its category is aligned with the wishlist
// item, and (for `once` items) `purchased` is flipped to true server-side.
const showLinkExisting = ref(false)
const linkExistingItem = ref(null)
function openLinkExisting(item) {
  linkExistingItem.value = item
  showLinkExisting.value = true
}
async function onLinkExistingDone() {
  // The server may have cloned a wishlist category into expense; refresh
  // categories store so pie/legend pick up the new metadata immediately.
  await Promise.all([catStore.load('expense'), wlStore.fetch(), loadForecast()])
}

// Wishlist «Не куплено»: clear the linked transaction and reset the
// purchased flag — keeps the original transaction in the расходы list.
async function unpurchaseWishlist(item) {
  try {
    await wlApi.unlinkPeriod(item.id)
    await wlStore.update(item.id, { purchased: false })
    await loadForecast()
    message.success('Запись отвязана')
  } catch (e) {
    message.error(e.message)
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

// ── Options ───────────────────────────────────────────────────────────────────

const catStore = useCategoriesStore()
const categoryOptions = computed(() => catStore.options('wishlist'))

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
            await catStore.remove(option.id, 'wishlist')
            if (form.value.category === option.value) form.value.category = ''
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

// Forecast lists mix wishlist + expense categories (regular items reuse
// expense). `findAcrossSections` lets row icons resolve regardless of
// which section the category was created under.
function renderCategoryLabel(option) {
  return h(CategoryLabel, {
    name: option.value,
    category: catStore.findAcrossSections(option.value),
    size: 14,
  })
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
  estimated_cost: [
    { required: true, type: 'number', message: 'Введите стоимость', trigger: 'blur' },
  ],
  category: [{ required: true, message: 'Выберите категорию', trigger: 'change' }],
}

function freqLabel(f) {
  const map = {
    once: 'Однократно',
    monthly: 'Ежемесячно',
    quarterly: 'Ежеквартально',
    yearly: 'Ежегодно',
  }
  return map[f] || f
}

// Per-period suffix for displayed amounts in «Регулярные расходы» rows.
function freqUnit(f) {
  if (f === 'quarterly') return '₽/кв'
  if (f === 'yearly') return '₽/год'
  return '₽/мес'
}

function formatDueDate(iso) {
  if (!iso) return ''
  // iso is YYYY-MM-DD; render as DD.MM.YYYY for Russian locale.
  const [y, m, d] = iso.split('-')
  return `${d}.${m}.${y}`
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
    if (cat && !catStore.bySection.wishlist.find((c) => c.name === cat)) {
      await catStore.add('wishlist', cat).catch(() => {})
    }
    // Derive frequency from the type pill: wishlist branch is always 'once'.
    const frequency = form.value.kind === 'regular' ? form.value.frequency : 'once'
    const payload = {
      name: form.value.name,
      estimated_cost: form.value.estimated_cost,
      category: cat,
      frequency,
      deposit: normalizeDeposit(form.value.deposit),
      notes: form.value.notes,
    }
    if (mobileForecastEditing.value) {
      await wlStore.update(mobileForecastEditing.value.id, payload)
      message.success('Сохранено')
    } else {
      await wlStore.create(payload)
      message.success(
        form.value.kind === 'regular'
          ? 'Добавлено в регулярные расходы'
          : 'Добавлено в список желаний',
      )
    }
    catStore.recordUse('wishlist', cat)
    // Пушим «Название» в localStorage-историю для autocomplete'a на
    // следующих добавлениях (см. utils/inputHistory.js).
    pushHistory('forecast-name', form.value.name)
    refreshNameHistory()
    await loadForecast()
    if (!mobileForecastEditing.value) {
      // Reset для нового добавления (keep kind для quick-entry).
      form.value = {
        kind: form.value.kind,
        name: '',
        estimated_cost: null,
        category: '',
        frequency: form.value.kind === 'regular' ? form.value.frequency : 'monthly',
        deposit: DEPOSIT_DEFAULT,
        notes: '',
      }
    }
    if (isMobile.value) exitForecastForm()
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

// Delete текущей редактируемой записи из mobile edit-вида.
async function deleteEditingForecast() {
  if (!mobileForecastEditing.value) return
  saving.value = true
  try {
    await wlStore.remove(mobileForecastEditing.value.id)
    await loadForecast()
    message.success('Удалено')
    exitForecastForm()
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

// Action-кнопки в edit-footer (мобильный edit-вид). Дёргают существующие
// flow'ы pay/unlink, после успешного действия закрываем форму.
function payEditingForecast() {
  const item = mobileForecastEditing.value
  if (!item) return
  exitForecastForm()
  if (form.value.kind === 'regular') {
    openPayRegular(item)
  } else {
    openPayWishlist(item)
  }
}

async function unpurchaseEditingForecast() {
  const item = mobileForecastEditing.value
  if (!item) return
  saving.value = true
  try {
    if (form.value.kind === 'regular') {
      await cancelRegularPaid(item)
    } else {
      await unpurchaseWishlist(item)
    }
    exitForecastForm()
  } finally {
    saving.value = false
  }
}

// ── Desktop NDataTable: shared render helpers ─────────────────────────────────
// Mirrors the pattern в IncomeView.vue: pencil-кнопка по ячейке, ok/cancel
// контролы возле edit-инпута, чекбокс-кружок для bulk-режима.

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

function selectionCheckbox(checked, onClick) {
  return h(
    'div',
    {
      style: `cursor:pointer;display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;border:2px solid ${checked ? primaryColor.value : palette.value.text3};background:${checked ? primaryColor.value : 'transparent'};color:#fff;transition:background .15s,border-color .15s;box-sizing:border-box`,
      onClick,
    },
    checked
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

// Inline-edit ячейки общего шаблона: либо input/select + ok/cancel, либо
// отображаемое значение + pencil. `field` соответствует ключам в startEdit:
// 'name' | 'category' | 'notes' | 'cost'.
// Inline-edit ячейки общего шаблона. Layout:
//   • Content — `flex:1; min-width:0` (растягивается на доступную ширину).
//   • Pencil — справа, прижат к top-right углу через `align-items: flex-start`
//     (мирор Name-колонки). Pencil оказывается в одной вертикали по всему
//     столбцу, content — левее.
// При `hidePencil` (compact mode) возвращаем displayNode как есть — чтобы
// Naive's column-level `ellipsis: { tooltip: true }` корректно truncate'ил.
function renderEditableCell(item, field, displayNode, opts = {}) {
  if (isEditing(item.id, field)) {
    let input
    if (field === 'category') {
      input = h(NSelect, {
        value: editValue.value,
        options: categoryOptions.value,
        filterable: true,
        tag: true,
        onCreate: handleCategoryCreate,
        renderOption: renderCategoryOption,
        renderLabel: renderCategoryLabel,
        size: 'small',
        to: 'body',
        style: 'min-width:140px',
        'onUpdate:value': (v) => {
          editValue.value = v
        },
      })
    } else if (field === 'cost') {
      input = h(NInputNumber, {
        value: editValue.value,
        min: 1,
        size: 'small',
        style: 'width:120px',
        'onUpdate:value': (v) => {
          editValue.value = v
        },
        onKeydown: (e) => {
          if (e.key === 'Enter') confirmEdit(item)
          if (e.key === 'Escape') cancelEdit()
        },
      })
    } else {
      input = h(NInput, {
        value: editValue.value,
        size: 'small',
        style: field === 'notes' ? 'min-width:140px' : 'width:160px',
        'onUpdate:value': (v) => {
          editValue.value = v
        },
        onKeydown: (e) => {
          if (e.key === 'Enter') confirmEdit(item)
          if (e.key === 'Escape') cancelEdit()
        },
      })
    }
    return h('div', { style: 'display:flex;align-items:center;gap:2px;min-width:0' }, [
      input,
      okBtn(() => confirmEdit(item)),
      cancelBtn(cancelEdit),
    ])
  }

  if (opts.hidePencil) return displayNode

  return h('div', { style: 'display:flex;align-items:flex-start;gap:4px;min-width:0;width:100%' }, [
    h('span', { style: 'flex:1;min-width:0' }, [displayNode]),
    pencilBtn(() => startEdit(item, field)),
  ])
}

// Текстовая cell-фабрика для compact-режима: Naive column-level
// `ellipsis: { tooltip: true }` работает только если render возвращает
// единичный span/строку (а не flex-див с pencil). Поэтому в compact-режиме
// pencil снят, а cell — простой span с возможным дополнительным стилем.
function plainTextCell(text, extraStyle = '') {
  return h('span', { style: extraStyle }, text)
}

// ── Регулярные расходы — columns ─────────────────────────────────────────────

// Иконочная кнопка с тултипом для action-колонок таблиц (мирор Income/Expense).
const iconActionBtn = ({ icon, tooltip, type = 'default', loading = false, onClick }) =>
  h(NTooltip, null, {
    trigger: () =>
      h(
        NButton,
        { size: 'small', quaternary: true, type, loading, onClick },
        { icon: () => h(NIcon, null, { default: () => h(icon) }) },
      ),
    default: () => tooltip,
  })

// Один action-spec — описывает кнопку независимо от способа рендеринга:
//   { icon, label, type, onClick, confirm? (text) }
// В «full»-режиме рендерится как iconActionBtn (или NPopconfirm-обёртка, если
// есть confirm). В «compact»-режиме — строка в popover'е с label-ом.
function renderActionButton(a) {
  const btn = iconActionBtn({
    icon: a.icon,
    tooltip: a.label,
    type: a.type,
    loading: a.loading,
    onClick: a.confirm ? undefined : a.onClick,
  })
  if (a.confirm) {
    return h(
      NPopconfirm,
      { onPositiveClick: a.onClick },
      { trigger: () => btn, default: () => a.confirm },
    )
  }
  return btn
}

function renderActionRow(a) {
  const inner = h(
    NButton,
    {
      size: 'small',
      quaternary: true,
      type: a.type || 'default',
      block: true,
      loading: a.loading,
      style: 'justify-content:flex-start;text-align:left',
      onClick: a.confirm ? undefined : a.onClick,
    },
    {
      icon: () => h(NIcon, null, { default: () => h(a.icon) }),
      default: () => a.label,
    },
  )
  if (a.confirm) {
    return h(
      NPopconfirm,
      { onPositiveClick: a.onClick },
      { trigger: () => inner, default: () => a.confirm },
    )
  }
  return inner
}

function renderActionsCell(actions, { compact }) {
  const items = actions.filter(Boolean)
  if (!items.length) return null
  if (compact) {
    return h(
      NPopover,
      { trigger: 'click', placement: 'bottom-end' },
      {
        trigger: () =>
          h(NTooltip, null, {
            trigger: () =>
              h(
                NButton,
                { size: 'small', quaternary: true },
                {
                  icon: () => h(NIcon, null, { default: () => h(EllipsisHorizontalOutline) }),
                },
              ),
            default: () => 'Действия',
          }),
        default: () =>
          h(
            'div',
            { style: 'display:flex;flex-direction:column;gap:2px;min-width:170px' },
            items.map((a) => renderActionRow(a)),
          ),
      },
    )
  }
  return h(
    NSpace,
    { size: 2, justify: 'center', wrap: false },
    { default: () => items.map((a) => renderActionButton(a)) },
  )
}

// Колонка имени: название с inline-pencil + чипсы статуса/частоты под ним.
// Pencil выровнен к началу первой строки текста (`align-items: flex-start`),
// а не центру cell'а — иначе при wrap длинных имён он съезжал вниз. В режиме
// veryCompact pencil скрыт (см. renderEditableCell), как и здесь во inline-
// state — освобождает место в узких пейнах.
function nameCell(row, opts) {
  const nameNode = h(
    NText,
    {
      style: {
        fontWeight: 500,
        textDecoration: opts.strike ? 'line-through' : 'none',
        color: opts.strike ? palette.value.text3 : 'inherit',
        wordBreak: 'break-word',
      },
    },
    { default: () => row.name },
  )
  let editableRow
  if (isEditing(row.id, 'name')) {
    editableRow = renderEditableCell(row, 'name', nameNode, { hidePencil: opts.hidePencil })
  } else if (opts.hidePencil) {
    editableRow = h('div', { style: 'display:flex;align-items:flex-start;min-width:0' }, [
      h('span', { style: 'flex:1;min-width:0' }, [nameNode]),
    ])
  } else {
    editableRow = h('div', { style: 'display:flex;align-items:flex-start;gap:4px;min-width:0' }, [
      h('span', { style: 'flex:1;min-width:0' }, [nameNode]),
      pencilBtn(() => startEdit(row, 'name')),
    ])
  }
  const tags = opts.tags.filter(Boolean)
  return h('div', { style: 'display:flex;flex-direction:column;gap:4px;min-width:0' }, [
    editableRow,
    tags.length ? h('div', { style: 'display:flex;flex-wrap:wrap;gap:4px' }, tags) : null,
  ])
}

const regularColumns = computed(() => {
  const hidePencil = regularHidePencils.value
  const compactActions = regularCompactActions.value
  const ultraCompact = regularUltraCompact.value
  return [
    {
      title: '',
      key: 'select',
      width: 36,
      align: 'center',
      render: (row) => {
        if (regularBulkMode.value) {
          return selectionCheckbox(regularSelectedIds.value.has(row.id), () =>
            toggleRegularSelect(row.id),
          )
        }
        return null
      },
    },
    {
      title: 'Название',
      key: 'name',
      minWidth: 90,
      render: (row) =>
        nameCell(row, {
          strike: row.paid_this_period,
          hidePencil,
          tags: [
            h(
              NTag,
              { type: 'info', size: 'small', round: true, bordered: false },
              { default: () => freqLabel(row.frequency) },
            ),
            row.paid_this_period
              ? h(
                  NTag,
                  { type: 'success', size: 'small', round: true, bordered: false },
                  { default: () => 'Оплачено' },
                )
              : null,
          ],
        }),
    },
    hidePencil
      ? {
          title: 'Категория',
          key: 'category',
          minWidth: 70,
          ellipsis: { tooltip: true },
          render: (row) =>
            row.category
              ? h(CategoryLabel, {
                  name: row.category,
                  category: catStore.findAcrossSections(row.category),
                  size: 14,
                })
              : plainTextCell('—'),
        }
      : {
          title: 'Категория',
          key: 'category',
          minWidth: 100,
          render: (row) =>
            renderEditableCell(
              row,
              'category',
              row.category
                ? h(CategoryLabel, {
                    name: row.category,
                    category: catStore.findAcrossSections(row.category),
                    size: 14,
                  })
                : h('span', {}, '—'),
            ),
        },
    hidePencil
      ? {
          title: 'Заметки',
          key: 'notes',
          minWidth: 70,
          ellipsis: { tooltip: true },
          render: (row) =>
            plainTextCell(row.notes || 'без заметок', 'font-style:italic;opacity:0.75'),
        }
      : {
          title: 'Заметки',
          key: 'notes',
          minWidth: 100,
          render: (row) =>
            renderEditableCell(
              row,
              'notes',
              h('span', { style: 'font-style:italic;opacity:0.75' }, row.notes || 'без заметок'),
            ),
        },
    ultraCompact
      ? null
      : {
          title: 'След. оплата',
          key: 'next_due_date',
          width: 80,
          render: (row) =>
            h(
              'span',
              { style: 'font-size:12px;opacity:0.75;white-space:nowrap' },
              row.paid_this_period && row.next_due_date ? formatDueDate(row.next_due_date) : '—',
            ),
        },
    {
      title: 'Сумма',
      key: 'monthly_cost',
      width: 100,
      render: (row) => {
        const amountNode = h(
          NText,
          {
            strong: true,
            style: {
              color: row.paid_this_period ? palette.value.text3 : palette.value.expense,
              textDecoration: row.paid_this_period ? 'line-through' : 'none',
              whiteSpace: 'nowrap',
            },
          },
          {
            default: () =>
              `${Math.round(row.monthly_cost).toLocaleString('ru-RU')} ${freqUnit(row.frequency)}`,
          },
        )
        return renderEditableCell(row, 'cost', amountNode, { hidePencil })
      },
    },
    {
      title: '',
      key: 'actions',
      width: compactActions ? 44 : 116,
      align: 'right',
      render: (row) => {
        if (regularBulkMode.value) return null
        // Single «toggle» slot in the centre: ✓ Оплачено when the period is
        // not yet paid, ✗ Отменить оплату when it is. Link sits to the left
        // (always visible), trash to the right.
        const toggleDesc = row.paid_this_period
          ? {
              icon: RefreshOutline,
              label: 'Отменить оплату',
              type: 'warning',
              loading: cancelingId.value === row.id,
              confirm: 'Отменить оплату в текущем периоде?',
              onClick: () => cancelRegularPaid(row),
            }
          : {
              icon: CheckmarkOutline,
              label: 'Оплачено',
              type: 'success',
              onClick: () => openPayRegular(row),
            }
        const linkDesc = {
          icon: LinkOutline,
          label: 'Привязать существующий расход',
          type: 'info',
          onClick: () => openLinkExisting(row),
        }
        const trashDesc = {
          icon: TrashOutline,
          label: 'Удалить',
          type: 'error',
          confirm: 'Удалить эту позицию?',
          onClick: () => wlStore.remove(row.id).then(loadForecast),
        }
        if (compactActions) {
          return renderActionsCell([linkDesc, toggleDesc, trashDesc], { compact: true })
        }
        // Fixed 3-slot layout: [Link, Toggle, Trash]. Every row uses the same
        // slot count so the trash column lines up vertically across paid /
        // unpaid rows.
        return h(
          'div',
          { style: 'display:flex;justify-content:flex-end;align-items:center;gap:2px' },
          [
            renderActionButton(linkDesc),
            renderActionButton(toggleDesc),
            renderActionButton(trashDesc),
          ],
        )
      },
    },
  ].filter(Boolean)
})

function getRegularRowProps(row) {
  if (regularBulkMode.value && regularSelectedIds.value.has(row.id)) {
    return { class: 'fc-row-sel' }
  }
  return {}
}

// ── Список желаний — columns ──────────────────────────────────────────────────

const wishlistColumns = computed(() => {
  const hidePencil = wishlistHidePencils.value
  const compactActions = wishlistCompactActions.value
  return [
    {
      title: '',
      key: 'select',
      width: 42,
      align: 'center',
      render: (row) => {
        if (bulkMode.value) {
          return selectionCheckbox(selectedIds.value.has(row.id), () => toggleSelect(row.id))
        }
        if (!row.created_by) {
          return h(NTooltip, null, {
            trigger: () => userPlaceholder(() => openReassign(row.id, 'wishlist')),
            default: () => 'Назначить автора',
          })
        }
        return h(NTooltip, null, {
          trigger: () =>
            h(
              'div',
              { style: 'cursor:pointer', onClick: () => openReassign(row.id, 'wishlist') },
              h(UserAvatar, {
                displayName: row.created_by.display_name,
                avatarUrl: row.created_by.avatar_url || '',
                size: 28,
              }),
            ),
          default: () => `${row.created_by.display_name} · нажмите для смены`,
        })
      },
    },
    {
      title: 'Название',
      key: 'name',
      minWidth: 90,
      render: (row) =>
        nameCell(row, {
          strike: row.purchased,
          hidePencil,
          tags: [
            row.purchased
              ? h(
                  NTag,
                  { type: 'success', size: 'small', round: true, bordered: false },
                  { default: () => 'Куплено' },
                )
              : null,
          ],
        }),
    },
    hidePencil
      ? {
          title: 'Категория',
          key: 'category',
          minWidth: 70,
          ellipsis: { tooltip: true },
          render: (row) =>
            row.category
              ? h(CategoryLabel, {
                  name: row.category,
                  category: catStore.findAcrossSections(row.category),
                  size: 14,
                })
              : plainTextCell('—'),
        }
      : {
          title: 'Категория',
          key: 'category',
          minWidth: 100,
          render: (row) =>
            renderEditableCell(
              row,
              'category',
              row.category
                ? h(CategoryLabel, {
                    name: row.category,
                    category: catStore.findAcrossSections(row.category),
                    size: 14,
                  })
                : h('span', {}, '—'),
            ),
        },
    hidePencil
      ? {
          title: 'Заметки',
          key: 'notes',
          minWidth: 70,
          ellipsis: { tooltip: true },
          render: (row) =>
            plainTextCell(row.notes || 'без заметок', 'font-style:italic;opacity:0.75'),
        }
      : {
          title: 'Заметки',
          key: 'notes',
          minWidth: 100,
          render: (row) =>
            renderEditableCell(
              row,
              'notes',
              h('span', { style: 'font-style:italic;opacity:0.75' }, row.notes || 'без заметок'),
            ),
        },
    {
      title: 'Сумма',
      key: 'estimated_cost',
      width: 100,
      render: (row) => {
        const amountNode = h(
          NText,
          {
            strong: true,
            style: {
              color: row.purchased ? palette.value.text3 : primaryColor.value,
              textDecoration: row.purchased ? 'line-through' : 'none',
              whiteSpace: 'nowrap',
            },
          },
          { default: () => `${row.estimated_cost.toLocaleString('ru-RU')} ₽` },
        )
        return renderEditableCell(row, 'cost', amountNode, { hidePencil })
      },
    },
    {
      title: '',
      key: 'actions',
      width: compactActions ? 44 : 116,
      align: 'right',
      render: (row) => {
        if (bulkMode.value) return null
        // Mirror the regular-row layout: [link][toggle][trash]. «Привязать»
        // прячется когда `purchased=true` (одна запись = одна привязка), но
        // слот сохраняется, чтобы trash оставался в общей вертикали.
        const toggleDesc = row.purchased
          ? {
              icon: CloseOutline,
              label: 'Не куплено',
              type: 'warning',
              onClick: () => unpurchaseWishlist(row),
            }
          : {
              icon: CheckmarkOutline,
              label: 'Куплено',
              type: 'success',
              onClick: () => openPayWishlist(row),
            }
        const linkDesc = !row.purchased
          ? {
              icon: LinkOutline,
              label: 'Привязать существующий расход',
              type: 'info',
              onClick: () => openLinkExisting(row),
            }
          : null
        const trashDesc = {
          icon: TrashOutline,
          label: 'Удалить',
          type: 'error',
          confirm: 'Удалить позицию?',
          onClick: () => wlStore.remove(row.id),
        }
        if (compactActions) {
          return renderActionsCell([linkDesc, toggleDesc, trashDesc].filter(Boolean), {
            compact: true,
          })
        }
        // Fixed 3-slot layout: [link-or-placeholder, toggle, trash].
        return h(
          'div',
          { style: 'display:flex;justify-content:flex-end;align-items:center;gap:2px' },
          [
            linkDesc ? renderActionButton(linkDesc) : h('div', { style: 'width:28px' }),
            renderActionButton(toggleDesc),
            renderActionButton(trashDesc),
          ],
        )
      },
    },
  ]
})

function getWishlistRowProps(row) {
  if (bulkMode.value && selectedIds.value.has(row.id)) {
    return { class: 'fc-row-sel' }
  }
  return {}
}

async function loadForecast() {
  loadingForecast.value = true
  try {
    const params = forecastDeposit.value ? { deposit: forecastDeposit.value } : undefined
    const { data } = await statistics.forecast(params)
    forecast.value = data
  } finally {
    loadingForecast.value = false
  }
}

function onForecastDepositChange() {
  loadForecast()
}

const forecastCategoryMeta = computed(() => {
  const out = {}
  for (const c of catStore.bySection.expense) {
    out[c.name] = { color: c.color, icon: c.icon, icon_scale: c.icon_scale }
  }
  return out
})

// ── «Фокус» на конкретной записи прогноза ────────────────────────────────────
//
// Расходы держат теги «Привязано к: …», по клику переходят на
// `/forecast?focus=<wishlist_id>`. Здесь мы:
//   1) Дожидаемся загрузки wishlist/forecast (чтобы знать частоту итема).
//   2) Переключаем мобильную табу на нужную секцию (regular / wishlist).
//   3) Подсвечиваем строку через row-class на 2.5с (CSS — `.fc-row-focus`).
const route = useRoute()
const focusedId = ref('')
// Tap-suppression window: after a link-nav focus, the device dispatches a
// synthetic "ghost" click at the tap coordinates onto the freshly-rendered
// Forecast card, which would open its edit sheet. Ignore taps until this ts.
let tapSuppressUntil = 0

async function focusForecastItem(id) {
  if (!id) return
  // Ждём один тик, чтобы wlStore.fetch + loadForecast завершились — иначе
  // мобильный switch-tab сработает не на ту секцию.
  if (!wlStore.items.length) await wlStore.fetch()
  const item = wlStore.items.find((x) => x.id === id)
  if (!item) return
  const isRegular = item.frequency && item.frequency !== 'once'
  if (isMobile.value) {
    activeTab.value = isRegular ? 'regular' : 'wishlist'
  }
  focusedId.value = id
  // Swallow the cross-navigation ghost click that follows the tap on the
  // source link tag (see tapSuppressUntil).
  tapSuppressUntil = Date.now() + 700
  // Scroll the matching row/card into view after the next paint. Highlight is
  // persistent (no auto-clear) so the user keeps the found item in view.
  await new Promise((r) => setTimeout(r, 50))
  const el =
    document.querySelector(`[data-focus-id="${id}"]`) || document.querySelector(`.fc-row-focus`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function getRegularRowClass(row) {
  return row.id === focusedId.value ? 'fc-row-focus' : ''
}
function getWishlistRowClass(row) {
  return row.id === focusedId.value ? 'fc-row-focus' : ''
}

onMounted(async () => {
  catStore.load('wishlist')
  // Expense categories are needed for the prefilled "Оплачено" form below.
  catStore.load('expense')
  await Promise.all([wlStore.fetch(), loadForecast()])
  if (route.query.focus) await focusForecastItem(String(route.query.focus))
})

// Re-trigger if the user navigates here again with a different focus id
// while the view is already mounted.
watch(
  () => route.query.focus,
  (v) => {
    if (route.path === '/forecast' && v) focusForecastItem(String(v))
  },
)
</script>

<style scoped>
/* Mobile tab strip — мирор SettingsTabs.vue: горизонтальная панель
   из 3 кнопок-таб поверх ленты секций. На десктопе скрыта (v-if). */
.forecast-tabs {
  display: flex;
  gap: 4px;
  background: var(--st-surface);
  border: 1px solid var(--st-border);
  /* 3px пара (container + inner) — единый радиус по всем mobile
     tab-strip'ам (`.settings-tabs`, `.section-row` mobile). Совпадает
     с дефолтным NCard.borderRadius — таб-полоска визуально читается
     как продолжение соседних карточек. */
  border-radius: 3px;
  padding: 4px;
  margin-bottom: 12px;
  /* Горизонтальный скролл вместо переноса/сжатия — как в `.settings-tabs`. */
  flex-wrap: nowrap;
  overflow-x: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.forecast-tabs::-webkit-scrollbar {
  display: none;
}
.forecast-tab {
  flex: 1 0 auto;
  white-space: nowrap;
  border: 0;
  background: transparent;
  color: var(--st-text2);
  padding: 8px 6px;
  border-radius: 3px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background 0.15s,
    color 0.15s;
}
.forecast-tab.active {
  background: var(--st-primary);
  color: var(--st-on-primary, #fff);
}

/* Mobile-only n-card header content for the add-form view — мирор
   IncomeView / ExpensesView. */
.card-back-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-back-header :deep(.n-button) {
  margin-right: 2px;
}

/* Mobile карточный listing — flex-стек с тем же gap'ом, что и у
   n-space :size="8". Назначается на `<TransitionGroup tag="div">`
   чтобы TransitionGroup мог раскладывать карточки. */
.tx-cards-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* Mobile-only simplified row для Forecast карточек: тот же визуал, что
   и у транзакций (Income/Expenses) — дата/название/категория/сумма. */
.tx-mobile-row {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  margin: -4px -2px; /* компенсировать `n-card` size=small padding */
}
.tx-mobile-row.paid .tx-card-category {
  text-decoration: line-through;
  opacity: 0.65;
}
.tx-mobile-row .tx-card-left {
  flex-shrink: 0;
  width: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.tx-mobile-row .tx-card-body {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
}
.tx-mobile-row .tx-card-row1 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  line-height: 1.2;
  flex-wrap: wrap;
}
.tx-mobile-row .tx-card-category {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.tx-mobile-row .tx-card-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3, rgba(127, 127, 127, 0.75));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tx-mobile-row .tx-card-amount {
  flex-shrink: 0;
  font-weight: 600;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
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

/* Во время свайпа правые углы n-card выпрямляются, чтобы стыковаться с
   левым ребром action-панели без зазора. transition сглаживает. */
:deep(.sc-content) .n-card {
  transition: border-radius 0.2s ease-out;
}
:deep(.sc-content.revealed) .n-card {
  border-top-right-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}
/* Naive UI палитра для positive/destructive экшенов (Paid·Bought·Delete);
   на «отменяющих» действиях (Cancel paid / Un-buy) оставлен Material Grey
   600 — желтый там читался как «warning», хотя действие неразрушительное.
   Grey честно сигналит «возврат в нейтральное состояние». */
.swipe-action-success {
  background: #18a058;
}
.swipe-action-warning {
  background: #757575;
}
.swipe-action-danger {
  background: #d03050;
}
.swipe-action-info {
  background: #2080f0;
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
  background: v-bind(primaryColor);
  border-color: v-bind(primaryColor);
}

/* Edit-режим: Удалить + Сохранить в одну строку (см. Income/Expenses). */
.form-actions-row {
  display: flex;
  gap: 8px;
}

.dep-radio-content {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  line-height: 1;
}

/* Bulk-selected строки получают class `.fc-row-sel` и подсвечиваются через
   td-таргетинг — иначе background на tr может перекрываться cell-background
   из header/scroll-container. */
.forecast-split :deep(.n-data-table-tr.fc-row-sel > .n-data-table-td) {
  background: v-bind('primaryColor + "1f"');
}

/* «Фокус» — подсветка целевой строки/карточки после прихода с
   /forecast?focus=<id>. Тонкая (1px) жёлто-оранжевая обводка + лёгкий фон.
   Подсветка стойкая (не затухает) — держится пока `focusedId` установлен,
   чтобы юзер не терял найденный итем из виду. На десктопе — inset-обводка по
   ячейкам; на мобиле — внешнее кольцо вокруг непрозрачной карточки (inset не
   видно из-за её фона), чтобы подсветка вообще проявилась. */
/* Single 1px border around the whole row (no per-cell verticals): top+bottom
   on every cell + left on the first cell, right on the last — mirrors the
   Income/Expenses parent-focus ring. Colour = theme primary; these forecast
   items have no group tint so a soft primary fill is fine. */
.forecast-split :deep(.n-data-table-tr.fc-row-focus > .n-data-table-td) {
  background: v-bind(focusBg);
  box-shadow:
    inset 0 1px 0 v-bind(primaryColor),
    inset 0 -1px 0 v-bind(primaryColor);
}
.forecast-split :deep(.n-data-table-tr.fc-row-focus > .n-data-table-td:first-child) {
  box-shadow:
    inset 0 1px 0 v-bind(primaryColor),
    inset 0 -1px 0 v-bind(primaryColor),
    inset 1px 0 0 v-bind(primaryColor);
}
.forecast-split :deep(.n-data-table-tr.fc-row-focus > .n-data-table-td:last-child) {
  box-shadow:
    inset 0 1px 0 v-bind(primaryColor),
    inset 0 -1px 0 v-bind(primaryColor),
    inset -1px 0 0 v-bind(primaryColor);
}
.fc-card-focus {
  border-radius: 8px;
  box-shadow: 0 0 0 1px v-bind(primaryColor);
}
</style>
