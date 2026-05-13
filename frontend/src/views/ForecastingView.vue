<template>
  <div>
    <!-- Forecast summary — 4 cards: total / 3-mo avg / regular / wishlist.
         The two contribution numbers come from the api 1.12.0 split:
         regular_contrib (recurring only) and wishlist_contrib − regular_contrib
         (one-off only). -->
    <n-grid
      :cols="4"
      :x-gap="16"
      :y-gap="16"
      responsive="screen"
      :item-responsive="true"
      style="margin-bottom: 16px"
    >
      <n-grid-item span="4 s:2 m:1">
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
      <n-grid-item span="4 s:2 m:1">
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
      <n-grid-item span="4 s:2 m:1">
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
      <n-grid-item span="4 s:2 m:1">
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
      <n-grid-item span="2 m:1">
        <n-card title="Прогноз по категориям">
          <n-spin :show="loadingForecast">
            <CategoryDonutChart
              v-if="forecast.breakdown?.length"
              :data="forecast.breakdown.map((d) => ({ category: d.category, amount: d.amount }))"
              :category-meta="forecastCategoryMeta"
              :palette="palette"
              unit="percent"
            />
            <n-empty
              v-else
              description="Добавьте транзакции или позиции в список желаний"
              style="padding: 60px 0"
            />
          </n-spin>
        </n-card>
      </n-grid-item>

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
            <n-grid :cols="2" :x-gap="12" :item-responsive="true" style="margin-top: 12px">
              <n-grid-item span="2">
                <n-form-item label="Название" path="name">
                  <n-input v-model:value="form.name" placeholder="Что хочу купить" />
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
            <n-button type="primary" :loading="saving" block @click="submit">
              {{ form.kind === 'regular' ? 'Добавить в регулярные' : 'Добавить в список' }}
            </n-button>
          </n-form>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Регулярные расходы + список желаний -->
    <n-grid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true">
      <n-grid-item span="2 m:1">
        <!-- Outer card with section title + bulk-edit toggle in the header.
             Inner sub-cards render each item with consistent columns. -->
        <n-card>
          <template #header>
            <n-space align="center" justify="space-between" style="width: 100%">
              <n-text strong>Регулярные расходы</n-text>
              <n-space align="center" :size="8">
                <template v-if="!regularBulkMode">
                  <n-button
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
                  <template v-if="regularSelectedIds.size">
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
                  <n-button size="small" quaternary @click="exitRegularBulkMode">Отмена</n-button>
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
            <n-space v-else vertical :size="8">
              <n-card
                v-for="item in forecast.regular_items"
                :key="item.id"
                size="small"
                :bordered="true"
                embedded
                :style="
                  regularBulkMode && regularSelectedIds.has(item.id)
                    ? `background:${primaryColor}1f`
                    : ''
                "
              >
                <div class="regular-row">
                  <div v-if="regularBulkMode" class="regular-row__avatar">
                    <div
                      class="bulk-checkbox"
                      :class="{ checked: regularSelectedIds.has(item.id) }"
                      :style="checkboxStyle(regularSelectedIds.has(item.id))"
                      @click="toggleRegularSelect(item.id)"
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
                  <div class="regular-row__main">
                    <div class="regular-row__title">
                      <template v-if="isEditing(item.id, 'name') && !regularBulkMode">
                        <n-input
                          v-model:value="editValue"
                          size="small"
                          style="width: 200px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </template>
                      <template v-else>
                        <n-text
                          :style="{
                            fontWeight: 500,
                            textDecoration: item.paid_this_period ? 'line-through' : 'none',
                            color: item.paid_this_period ? palette.text3 : 'inherit',
                          }"
                        >
                          {{ item.name }}
                        </n-text>
                        <span
                          v-if="!regularBulkMode"
                          class="inline-edit-icon"
                          title="Редактировать название"
                          @click="startEdit(item, 'name')"
                          v-html="pencilIconHtml"
                        />
                      </template>
                      <n-tag type="info" size="small" round>{{ freqLabel(item.frequency) }}</n-tag>
                      <n-tag v-if="item.paid_this_period" type="success" size="small" round>
                        Оплачено · {{ Math.round(item.paid_amount).toLocaleString('ru-RU') }} ₽
                      </n-tag>
                    </div>
                    <div class="regular-row__meta">
                      <template v-if="isEditing(item.id, 'category') && !regularBulkMode">
                        <n-select
                          v-model:value="editValue"
                          :options="categoryOptions"
                          filterable
                          tag
                          :on-create="handleCategoryCreate"
                          to="body"
                          size="small"
                          style="min-width: 180px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </template>
                      <template v-else>
                        <n-text depth="3" style="font-size: 12px">{{ item.category }}</n-text>
                        <span
                          v-if="!regularBulkMode"
                          class="inline-edit-icon"
                          title="Редактировать категорию"
                          @click="startEdit(item, 'category')"
                          v-html="pencilIconHtml"
                        />
                      </template>
                      <span v-if="!isEditing(item.id, 'category')" class="meta-sep">·</span>
                      <template v-if="isEditing(item.id, 'notes') && !regularBulkMode">
                        <n-input
                          v-model:value="editValue"
                          size="small"
                          style="width: 240px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </template>
                      <template v-else>
                        <n-text depth="3" style="font-size: 12px; font-style: italic">
                          {{ item.notes || 'без заметок' }}
                        </n-text>
                        <span
                          v-if="!regularBulkMode"
                          class="inline-edit-icon"
                          title="Редактировать заметку"
                          @click="startEdit(item, 'notes')"
                          v-html="pencilIconHtml"
                        />
                      </template>
                      <template v-if="item.paid_this_period && item.next_due_date">
                        <span class="meta-sep">·</span>
                        <n-text depth="3" style="font-size: 12px">
                          след. оплата: {{ formatDueDate(item.next_due_date) }}
                        </n-text>
                      </template>
                    </div>
                  </div>
                  <div class="regular-row__amount">
                    <template v-if="isEditing(item.id, 'cost') && !regularBulkMode">
                      <n-space :size="4" align="center">
                        <n-input-number
                          v-model:value="editValue"
                          :min="1"
                          size="small"
                          style="width: 120px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </n-space>
                    </template>
                    <template v-else>
                      <n-text
                        strong
                        :style="{
                          color: item.paid_this_period ? palette.text3 : palette.expense,
                          textDecoration: item.paid_this_period ? 'line-through' : 'none',
                          whiteSpace: 'nowrap',
                        }"
                      >
                        {{ Math.round(item.monthly_cost).toLocaleString('ru-RU') }}
                        {{ freqUnit(item.frequency) }}
                      </n-text>
                      <span
                        v-if="!regularBulkMode"
                        class="inline-edit-icon"
                        title="Редактировать стоимость"
                        @click="startEdit(item, 'cost')"
                        v-html="pencilIconHtml"
                      />
                    </template>
                  </div>
                  <div v-if="!regularBulkMode" class="regular-row__actions">
                    <n-button size="small" type="success" @click="openPayRegular(item)">
                      Оплачено
                    </n-button>
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

      <!-- Wishlist table -->
      <n-grid-item span="2 m:1">
        <n-card>
          <template #header>
            <n-space align="center" justify="space-between" style="width: 100%">
              <n-text strong>Список желаний</n-text>
              <n-space align="center" :size="8">
                <template v-if="!bulkMode">
                  <n-button size="small" :disabled="!wishlistOnly.length" @click="enterBulkMode">
                    Пакетное редактирование
                  </n-button>
                </template>
                <template v-else>
                  <n-text v-if="selectedIds.size" depth="2" style="font-size: 12px">
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
            <n-empty
              v-if="!wishlistOnly.length"
              description="Список пуст"
              style="padding: 40px 0"
            />
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
                          <svg
                            v-else
                            width="14"
                            height="14"
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            stroke-width="2"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          >
                            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                            <circle cx="12" cy="7" r="4" />
                          </svg>
                        </div>
                      </template>
                      {{
                        item.created_by
                          ? item.created_by.display_name + ' · нажмите для смены'
                          : 'Назначить автора'
                      }}
                    </n-tooltip>
                  </div>
                  <div class="regular-row__main">
                    <div class="regular-row__title">
                      <template v-if="isEditing(item.id, 'name') && !bulkMode">
                        <n-input
                          v-model:value="editValue"
                          size="small"
                          style="width: 200px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </template>
                      <template v-else>
                        <n-text
                          :style="{
                            fontWeight: 500,
                            textDecoration: item.purchased ? 'line-through' : 'none',
                            color: item.purchased ? palette.text3 : 'inherit',
                          }"
                        >
                          {{ item.name }}
                        </n-text>
                        <span
                          v-if="!bulkMode"
                          class="inline-edit-icon"
                          title="Редактировать название"
                          @click="startEdit(item, 'name')"
                          v-html="pencilIconHtml"
                        />
                      </template>
                      <n-tag v-if="item.purchased" type="success" size="small" round>Куплено</n-tag>
                    </div>
                    <div class="regular-row__meta">
                      <template v-if="isEditing(item.id, 'category') && !bulkMode">
                        <n-select
                          v-model:value="editValue"
                          :options="categoryOptions"
                          filterable
                          tag
                          :on-create="handleCategoryCreate"
                          to="body"
                          size="small"
                          style="min-width: 180px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </template>
                      <template v-else>
                        <n-text depth="3" style="font-size: 12px">{{ item.category }}</n-text>
                        <span
                          v-if="!bulkMode"
                          class="inline-edit-icon"
                          title="Редактировать категорию"
                          @click="startEdit(item, 'category')"
                          v-html="pencilIconHtml"
                        />
                      </template>
                      <span v-if="!isEditing(item.id, 'category')" class="meta-sep">·</span>
                      <template v-if="isEditing(item.id, 'notes') && !bulkMode">
                        <n-input
                          v-model:value="editValue"
                          size="small"
                          style="width: 240px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </template>
                      <template v-else>
                        <n-text depth="3" style="font-size: 12px; font-style: italic">
                          {{ item.notes || 'без заметок' }}
                        </n-text>
                        <span
                          v-if="!bulkMode"
                          class="inline-edit-icon"
                          title="Редактировать заметку"
                          @click="startEdit(item, 'notes')"
                          v-html="pencilIconHtml"
                        />
                      </template>
                    </div>
                  </div>
                  <div class="regular-row__amount">
                    <template v-if="isEditing(item.id, 'cost') && !bulkMode">
                      <n-space :size="4" align="center">
                        <n-input-number
                          v-model:value="editValue"
                          :min="1"
                          size="small"
                          style="width: 120px"
                          @keydown.enter="confirmEdit(item)"
                          @keydown.esc="cancelEdit"
                        />
                        <n-button
                          size="tiny"
                          type="primary"
                          style="padding: 0 5px; min-width: 24px"
                          @click="confirmEdit(item)"
                        >
                          ✓
                        </n-button>
                        <n-button
                          size="tiny"
                          style="padding: 0 5px; min-width: 24px"
                          @click="cancelEdit"
                        >
                          ✗
                        </n-button>
                      </n-space>
                    </template>
                    <template v-else>
                      <n-text
                        strong
                        :style="{
                          color: item.purchased ? palette.text3 : primaryColor,
                          whiteSpace: 'nowrap',
                          textDecoration: item.purchased ? 'line-through' : 'none',
                        }"
                      >
                        {{ item.estimated_cost.toLocaleString('ru-RU') }} ₽
                      </n-text>
                      <span
                        v-if="!bulkMode"
                        class="inline-edit-icon"
                        title="Редактировать стоимость"
                        @click="startEdit(item, 'cost')"
                        v-html="pencilIconHtml"
                      />
                    </template>
                  </div>
                  <div v-if="!bulkMode" class="regular-row__actions">
                    <n-button
                      size="small"
                      :type="item.purchased ? 'default' : 'success'"
                      @click="item.purchased ? unpurchaseWishlist(item) : openPayWishlist(item)"
                    >
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
import { ref, computed, onMounted, h } from 'vue'
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
  NInput,
  NInputNumber,
  NSelect,
  NModal,
  NTooltip,
  NDatePicker,
  NRadioGroup,
  NRadioButton,
} from 'naive-ui'
import { useWishlistStore } from '@/stores/wishlist'
import { useCategoriesStore } from '@/stores/categories'
import { statistics, users as usersApi, wishlist as wlApi, transactions as txApi } from '@/api'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import CategoryDonutChart from '@/components/CategoryDonutChart.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'

const themeStore = useThemeStore()
const { primaryColor, palette } = storeToRefs(themeStore)

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
  notes: '',
})

// Wishlist list excludes recurring items — those live in «Регулярные расходы».
const wishlistOnly = computed(() =>
  wlStore.items.filter((it) => !it.frequency || it.frequency === 'once'),
)

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

const purchasableSelected = computed(() =>
  wlStore.items.filter(
    (it) => selectedIds.value.has(it.id) && (!it.frequency || it.frequency === 'once'),
  ),
)
const purchasableSelectedCount = computed(() => purchasableSelected.value.length)
const allPurchasableSelectedPurchased = computed(() => {
  const list = purchasableSelected.value
  return list.length > 0 && list.every((it) => it.purchased)
})

async function bulkTogglePurchased() {
  const list = purchasableSelected.value
  if (!list.length) return
  const target = !allPurchasableSelectedPurchased.value
  bulkBusy.value = true
  try {
    await Promise.all(list.map((it) => wlApi.update(it.id, { purchased: target })))
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

// Inline pencil SVG reused across editable cells. Sized at 13px to match
// the existing cost-edit affordance.
const pencilIconHtml = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block;vertical-align:middle"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>`

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
    await wlStore.create({
      name: form.value.name,
      estimated_cost: form.value.estimated_cost,
      category: cat,
      frequency,
      notes: form.value.notes,
    })
    catStore.recordUse('wishlist', cat)
    await loadForecast()
    message.success(
      form.value.kind === 'regular'
        ? 'Добавлено в регулярные расходы'
        : 'Добавлено в список желаний',
    )
    form.value = {
      kind: form.value.kind, // keep the user's last choice for quick repeat entry
      name: '',
      estimated_cost: null,
      category: '',
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

const forecastCategoryMeta = computed(() => {
  const out = {}
  for (const c of catStore.bySection.expense) out[c.name] = { color: c.color, icon: c.icon }
  return out
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
.regular-row__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 2px;
}
.meta-sep {
  color: var(--n-text-color-3, rgba(255, 255, 255, 0.3));
  font-size: 12px;
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
