<template>
  <div class="setup-wizard">
    <div class="setup-wizard-card">
      <div class="setup-wizard-header">
        <MbLogo :color="primaryColor" :size="36" aria-label="msdnna budget" />
        <div class="setup-wizard-title">
          <h2>Первый запуск</h2>
          <n-text depth="3" style="font-size: 13px">
            Шаг {{ step }} из 2 — {{ step === 1 ? 'создание администратора' : 'импорт данных' }}
          </n-text>
        </div>
      </div>

      <n-steps :current="step" size="small" style="margin-bottom: 24px">
        <n-step title="Администратор" />
        <n-step title="Импорт данных" />
      </n-steps>

      <!-- Step 1 — admin credentials -->
      <template v-if="step === 1">
        <n-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-placement="top"
          @keydown.enter="submitStep1"
        >
          <n-form-item label="Отображаемое имя" path="display_name">
            <n-input
              v-model:value="form.display_name"
              placeholder="Иван Иванов"
              :disabled="loading"
              autocomplete="name"
            />
          </n-form-item>
          <n-form-item label="Логин" path="login">
            <n-input
              v-model:value="form.login"
              placeholder="admin"
              :disabled="loading"
              autocomplete="username"
            />
          </n-form-item>
          <n-form-item label="Пароль" path="password">
            <n-input
              v-model:value="form.password"
              type="password"
              show-password-on="click"
              :disabled="loading"
              autocomplete="new-password"
            />
          </n-form-item>
          <div v-if="form.password" class="pw-strength">
            <div class="pw-strength-bar">
              <div
                class="pw-strength-fill"
                :style="{ width: pwStrength.pct + '%', background: pwStrength.color }"
              />
            </div>
            <n-text :style="{ color: pwStrength.color, fontSize: '12px' }">
              {{ pwStrength.label }}
            </n-text>
          </div>
          <n-form-item label="Повторите пароль" path="password_confirm">
            <n-input
              v-model:value="form.password_confirm"
              type="password"
              show-password-on="click"
              :disabled="loading"
              autocomplete="new-password"
            />
          </n-form-item>

          <n-alert v-if="errorMsg" type="error" style="margin-bottom: 12px">
            {{ errorMsg }}
          </n-alert>

          <n-button type="primary" block :loading="loading" @click="submitStep1">
            Создать администратора
          </n-button>
        </n-form>
      </template>

      <!-- Step 2 — optional import -->
      <template v-else>
        <n-text depth="2" style="display: block; margin-bottom: 16px; font-size: 13px">
          Можно загрузить JSON-снимок из другой инсталляции — пользователи, категории, доходы,
          расходы и прогноз. Шаг необязателен.
        </n-text>

        <div
          class="dropzone"
          :class="{ active: isDraggingOver, error: !!parseError }"
          @dragover.prevent="isDraggingOver = true"
          @dragleave.prevent="isDraggingOver = false"
          @drop.prevent="onDrop"
        >
          <n-icon size="32" :color="palette.text3"><CloudUploadOutline /></n-icon>
          <div style="margin-top: 8px; font-size: 14px">
            Перетащите JSON-файл или
            <a href="#" @click.prevent="fileInput?.click()">выберите его</a>
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
          <n-collapse-item title="Или вставьте JSON вручную" name="paste">
            <n-input
              v-model:value="pastedJson"
              type="textarea"
              :autosize="{ minRows: 6, maxRows: 14 }"
              placeholder='{"schema_version": 1, ...}'
              :disabled="loading"
            />
          </n-collapse-item>
        </n-collapse>

        <n-alert v-if="parseError" type="error" style="margin-top: 12px">
          {{ parseError }}
        </n-alert>
        <n-alert v-if="parsed" type="success" style="margin-top: 12px">
          Снимок распознан · v{{ parsed.schema_version }} · пользователей:
          {{ parsed.users?.length || 0 }} · категорий: {{ parsed.categories?.length || 0 }} ·
          транзакций: {{ parsed.transactions?.length || 0 }}
        </n-alert>
        <n-alert v-if="importStats" type="success" style="margin-top: 12px">
          Импорт завершён · {{ importStatsSummary }}
        </n-alert>
        <n-alert v-if="importError" type="error" style="margin-top: 12px">
          {{ importError }}
        </n-alert>

        <div class="step2-actions">
          <n-button :disabled="loading" @click="finishWithoutImport">Пропустить</n-button>
          <n-button type="primary" :disabled="!parsed" :loading="loading" @click="submitImport">
            Импортировать и продолжить
          </n-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import {
  NSteps,
  NStep,
  NForm,
  NFormItem,
  NInput,
  NButton,
  NAlert,
  NText,
  NIcon,
  NCollapse,
  NCollapseItem,
  useMessage,
} from 'naive-ui'
import { CloudUploadOutline } from '@vicons/ionicons5'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import MbLogo from '@/components/MbLogo.vue'
import api from '@/api/index'

const emit = defineEmits(['done'])

const themeStore = useThemeStore()
const { primaryColor, palette } = storeToRefs(themeStore)
const auth = useAuthStore()
const message = useMessage()

const step = ref(1)
const loading = ref(false)
const errorMsg = ref('')
const formRef = ref(null)
const form = reactive({
  display_name: '',
  login: '',
  password: '',
  password_confirm: '',
})

const rules = {
  display_name: [{ required: true, message: 'Введите имя', trigger: 'blur' }],
  login: [{ required: true, message: 'Введите логин', trigger: 'blur' }],
  password: [
    { required: true, message: 'Введите пароль', trigger: 'blur' },
    { min: 4, message: 'Минимум 4 символа', trigger: 'blur' },
  ],
  password_confirm: [
    { required: true, message: 'Повторите пароль', trigger: 'blur' },
    {
      validator: (_r, v) => v === form.password,
      message: 'Пароли не совпадают',
      trigger: 'blur',
    },
  ],
}

// Visual-only password strength meter — never blocks submission. Scores
// 0–4 (length / classes used) so a weak password still goes through; we
// just colour the bar red.
const pwStrength = computed(() => {
  const p = form.password || ''
  let score = 0
  if (p.length >= 8) score++
  if (p.length >= 12) score++
  if (/[A-Z]/.test(p) && /[a-z]/.test(p)) score++
  if (/\d/.test(p)) score++
  if (/[^A-Za-z0-9]/.test(p)) score++
  const map = [
    { pct: 15, label: 'Слабый', color: '#EF4444' },
    { pct: 30, label: 'Слабый', color: '#EF4444' },
    { pct: 50, label: 'Средний', color: '#F59E0B' },
    { pct: 75, label: 'Хороший', color: '#22C55E' },
    { pct: 100, label: 'Сильный', color: '#16A34A' },
    { pct: 100, label: 'Сильный', color: '#16A34A' },
  ]
  return map[score]
})

async function submitStep1() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await api.post('/setup/init', {
      login: form.login.trim(),
      password: form.password,
      display_name: form.display_name.trim(),
    })
    auth.setAuth(res.data)
    step.value = 2
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || 'Ошибка'
  } finally {
    loading.value = false
  }
}

// ─── Step 2: import ────────────────────────────────────────────────
const fileInput = ref(null)
const isDraggingOver = ref(false)
const pastedJson = ref('')
const parsed = ref(null)
const parseError = ref('')
const importStats = ref(null)
const importError = ref('')
const loadedFileName = ref('')

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

// Re-parse pasted text on debounce-less change — payload is local, cost
// is trivial.
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
  return parts.length ? parts.join(', ') : 'без новых записей'
})

async function submitImport() {
  if (!parsed.value) return
  loading.value = true
  importError.value = ''
  importStats.value = null
  try {
    const res = await api.post('/admin/import', { mode: 'merge', snapshot: parsed.value })
    importStats.value = res.data
    message.success('Импорт завершён')
    // Дать пользователю секунду посмотреть статистику, потом выйти.
    setTimeout(() => emit('done'), 1200)
  } catch (e) {
    importError.value = e.response?.data?.error || e.message || 'Ошибка импорта'
  } finally {
    loading.value = false
  }
}

function finishWithoutImport() {
  emit('done')
}
</script>

<style scoped>
.setup-wizard {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: var(--app-bg, #f5f7fa);
}
.setup-wizard-card {
  width: 100%;
  max-width: 480px;
  background: var(--card-bg, #fff);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
  padding: 28px 32px;
}
.setup-wizard-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}
.setup-wizard-title h2 {
  margin: 0 0 2px;
  font-size: 18px;
}
.pw-strength {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: -8px 0 12px;
}
.pw-strength-bar {
  flex: 1;
  height: 4px;
  background: rgba(0, 0, 0, 0.08);
  border-radius: 2px;
  overflow: hidden;
}
.pw-strength-fill {
  height: 100%;
  transition:
    width 200ms,
    background 200ms;
}
.dropzone {
  border: 2px dashed rgba(0, 0, 0, 0.18);
  border-radius: 8px;
  padding: 24px;
  text-align: center;
  transition:
    border-color 150ms,
    background 150ms;
  cursor: pointer;
}
.dropzone.active {
  border-color: var(--n-primary-color, #18a058);
  background: rgba(24, 160, 88, 0.06);
}
.dropzone.error {
  border-color: #ef4444;
}
.step2-actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
}
</style>
