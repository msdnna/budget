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
                <div style="font-weight:600">{{ p.category }} · {{ p.amount.toLocaleString('ru-RU') }} ₽</div>
                <div style="font-size:11px; opacity:0.65">{{ p.purpose || p.description || 'без описания' }} · {{ new Date(p.date).toLocaleDateString('ru-RU') }}</div>
              </div>
              <n-tag size="small" type="warning" round>заполнить</n-tag>
            </div>
          </div>

          <n-space style="margin-bottom:12px" wrap align="center" justify="space-between">
            <n-space wrap align="center">
              <n-date-picker v-model:value="filterRange" type="daterange" clearable size="small" @update:value="applyFilters" placeholder="Фильтр по дате" />
              <n-select
                v-model:value="filterCategories"
                :options="categoryOptions"
                multiple
                clearable
                size="small"
                style="width:230px"
                placeholder="Все категории"
                :max-tag-count="1"
                to="body"
                @update:value="applyFilters"
              />
              <n-checkbox v-model:checked="showDetailed" @update:checked="applyFilters" size="small">
                <span style="font-size:12px">Показать закрытые запросы</span>
              </n-checkbox>
            </n-space>
            <n-space align="center" :size="8">
              <template v-if="!bulkMode">
                <n-button size="small" @click="enterBulkMode">Пакетное редактирование</n-button>
              </template>
              <template v-else>
                <n-text v-if="selectedIds.size" depth="2" style="font-size:12px">
                  Выбрано: {{ selectedIds.size }}
                </n-text>
                <template v-if="selectedIds.size">
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
                <n-button size="small" quaternary @click="exitBulkMode">Отмена</n-button>
              </template>
            </n-space>
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
import { ref, computed, onMounted, watch, h } from 'vue'
function toLocalDateString(ts) {
  const d = new Date(ts)
  const offset = d.getTimezoneOffset()
  return new Date(d.getTime() - offset * 60000).toISOString()
}
import { useMessage } from 'naive-ui'
import {
  NCard, NGrid, NGridItem, NForm, NFormItem, NInput, NInputNumber,
  NSelect, NDatePicker, NButton, NDataTable, NSpace, NPopconfirm, NText, NTooltip,
  NModal, NSpin, NList, NListItem, NTag, NCheckbox,
} from 'naive-ui'
import { useTransactionsStore } from '@/stores/transactions'
import { useThemeStore } from '@/stores/theme'
import { useCategoriesStore } from '@/stores/categories'
import { storeToRefs } from 'pinia'
import SplitPane from '@/components/SplitPane.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ConfirmActionButton from '@/components/ConfirmActionButton.vue'
import { users as usersApi, transactions as txApi, detailRequests as drApi } from '@/api'
import { useDetailRequestsStore } from '@/stores/detailRequests'
import { useAuthStore } from '@/stores/auth'

const store = useTransactionsStore('expenses')
const drStore = useDetailRequestsStore()
const auth = useAuthStore()
const { palette, valuesHidden, primaryColor } = storeToRefs(useThemeStore())
const expenseColor = computed(() => palette.value.expense)
const message = useMessage()
const formRef = ref(null)
const saving = ref(false)
const filterRange = ref(null)
const filterCategories = ref([])
const showDetailed = ref(false)

const form = ref({ amount: null, date: Date.now(), category: '', purpose: '', description: '' })

const catStore = useCategoriesStore()
const categoryOptions = computed(() => catStore.options('expense'))

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
  // Default categories and unsaved pending tags render normally
  if (option.is_default || !option.id) return node
  return h('div', { style: 'display:flex;align-items:center;width:100%' }, [
    h('span', { style: 'flex:1;min-width:0' }, [node]),
    h('span', {
      style: `opacity:0.55;cursor:pointer;flex-shrink:0;padding:2px 4px;margin-right:14px;display:inline-flex;align-items:center;transition:opacity .15s;color:${palette.value.text2}`,
      title: 'Удалить категорию',
      onClick: async (e) => {
        e.stopPropagation()
        try {
          await catStore.remove(option.id, 'expense')
          if (form.value.category === option.value) form.value.category = ''
          if (filterCategories.value.includes(option.value)) {
            filterCategories.value = filterCategories.value.filter(v => v !== option.value)
            applyFilters()
          }
        } catch { message.error('Не удалось удалить категорию') }
      },
      onMouseenter: e => { e.currentTarget.style.opacity = '1' },
      onMouseleave: e => { e.currentTarget.style.opacity = '0.55' },
    }, [trashIcon()]),
  ])
}

const rules = {
  amount: [{ required: true, type: 'number', message: 'Введите сумму', trigger: 'blur' }],
  date: [{ required: true, type: 'number', message: 'Выберите дату', trigger: 'change' }],
  category: [{ required: true, message: 'Выберите категорию', trigger: 'change' }],
}

async function submit() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const cat = form.value.category
    if (cat && !catStore.bySection.expense.find(c => c.name === cat)) {
      await catStore.add('expense', cat).catch(() => {})
    }
    await store.create({
      type: 'expense',
      amount: form.value.amount,
      date: toLocalDateString(form.value.date).split('T')[0],
      category: cat,
      purpose: form.value.purpose,
      description: form.value.description,
    })
    catStore.recordUse('expense', cat)
    message.success('Расход добавлен')
    form.value = { amount: null, date: Date.now(), category: '', purpose: '', description: '' }
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

const myOpenRequestParentIds = computed(() => new Set(
  drStore.items
    .filter(r => r.status === 'open' && r.assignee?.user_id === auth.user?.user_id)
    .map(r => r.parent_transaction_id)
))

function hasMyOpenRequest(row) {
  return myOpenRequestParentIds.value.has(row.id) ||
    (row.detail_request_status === 'open' &&
      drStore.items.find(r => r.id === row.detail_request_id)?.assignee?.user_id === auth.user?.user_id)
}

// Parent transactions of my open requests — fetched eagerly so the pinned
// banner can render even when the parent isn't on the current page.
const myOpenParents = ref([])
async function loadMyOpenParents() {
  const mine = drStore.items.filter(
    r => r.status === 'open' && r.assignee?.user_id === auth.user?.user_id,
  )
  const out = []
  for (const r of mine) {
    try {
      const { data } = await drApi.get(r.id)
      if (data?.parent) out.push(data.parent)
    } catch {}
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
  form.value = { amount: row.amount, date: Date.now(), category: row.category, purpose: row.purpose || '', description: row.description || '' }
  message.info('Форма заполнена по шаблону')
}

function fmtLocalDate(ts) {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function applyFilters() {
  const f = { type: 'expense', categories: filterCategories.value, includeDetailed: showDetailed.value }
  if (filterRange.value) {
    f.from = fmtLocalDate(filterRange.value[0])
    f.to = fmtLocalDate(filterRange.value[1])
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
  if (s.has(id)) s.delete(id); else s.add(id)
  selectedIds.value = s
}

const allSelectedHidden = computed(() => {
  if (!selectedIds.value.size) return false
  const sel = store.items.filter(r => selectedIds.value.has(r.id))
  return sel.length > 0 && sel.every(r => r.hidden)
})

async function bulkToggleHidden() {
  const target = !allSelectedHidden.value
  bulkBusy.value = true
  try {
    await Promise.all(Array.from(selectedIds.value).map(id => txApi.update(id, { hidden: target })))
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
    await Promise.all(Array.from(selectedIds.value).map(id => txApi.remove(id)))
    exitBulkMode()
    await store.fetch()
    message.success('Записи удалены')
  } catch (e) {
    message.error(e.message)
  } finally {
    bulkBusy.value = false
  }
}

const selectionCheckbox = (row) => {
  const sel = selectedIds.value.has(row.id)
  return h('div', {
    style: `cursor:pointer;display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;border:2px solid ${sel ? primaryColor.value : palette.value.text3};background:${sel ? primaryColor.value : 'transparent'};color:#fff;transition:background .15s,border-color .15s;box-sizing:border-box`,
    onClick: () => toggleSelect(row.id),
  }, sel ? [h('svg', {
    width: 12, height: 12, viewBox: '0 0 24 24', fill: 'none',
    stroke: 'currentColor', 'stroke-width': '3', 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
    style: 'display:block',
  }, [h('polyline', { points: '20 6 9 17 4 12' })])] : [])
}

// ── Columns ───────────────────────────────────────────────────────────────────

const columns = computed(() => [
  {
    title: '', key: 'created_by', width: 36, align: 'center',
    render: row => {
      if (bulkMode.value) return selectionCheckbox(row)
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
              options: categoryOptions.value,
              filterable: true, tag: true,
              renderOption: renderCategoryOption,
              onCreate: (val) => ({ label: val, value: val, id: null, is_default: false }),
              size: 'small', to: 'body', style: 'width:120px',
              'onUpdate:value': async (v) => {
                editCellValue.value = v
                const isNew = v && !catStore.bySection.expense.find(c => c.name === v)
                if (isNew) await catStore.add('expense', v)
              },
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
          h(NText, {
            style: `color:${expenseColor.value};font-weight:600;transition:filter .25s${valuesHidden.value ? ';filter:blur(7px);user-select:none' : ''}`
          }, { default: () => `−${row.amount.toLocaleString('ru-RU')} ₽` }),
          pencilBtn(() => startCellEdit(row.id, 'amount', row.amount)),
        ]
      })
    }
  },
  {
    title: '', key: 'actions', width: 140, align: 'right',
    render: row => {
      const buttons = [
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
      ]
      // Detail-request actions: open existing or create new (only for parent
      // expense transactions; child transactions never get a detail-request).
      if (!row.parent_id) {
        if (row.detail_request_id) {
          buttons.push(h(NTooltip, null, {
            trigger: () => h(NButton, {
              size: 'small', quaternary: true, type: 'warning',
              onClick: () => openDetailRequest(row.detail_request_id)
            }, { default: () => '⇲' }),
            default: () => row.detail_request_status === 'open' ? 'Открыть запрос на детализацию' : 'Закрытый запрос на детализацию'
          }))
        } else {
          buttons.push(h(NTooltip, null, {
            trigger: () => h(NButton, {
              size: 'small', quaternary: true, type: 'primary',
              onClick: () => startCreateDetailRequest(row)
            }, { default: () => '⇲' }),
            default: () => 'Создать запрос на детализацию'
          }))
        }
      }
      buttons.push(h(NPopconfirm, { onPositiveClick: () => store.remove(row.id) }, {
        trigger: () => h(NButton, { size: 'small', type: 'error', quaternary: true }, { default: () => '✕' }),
        default: () => 'Удалить запись?'
      }))
      return h(NSpace, { size: 2, justify: 'end', wrap: false }, { default: () => buttons })
    }
  }
])

const pagination = computed(() => ({
  page: store.page, pageSize: store.limit, itemCount: store.total, showSizePicker: false
}))

onMounted(async () => {
  store.setFilters({ type: 'expense' })
  catStore.load('expense')
  await drStore.fetchAll()
  await loadMyOpenParents()
})

watch(() => drStore.items, async () => {
  await loadMyOpenParents()
}, { deep: true })
</script>

<style scoped>
.dr-pinned-title {
  font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em;
  opacity: 0.7; margin-bottom: 6px;
}
.dr-pinned-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 4px; border-radius: 4px; cursor: pointer;
  transition: background 0.15s;
}
.dr-pinned-row:hover { background: rgba(240,160,32,0.18); }
.dr-pinned-row + .dr-pinned-row { border-top: 1px dashed rgba(240,160,32,0.25); }
</style>
