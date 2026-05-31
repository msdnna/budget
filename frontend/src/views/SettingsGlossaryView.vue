<template>
  <div>
    <n-card title="Глоссарий" style="max-width: 720px; margin: 0 auto">
      <n-text depth="3" tag="p" style="font-size: 13px; margin-bottom: 14px">
        Общесемейный словарь «термин → значение». Бот подкидывает все записи в системный промпт LLM,
        чтобы модель сразу раскрывала сокращения, прозвища и личный жаргон. Видно всем, редактирует
        только админ.
      </n-text>

      <n-spin :show="loading">
        <n-table size="small" :bordered="false" :single-line="false">
          <thead>
            <tr>
              <th style="width: 28%">Термин</th>
              <th>Значение</th>
              <th style="width: 100px; text-align: right">Действия</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="items.length === 0">
              <td colspan="3" style="text-align: center; color: var(--n-text-color-3)">
                Записей пока нет
              </td>
            </tr>
            <tr v-for="row in items" :key="row.id">
              <td>
                <n-input
                  v-model:value="row._term"
                  size="small"
                  placeholder="напр. магаз"
                  @update:value="markDirty(row)"
                />
              </td>
              <td>
                <n-input
                  v-model:value="row._meaning"
                  size="small"
                  placeholder="напр. магазин"
                  @update:value="markDirty(row)"
                />
              </td>
              <td style="text-align: right">
                <n-button
                  v-if="row._dirty"
                  size="tiny"
                  type="primary"
                  :loading="row._saving"
                  @click="save(row)"
                >
                  Сохранить
                </n-button>
                <n-popconfirm
                  positive-text="Удалить"
                  negative-text="Отмена"
                  @positive-click="remove(row)"
                >
                  <template #trigger>
                    <n-button size="small" quaternary type="error" :title="`Удалить «${row.term}»`">
                      <template #icon>
                        <n-icon :component="TrashOutline" />
                      </template>
                    </n-button>
                  </template>
                  Удалить «{{ row.term }}»?
                </n-popconfirm>
              </td>
            </tr>
          </tbody>
        </n-table>

        <div
          style="
            display: flex;
            gap: 8px;
            align-items: flex-start;
            margin-top: 16px;
            flex-wrap: wrap;
          "
        >
          <n-input
            v-model:value="newTerm"
            placeholder="Новый термин"
            size="small"
            style="flex: 1; min-width: 140px"
            @keydown.enter="create"
          />
          <n-input
            v-model:value="newMeaning"
            placeholder="Значение"
            size="small"
            style="flex: 2; min-width: 200px"
            @keydown.enter="create"
          />
          <n-button
            type="primary"
            size="small"
            :loading="creating"
            :disabled="!newTerm.trim() || !newMeaning.trim()"
            @click="create"
          >
            Добавить
          </n-button>
        </div>
      </n-spin>
    </n-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NIcon,
  NInput,
  NPopconfirm,
  NSpin,
  NTable,
  NText,
  useMessage,
} from 'naive-ui'
import { TrashOutline } from '@vicons/ionicons5'
import { glossary as glossaryApi } from '@/api'

const message = useMessage()

const loading = ref(false)
const creating = ref(false)
const items = ref([])
const newTerm = ref('')
const newMeaning = ref('')

// Edit-state lives on the row itself (`_term`, `_meaning`, `_dirty`,
// `_saving`) so the table can be inline-edited without a separate modal.
// Server fields (`term`, `meaning`) are preserved as the baseline for
// dirty-checks and rolling back on failure.
function attachEditState(row) {
  return {
    ...row,
    _term: row.term,
    _meaning: row.meaning,
    _dirty: false,
    _saving: false,
  }
}

function markDirty(row) {
  row._dirty = row._term !== row.term || row._meaning !== row.meaning
}

async function refresh() {
  loading.value = true
  try {
    const { data } = await glossaryApi.list()
    items.value = (data || []).map(attachEditState)
  } catch {
    message.error('Не удалось загрузить глоссарий')
  } finally {
    loading.value = false
  }
}

async function create() {
  const term = newTerm.value.trim()
  const meaning = newMeaning.value.trim()
  if (!term || !meaning) return
  creating.value = true
  try {
    const { data } = await glossaryApi.create(term, meaning)
    items.value = [...items.value, attachEditState(data)].sort((a, b) =>
      a.term.localeCompare(b.term, 'ru'),
    )
    newTerm.value = ''
    newMeaning.value = ''
  } catch (e) {
    if (e?.response?.status === 409) {
      message.warning('Такой термин уже есть')
    } else {
      message.error('Не удалось добавить')
    }
  } finally {
    creating.value = false
  }
}

async function save(row) {
  row._saving = true
  try {
    const patch = {}
    if (row._term !== row.term) patch.term = row._term.trim()
    if (row._meaning !== row.meaning) patch.meaning = row._meaning.trim()
    const { data } = await glossaryApi.update(row.id, patch)
    Object.assign(row, attachEditState(data))
    message.success('Сохранено')
  } catch (e) {
    if (e?.response?.status === 409) {
      message.warning('Такой термин уже есть')
    } else {
      message.error('Не удалось сохранить')
    }
  } finally {
    row._saving = false
  }
}

async function remove(row) {
  try {
    await glossaryApi.remove(row.id)
    items.value = items.value.filter((r) => r.id !== row.id)
  } catch {
    message.error('Не удалось удалить')
  }
}

onMounted(refresh)
</script>
