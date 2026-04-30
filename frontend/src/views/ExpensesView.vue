<template>
  <div>
    <SplitPane storage-key="expenses-split" :default-left="45" :min-left="20" :max-left="75">
      <template #left>
        <n-card title="Добавить расход">
          <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
            <n-grid :cols="2" :x-gap="12" :item-responsive="true">
              <n-grid-item span="2 s:1">
                <n-form-item label="Сумма (₽)" path="amount">
                  <n-input-number v-model:value="form.amount" :min="0.01" :precision="2" style="width:100%" placeholder="0.00" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2 s:1">
                <n-form-item label="Дата" path="date">
                  <n-date-picker v-model:value="form.date" type="date" style="width:100%" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2 s:1">
                <n-form-item label="Категория" path="category">
                  <n-select v-model:value="form.category" :options="categoryOptions" placeholder="Выберите категорию" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2 s:1">
                <n-form-item label="Назначение">
                  <n-input v-model:value="form.purpose" placeholder="Куда потрачено" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item span="2">
                <n-form-item label="Описание">
                  <n-input v-model:value="form.description" placeholder="Дополнительно (необязательно)" />
                </n-form-item>
              </n-grid-item>
            </n-grid>
            <n-button type="primary" :loading="saving" @click="submit" block>
              Добавить расход
            </n-button>
          </n-form>
        </n-card>
      </template>

      <template #right>
        <n-card title="История расходов">
          <n-space style="margin-bottom:12px" wrap>
            <n-date-picker v-model:value="filterRange" type="daterange" clearable size="small" @update:value="applyFilters" placeholder="Фильтр по дате" />
            <n-select v-model:value="filterCategory" :options="[{label:'Все',value:''},...categoryOptions]" size="small" style="width:150px" @update:value="applyFilters" />
          </n-space>
          <n-data-table
            :columns="columns"
            :data="store.items"
            :loading="store.loading"
            :pagination="pagination"
            :row-props="getRowProps"
            remote
            @update:page="store.setPage"
            :scroll-x="960"
          />
        </n-card>
      </template>
    </SplitPane>

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
function toLocalDateString(ts) {
  const d = new Date(ts)
  const offset = d.getTimezoneOffset()
  return new Date(d.getTime() - offset * 60000).toISOString()
}
import { useMessage } from 'naive-ui'
import {
  NCard, NGrid, NGridItem, NForm, NFormItem, NInput, NInputNumber,
  NSelect, NDatePicker, NButton, NDataTable, NSpace, NPopconfirm, NText, NTooltip,
  NModal, NSpin, NList, NListItem
} from 'naive-ui'
import { useTransactionsStore } from '@/stores/transactions'
import { useThemeStore } from '@/stores/theme'
import { storeToRefs } from 'pinia'
import SplitPane from '@/components/SplitPane.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { users as usersApi } from '@/api'

const store = useTransactionsStore()
const { palette } = storeToRefs(useThemeStore())
const expenseColor = computed(() => palette.value.expense)
const message = useMessage()
const formRef = ref(null)
const saving = ref(false)
const filterRange = ref(null)
const filterCategory = ref('')

const form = ref({ amount: null, date: Date.now(), category: '', purpose: '', description: '' })

const categoryOptions = [
  'Продукты','Транспорт','Жильё/ЖКХ','Рестораны','Развлечения',
  'Здоровье','Образование','Одежда','Электроника','Путешествия',
  'Связь','Красота','Спорт','Прочее'
].map(v => ({ label: v, value: v }))

const rules = {
  amount: [{ required: true, type: 'number', message: 'Введите сумму', trigger: 'blur' }],
  date: [{ required: true, type: 'number', message: 'Выберите дату', trigger: 'change' }],
  category: [{ required: true, message: 'Выберите категорию', trigger: 'change' }],
}

async function submit() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    await store.create({
      type: 'expense',
      amount: form.value.amount,
      date: toLocalDateString(form.value.date).split('T')[0],
      category: form.value.category,
      purpose: form.value.purpose,
      description: form.value.description,
    })
    message.success('Расход добавлен')
    form.value = { amount: null, date: Date.now(), category: '', purpose: '', description: '' }
  } catch (e) {
    message.error(e.message)
  } finally {
    saving.value = false
  }
}

function getRowProps(row) {
  return { style: row.hidden ? 'opacity:0.4' : '' }
}

function fillFromTemplate(row) {
  form.value = { amount: row.amount, date: Date.now(), category: row.category, purpose: row.purpose || '', description: row.description || '' }
  message.info('Форма заполнена по шаблону')
}

function applyFilters() {
  const f = { type: 'expense', category: filterCategory.value }
  if (filterRange.value) {
    f.from = new Date(filterRange.value[0]).toISOString().split('T')[0]
    f.to = new Date(filterRange.value[1]).toISOString().split('T')[0]
  }
  store.setFilters(f)
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
      created_by: { user_id: user.user_id, display_name: user.display_name, avatar_url: user.avatar_url || '' }
    })
    message.success(`Автор: ${user.display_name}`)
  } catch (e) {
    message.error(e.message)
  } finally {
    showReassign.value = false
  }
}

// ── Render helpers ────────────────────────────────────────────────────────────

const pencilSvg = () => h('svg', {
  width: 12, height: 12, viewBox: '0 0 24 24', fill: 'none',
  stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
  style: 'display:block'
}, [
  h('path', { d: 'M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7' }),
  h('path', { d: 'M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z' }),
])

const pencilBtn = (onClick) => h('span', {
  style: 'opacity:0.28;cursor:pointer;display:inline-flex;align-items:center;margin-left:3px;vertical-align:middle;transition:opacity .15s;color:inherit',
  onMouseenter: e => { e.currentTarget.style.opacity = '0.72' },
  onMouseleave: e => { e.currentTarget.style.opacity = '0.28' },
  onClick,
}, [pencilSvg()])

const okBtn = (onClick) => h(NButton, {
  size: 'tiny', type: 'primary',
  style: 'padding:0 4px;min-width:22px;height:22px',
  onClick,
}, { default: () => '✓' })

const cancelBtn = (onClick) => h(NButton, {
  size: 'tiny',
  style: 'padding:0 4px;min-width:22px;height:22px',
  onClick,
}, { default: () => '✗' })

const userPlaceholder = (onClick) => h('div', {
  style: 'cursor:pointer;display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;border:1px dashed currentColor;opacity:0.35;transition:opacity .15s',
  onMouseenter: e => { e.currentTarget.style.opacity = '0.7' },
  onMouseleave: e => { e.currentTarget.style.opacity = '0.35' },
  onClick,
}, [
  h('svg', { width: 12, height: 12, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2', style: 'display:block' }, [
    h('path', { d: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2' }),
    h('circle', { cx: 12, cy: 7, r: 4 }),
  ])
])

// ── Columns ───────────────────────────────────────────────────────────────────

const columns = [
  {
    title: '', key: 'created_by', width: 36, align: 'center',
    render: row => {
      if (!row.created_by) {
        return h(NTooltip, null, {
          trigger: () => userPlaceholder(() => openReassign(row)),
          default: () => 'Назначить автора',
        })
      }
      return h(NTooltip, null, {
        trigger: () => h('div', {
          style: 'cursor:pointer',
          onClick: () => openReassign(row)
        }, h(UserAvatar, { displayName: row.created_by.display_name, avatarUrl: row.created_by.avatar_url || '', size: 24 })),
        default: () => `${row.created_by.display_name} · нажмите для смены`,
      })
    }
  },
  {
    title: 'Дата', key: 'date', width: 100,
    render: row => new Date(row.date).toLocaleDateString('ru-RU')
  },
  {
    title: 'Категория', key: 'category', width: 150,
    render: row => {
      if (isEditing(row.id, 'category')) {
        return h(NSpace, { size: 2, wrap: false, align: 'center' }, {
          default: () => [
            h(NSelect, {
              value: editCellValue.value,
              options: categoryOptions,
              size: 'small', to: 'body', style: 'width:120px',
              'onUpdate:value': v => { editCellValue.value = v },
            }),
            okBtn(() => confirmCellEdit(row, 'category')),
            cancelBtn(cancelCellEdit),
          ]
        })
      }
      return h(NSpace, { size: 2, wrap: false, align: 'center' }, {
        default: () => [
          h('span', {}, row.category),
          pencilBtn(() => startCellEdit(row.id, 'category', row.category)),
        ]
      })
    }
  },
  {
    title: 'Назначение', key: 'purpose', width: 150,
    render: row => {
      if (isEditing(row.id, 'purpose')) {
        return h(NSpace, { size: 2, wrap: false, align: 'center' }, {
          default: () => [
            h(NInput, {
              value: editCellValue.value,
              size: 'small', style: 'width:110px',
              'onUpdate:value': v => { editCellValue.value = v },
              onKeydown: e => { if (e.key === 'Enter') confirmCellEdit(row, 'purpose'); if (e.key === 'Escape') cancelCellEdit() },
            }),
            okBtn(() => confirmCellEdit(row, 'purpose')),
            cancelBtn(cancelCellEdit),
          ]
        })
      }
      return h(NSpace, { size: 2, wrap: false, align: 'center' }, {
        default: () => [
          h('span', {}, row.purpose || ''),
          pencilBtn(() => startCellEdit(row.id, 'purpose', row.purpose || '')),
        ]
      })
    }
  },
  {
    title: 'Описание', key: 'description',
    render: row => {
      if (isEditing(row.id, 'description')) {
        return h(NSpace, { size: 2, wrap: false, align: 'center' }, {
          default: () => [
            h(NInput, {
              value: editCellValue.value,
              size: 'small', style: 'min-width:120px',
              'onUpdate:value': v => { editCellValue.value = v },
              onKeydown: e => { if (e.key === 'Enter') confirmCellEdit(row, 'description'); if (e.key === 'Escape') cancelCellEdit() },
            }),
            okBtn(() => confirmCellEdit(row, 'description')),
            cancelBtn(cancelCellEdit),
          ]
        })
      }
      return h(NSpace, { size: 2, wrap: false, align: 'center' }, {
        default: () => [
          h('span', {}, row.description || ''),
          pencilBtn(() => startCellEdit(row.id, 'description', row.description || '')),
        ]
      })
    }
  },
  {
    title: 'Сумма', key: 'amount', width: 165, align: 'right',
    render: row => {
      if (isEditing(row.id, 'amount')) {
        return h(NSpace, { size: 2, wrap: false, align: 'center', justify: 'end' }, {
          default: () => [
            h(NInputNumber, {
              value: editCellValue.value,
              min: 0.01, precision: 2, size: 'small', style: 'width:100px',
              'onUpdate:value': v => { editCellValue.value = v },
              onKeydown: e => { if (e.key === 'Enter') confirmCellEdit(row, 'amount'); if (e.key === 'Escape') cancelCellEdit() },
            }),
            okBtn(() => confirmCellEdit(row, 'amount')),
            cancelBtn(cancelCellEdit),
          ]
        })
      }
      return h(NSpace, { size: 2, wrap: false, align: 'center', justify: 'end' }, {
        default: () => [
          h(NText, { style: `color:${expenseColor.value};font-weight:600` },
            { default: () => `−${row.amount.toLocaleString('ru-RU')} ₽` }),
          pencilBtn(() => startCellEdit(row.id, 'amount', row.amount)),
        ]
      })
    }
  },
  {
    title: '', key: 'actions', width: 100, align: 'center',
    render: row => h(NSpace, { size: 2, justify: 'center', wrap: false }, {
      default: () => [
        h(NTooltip, null, {
          trigger: () => h(NButton, {
            size: 'small', quaternary: true,
            type: row.hidden ? 'warning' : 'default',
            onClick: () => store.toggle(row.id, !row.hidden)
          }, { default: () => row.hidden ? '●' : '○' }),
          default: () => row.hidden ? 'Показать' : 'Скрыть'
        }),
        h(NTooltip, null, {
          trigger: () => h(NButton, {
            size: 'small', quaternary: true, type: 'info',
            onClick: () => fillFromTemplate(row)
          }, { default: () => '+' }),
          default: () => 'Добавить как шаблон'
        }),
        h(NPopconfirm, { onPositiveClick: () => store.remove(row.id) }, {
          trigger: () => h(NButton, { size: 'small', type: 'error', quaternary: true }, { default: () => '✕' }),
          default: () => 'Удалить запись?'
        })
      ]
    })
  }
]

const pagination = computed(() => ({
  page: store.page, pageSize: store.limit, itemCount: store.total, showSizePicker: false
}))

onMounted(() => {
  store.setFilters({ type: 'expense' })
})
</script>
