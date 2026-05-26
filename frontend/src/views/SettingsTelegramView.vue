<template>
  <div>
    <n-card title="Telegram" style="max-width: 640px; margin: 0 auto">
      <n-spin :show="loading">
        <!-- Linked: show telegram username + Unlink CTA -->
        <div v-if="status?.linked">
          <n-text depth="3" tag="p" style="font-size: 13px; margin-bottom: 12px">
            Аккаунт привязан к боту. Можно писать в свободной форме — например,
            <i>«продукты магнит 2300»</i>
            — и подтверждать кнопкой в Telegram.
          </n-text>

          <n-descriptions :column="1" size="small" bordered style="margin-bottom: 16px">
            <n-descriptions-item label="Telegram">
              <span v-if="status.telegram_username">@{{ status.telegram_username }}</span>
              <span v-else>id {{ status.telegram_user_id }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="Привязан">
              {{ formatLinkedAt(status.linked_at) }}
            </n-descriptions-item>
          </n-descriptions>

          <n-button :loading="unlinking" type="error" ghost @click="onUnlink">Отвязать</n-button>
        </div>

        <!-- Not linked, no pending code: show "generate" CTA -->
        <div v-else-if="!pendingCode">
          <n-text depth="3" tag="p" style="font-size: 13px; margin-bottom: 14px">
            Привяжите ваш аккаунт к боту, чтобы добавлять транзакции из Telegram в свободной форме.
            Бот распознаёт сумму, категорию и контрагента.
          </n-text>
          <ol style="font-size: 13px; padding-left: 20px; margin: 0 0 16px; line-height: 1.7">
            <li>
              Найдите бота в Telegram:
              <a v-if="botLink" :href="botLink" target="_blank" rel="noopener">
                @{{ botUsername }}
              </a>
              <span v-else>в инструкции у администратора</span>
            </li>
            <li>Нажмите «Сгенерировать код» ниже.</li>
            <li>
              В чате с ботом отправьте команду
              <code>/link &lt;КОД&gt;</code>
              (код покажем здесь).
            </li>
          </ol>
          <n-button :loading="generating" type="primary" @click="onGenerate">
            Сгенерировать код
          </n-button>
        </div>

        <!-- Pending: show the code + countdown -->
        <div v-else>
          <n-text depth="3" tag="p" style="font-size: 13px; margin-bottom: 10px">
            Отправьте боту команду:
          </n-text>
          <n-card embedded style="text-align: center; padding: 16px; margin-bottom: 12px">
            <div style="font-size: 13px; color: var(--n-text-color-3); margin-bottom: 4px">
              /link
            </div>
            <div
              style="
                font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                font-size: 32px;
                letter-spacing: 4px;
                font-weight: 600;
                user-select: all;
              "
            >
              {{ pendingCode }}
            </div>
            <div style="font-size: 12px; color: var(--n-text-color-3); margin-top: 8px">
              Истекает через {{ countdownLabel }}
            </div>
          </n-card>

          <n-space>
            <n-button @click="copyCode">Скопировать</n-button>
            <n-button :loading="generating" @click="onGenerate">Новый код</n-button>
            <n-button quaternary @click="pendingCode = ''">Отмена</n-button>
          </n-space>

          <n-text depth="3" tag="p" style="font-size: 12px; margin-top: 18px">
            После того как бот ответит «✅ Привязка выполнена», обновите эту страницу — появится
            отвязка.
          </n-text>
        </div>
      </n-spin>
    </n-card>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NSpace,
  NSpin,
  NText,
  useMessage,
} from 'naive-ui'
import { telegram as telegramApi } from '@/api'

// Bot handle — kept here rather than in a separate config because the app
// is a single-tenant family install. Override via env (VITE_TG_BOT) if you
// fork. Falsy → hide the deep-link, instructions still work.
const botUsername = import.meta.env.VITE_TG_BOT || 'msdnna_budget_bot'
const botLink = botUsername ? `https://t.me/${botUsername}` : ''

const message = useMessage()

const loading = ref(false)
const generating = ref(false)
const unlinking = ref(false)

const status = ref(null) // { linked, telegram_user_id?, telegram_username?, linked_at? }
const pendingCode = ref('')
const pendingExpiresAt = ref(null) // Date

const countdownSec = ref(0)
let countdownTimer = null

const countdownLabel = computed(() => {
  if (countdownSec.value <= 0) return 'истёк'
  const m = Math.floor(countdownSec.value / 60)
  const s = countdownSec.value % 60
  return `${m}:${String(s).padStart(2, '0')}`
})

async function refreshStatus() {
  loading.value = true
  try {
    const { data } = await telegramApi.status()
    status.value = data
  } catch (e) {
    // 401 is handled by the global interceptor; anything else is logged.
    if (e?.response?.status !== 401) {
      message.error('Не удалось загрузить статус привязки')
    }
  } finally {
    loading.value = false
  }
}

function startCountdown(expiresAtISO) {
  pendingExpiresAt.value = new Date(expiresAtISO)
  const tick = () => {
    const ms = pendingExpiresAt.value - new Date()
    countdownSec.value = Math.max(0, Math.floor(ms / 1000))
    if (countdownSec.value <= 0) {
      // Code rotted; drop the pending UI so the user has to regenerate
      // (matches server-side state — UpsertCode wipes expired records).
      pendingCode.value = ''
      stopCountdown()
    }
  }
  stopCountdown()
  tick()
  countdownTimer = setInterval(tick, 1000)
}

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

async function onGenerate() {
  generating.value = true
  try {
    const { data } = await telegramApi.init()
    pendingCode.value = data.code
    startCountdown(data.expires_at)
  } catch {
    message.error('Не удалось сгенерировать код')
  } finally {
    generating.value = false
  }
}

async function onUnlink() {
  unlinking.value = true
  try {
    await telegramApi.unlink()
    status.value = { linked: false }
    pendingCode.value = ''
    message.success('Аккаунт отвязан')
  } catch {
    message.error('Не удалось отвязать')
  } finally {
    unlinking.value = false
  }
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(`/link ${pendingCode.value}`)
    message.success('Скопировано')
  } catch {
    message.warning('Скопируйте вручную')
  }
}

function formatLinkedAt(iso) {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString('ru-RU')
  } catch {
    return iso
  }
}

onMounted(refreshStatus)
onUnmounted(stopCountdown)
</script>
