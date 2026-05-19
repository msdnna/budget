<template>
  <div>
    <n-card title="Импорт / Экспорт данных" style="max-width: 640px; margin: 0 auto">
      <n-space vertical size="large">
        <!-- Export block -->
        <n-card embedded style="border-radius: 8px">
          <div>
            <n-text strong style="font-size: 15px">Экспорт</n-text>
            <n-text depth="3" tag="p" style="font-size: 13px; margin: 6px 0 16px">
              Скачать JSON-снимок всей системы (пользователи с зашифрованными паролями, категории,
              иконки, доходы, расходы, прогноз, запросы на детализацию). Снимок переносим между
              инсталляциями msdnna budget.
            </n-text>
          </div>
          <n-button type="primary" :loading="exporting" @click="onExport">
            <template #icon>
              <n-icon><CloudDownloadOutline /></n-icon>
            </template>
            Скачать JSON
          </n-button>
        </n-card>

        <!-- Import block -->
        <n-card embedded style="border-radius: 8px">
          <div>
            <n-text strong style="font-size: 15px">Импорт</n-text>
            <n-text depth="3" tag="p" style="font-size: 13px; margin: 6px 0 20px">
              Загрузите JSON-снимок из другой инсталляции. По умолчанию совпадающие записи (по UUID
              либо login для пользователей) пропускаются — текущая база не повреждается. Режим
              «Перезаписать» очищает базу перед импортом, сохраняя только вашу учётную запись.
            </n-text>
          </div>

          <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 12px">
            <n-text style="font-size: 13px">Режим:</n-text>
            <n-radio-group v-model:value="mode" :disabled="importing">
              <n-radio value="merge">Добавить без перезаписи</n-radio>
              <n-radio value="replace">Перезаписать всё, кроме меня</n-radio>
            </n-radio-group>
          </div>

          <div
            class="dropzone"
            :class="{ active: isDraggingOver, error: !!parseError }"
            @dragover.prevent="isDraggingOver = true"
            @dragleave.prevent="isDraggingOver = false"
            @drop.prevent="onDrop"
          >
            <n-icon size="28" :color="palette.text3"><CloudUploadOutline /></n-icon>
            <div style="margin-top: 6px; font-size: 13px">
              Перетащите JSON или
              <a href="#" @click.prevent="fileInput?.click()">выберите файл</a>
            </div>
            <input
              ref="fileInput"
              type="file"
              accept="application/json,.json"
              style="display: none"
              @change="onFileChange"
            />
            <div v-if="loadedFileName" style="margin-top: 6px; font-size: 12px; opacity: 0.7">
              {{ loadedFileName }}
            </div>
          </div>

          <n-collapse style="margin-top: 12px">
            <n-collapse-item title="Или вставить JSON вручную" name="paste">
              <n-input
                v-model:value="pastedJson"
                type="textarea"
                :autosize="{ minRows: 6, maxRows: 14 }"
                placeholder='{"schema_version": 1, ...}'
                :disabled="importing"
              />
            </n-collapse-item>
          </n-collapse>

          <n-alert v-if="parseError" type="error" style="margin-top: 12px">
            {{ parseError }}
          </n-alert>
          <n-alert v-if="parsed && !importStats" type="info" style="margin-top: 12px">
            Снимок распознан · v{{ parsed.schema_version }} · пользователей:
            {{ parsed.users?.length || 0 }} · категорий: {{ parsed.categories?.length || 0 }} ·
            транзакций: {{ parsed.transactions?.length || 0 }} · прогноз:
            {{ parsed.wishlist?.length || 0 }}
          </n-alert>
          <n-alert v-if="importStats" type="success" style="margin-top: 12px">
            Импорт завершён ({{ importStats.mode }}) · {{ importStatsSummary }}
          </n-alert>
          <n-alert v-if="importError" type="error" style="margin-top: 12px">
            {{ importError }}
          </n-alert>

          <n-button
            type="primary"
            style="margin-top: 12px"
            :disabled="!parsed"
            :loading="importing"
            @click="onImport"
          >
            Импортировать
          </n-button>
        </n-card>
      </n-space>

      <n-alert type="warning" style="margin-top: 16px" :bordered="false">
        Этот файл — не замена бэкапа базы. Он не содержит истории уведомлений и системных
        мета-данных синхронизации. Резервное копирование MongoDB остаётся отдельной задачей.
      </n-alert>
    </n-card>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import {
  NCard,
  NButton,
  NIcon,
  NText,
  NSpace,
  NAlert,
  NCollapse,
  NCollapseItem,
  NInput,
  NRadio,
  NRadioGroup,
  useMessage,
} from 'naive-ui'
import { CloudUploadOutline, CloudDownloadOutline } from '@vicons/ionicons5'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import api from '@/api/index'

const { palette } = storeToRefs(useThemeStore())
const message = useMessage()

const exporting = ref(false)
async function onExport() {
  exporting.value = true
  try {
    const res = await api.get('/admin/export', { responseType: 'blob' })
    const cd = res.headers?.['content-disposition'] || ''
    let filename = `budget-export-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}.json`
    const m = /filename="?([^";]+)"?/i.exec(cd)
    if (m) filename = m[1]
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
    message.success('Снимок скачан')
  } catch (e) {
    message.error('Ошибка экспорта: ' + (e.response?.data?.error || e.message))
  } finally {
    exporting.value = false
  }
}

// ─── Import ────────────────────────────────────────────────────────
const mode = ref('merge')
const fileInput = ref(null)
const isDraggingOver = ref(false)
const pastedJson = ref('')
const parsed = ref(null)
const parseError = ref('')
const loadedFileName = ref('')
const importing = ref(false)
const importStats = ref(null)
const importError = ref('')

function tryParse(text, source) {
  parseError.value = ''
  parsed.value = null
  if (!text.trim()) return
  let obj
  try {
    obj = JSON.parse(text)
  } catch (e) {
    parseError.value = 'Невалидный JSON: ' + e.message
    return
  }
  if (typeof obj !== 'object' || obj === null || !Number.isInteger(obj.schema_version)) {
    parseError.value = 'Не похоже на снимок msdnna budget — нет поля schema_version'
    return
  }
  parsed.value = obj
  if (source) loadedFileName.value = source
}

function onDrop(e) {
  isDraggingOver.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) readFile(file)
}
function onFileChange(e) {
  const file = e.target.files?.[0]
  if (file) readFile(file)
}
function readFile(file) {
  const r = new FileReader()
  r.onload = () => {
    const text = String(r.result || '')
    pastedJson.value = ''
    tryParse(text, file.name)
  }
  r.onerror = () => {
    parseError.value = 'Не удалось прочитать файл'
  }
  r.readAsText(file)
}

watch(pastedJson, (v) => {
  if (v) {
    loadedFileName.value = ''
    tryParse(v, '')
  } else if (!loadedFileName.value) {
    parsed.value = null
    parseError.value = ''
  }
})

const importStatsSummary = computed(() => {
  const s = importStats.value
  if (!s) return ''
  const parts = []
  if (s.users_imported) parts.push(`пользователей: ${s.users_imported}`)
  if (s.categories_imported) parts.push(`категорий: ${s.categories_imported}`)
  if (s.icons_imported) parts.push(`иконок: ${s.icons_imported}`)
  if (s.transactions_imported) parts.push(`транзакций: ${s.transactions_imported}`)
  if (s.wishlist_imported) parts.push(`желаний: ${s.wishlist_imported}`)
  if (s.detail_requests_imported) parts.push(`запросов: ${s.detail_requests_imported}`)
  const skipped =
    (s.users_skipped || 0) +
    (s.categories_skipped || 0) +
    (s.icons_skipped || 0) +
    (s.transactions_skipped || 0) +
    (s.wishlist_skipped || 0) +
    (s.detail_requests_skipped || 0)
  if (skipped) parts.push(`пропущено: ${skipped}`)
  return parts.length ? parts.join(', ') : 'без новых записей'
})

async function onImport() {
  if (!parsed.value) return
  importing.value = true
  importError.value = ''
  importStats.value = null
  try {
    const res = await api.post('/admin/import', { mode: mode.value, snapshot: parsed.value })
    importStats.value = res.data
    message.success('Импорт завершён')
  } catch (e) {
    importError.value = e.response?.data?.error || e.message || 'Ошибка импорта'
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.dropzone {
  border: 2px dashed rgba(0, 0, 0, 0.18);
  border-radius: 8px;
  padding: 20px;
  text-align: center;
  cursor: pointer;
  transition:
    border-color 150ms,
    background 150ms;
}
.dropzone.active {
  border-color: var(--n-primary-color, #18a058);
  background: rgba(24, 160, 88, 0.06);
}
.dropzone.error {
  border-color: #ef4444;
}
</style>
