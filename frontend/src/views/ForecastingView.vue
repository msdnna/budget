<template>
  <div>
    <!-- Forecast summary -->
    <n-grid :cols="3" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true" style="margin-bottom: 16px;">
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Прогноз на месяц" :value="Math.round(forecast.total_monthly)" :precision="0">
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Среднее (3 мес.)" :value="Math.round(forecast.historical_avg)" :precision="0">
            <template #suffix>₽</template>
          </n-statistic>
        </n-card>
      </n-grid-item>
      <n-grid-item span="3 m:1">
        <n-card>
          <n-statistic label="Вклад списка желаний" :value="Math.round(forecast.wishlist_contrib)" :precision="0">
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
        <n-card title="Регулярные расходы">
          <n-spin :show="loadingForecast">
            <n-empty v-if="!forecast.regular_items?.length" description="Нет регулярных позиций" style="padding: 30px 0;" />
            <n-list v-else>
              <n-list-item v-for="item in forecast.regular_items" :key="item.id">
                <n-thing :title="item.name" :description="item.category">
                  <template #header-extra>
                    <n-space align="center">
                      <n-tag type="info" size="small">{{ freqLabel(item.frequency) }}</n-tag>
                      <n-text strong :style="{ color: palette.expense }">{{ Math.round(item.monthly_cost).toLocaleString('ru-RU') }} ₽/мес</n-text>
                    </n-space>
                  </template>
                </n-thing>
              </n-list-item>
            </n-list>
          </n-spin>
        </n-card>
      </n-grid-item>
    </n-grid>

    <!-- Wishlist management -->
    <n-grid :cols="2" :x-gap="16" :y-gap="16" responsive="screen" :item-responsive="true">
      <n-grid-item span="2 m:1">
        <n-card title="Добавить в список желаний">
          <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
            <n-grid :cols="2" :x-gap="12" :item-responsive="true">
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
                  <n-select v-model:value="form.category" :options="categoryOptions" placeholder="Категория" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2 s:1">
                <n-form-item label="Частота">
                  <n-select v-model:value="form.frequency" :options="frequencyOptions" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item v-if="form.frequency === 'once'" span="2">
                <n-form-item label="Куплено">
                  <n-switch v-model:value="form.purchased" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2">
                <n-form-item label="Заметки">
                  <n-input v-model:value="form.notes" placeholder="Необязательно" />
                </n-form-item>
              </n-grid-item>
            </n-grid>
            <n-button type="primary" :loading="saving" @click="submit" block>
              Добавить в список
            </n-button>
          </n-form>
        </n-card>
      </n-grid-item>

      <!-- Wishlist table -->
      <n-grid-item span="2 m:1">
        <n-card title="Список желаний">
          <n-spin :show="wlStore.loading">
            <n-empty v-if="!wlStore.items.length" description="Список пуст" style="padding: 40px 0;" />
            <n-list v-else>
              <n-list-item v-for="item in wlStore.items" :key="item.id">
                <n-thing :title="item.name" :description="item.category">
                  <!-- Avatar / assign user -->
                  <template #avatar>
                    <n-tooltip :delay="300">
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
                  </template>

                  <template #header-extra>
                    <n-space align="center">
                      <n-tag v-if="item.frequency && item.frequency !== 'once'" type="info" size="small">{{ freqLabel(item.frequency) }}</n-tag>
                      <n-tag v-if="item.purchased" type="success" size="small">Куплено</n-tag>

                      <!-- Inline cost edit -->
                      <template v-if="editingId === item.id">
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
                      </template>
                      <template v-else>
                        <n-text strong :style="{ color: item.purchased ? palette.text3 : primaryColor, whiteSpace: 'nowrap', textDecoration: item.purchased ? 'line-through' : 'none' }">
                          {{ item.estimated_cost.toLocaleString('ru-RU') }} ₽
                        </n-text>
                        <span class="inline-edit-icon" @click="startEdit(item)" title="Редактировать стоимость">
                          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                          </svg>
                        </span>
                      </template>
                    </n-space>
                  </template>

                  <template #action>
                    <n-space size="small">
                      <n-button v-if="item.frequency === 'once'" size="tiny" @click="wlStore.togglePurchased(item)">
                        {{ item.purchased ? 'Не куплено' : 'Куплено' }}
                      </n-button>
                      <n-popconfirm @positive-click="wlStore.remove(item.id)">
                        <template #trigger>
                          <n-button size="tiny" type="error" quaternary>✕</n-button>
                        </template>
                        Удалить позицию?
                      </n-popconfirm>
                    </n-space>
                  </template>
                </n-thing>
              </n-list-item>
            </n-list>
          </n-spin>
        </n-card>
      </n-grid-item>
    </n-grid>

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
import { ref, computed, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { use } from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import {
  NCard, NGrid, NGridItem, NStatistic, NSpin, NEmpty, NList, NListItem, NThing,
  NText, NTag, NSpace, NButton, NPopconfirm, NForm, NFormItem, NInput,
  NInputNumber, NSelect, NSwitch, NModal, NTooltip
} from 'naive-ui'
import { useWishlistStore } from '@/stores/wishlist'
import { statistics, users as usersApi } from '@/api'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import UserAvatar from '@/components/UserAvatar.vue'

use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

const { chartColors, primaryColor, palette } = storeToRefs(useThemeStore())

const wlStore = useWishlistStore()
const message = useMessage()
const formRef = ref(null)
const saving = ref(false)
const loadingForecast = ref(false)

const forecast = ref({
  total_monthly: 0, historical_avg: 0, wishlist_contrib: 0,
  breakdown: [], regular_items: [], unpurchased_wishlist: []
})

const form = ref({
  name: '', estimated_cost: null, category: '',
  frequency: 'once', purchased: false, notes: ''
})

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

const categoryOptions = [
  'Продукты','Транспорт','Жильё/ЖКХ','Рестораны','Развлечения',
  'Здоровье','Образование','Одежда','Электроника','Путешествия',
  'Связь','Красота','Спорт','Прочее'
].map(v => ({ label: v, value: v }))

const frequencyOptions = [
  { label: 'Однократно', value: 'once' },
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

async function submit() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    await wlStore.create({ ...form.value })
    await loadForecast()
    message.success('Добавлено в список желаний')
    form.value = { name: '', estimated_cost: null, category: '', frequency: 'once', purchased: false, notes: '' }
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
</style>
