<template>
  <div>
    <n-card title="Триггеры бота" style="max-width: 720px; margin: 0 auto">
      <n-text depth="3" tag="p" style="font-size: 13px; margin-bottom: 14px">
        Фразы-подсказки для классификатора Telegram-бота. По ним бот понимает, что именно вы хотите
        сделать сообщением (добавить желаемую покупку, оплатить регулярный счёт и т.д.). Встроенный
        набор подгружается в БД при первом запуске — его можно свободно дополнять и удалять, как
        любые свои фразы. Видно всем, редактирует только админ.
      </n-text>

      <n-spin :show="loading">
        <div v-for="intent in INTENT_ORDER" :key="intent" class="intent-block">
          <div class="intent-head">
            <span class="intent-label">{{ META[intent].label }}</span>
            <n-button
              v-if="dirty[intent]"
              size="tiny"
              type="primary"
              :loading="saving[intent]"
              @click="save(intent)"
            >
              Сохранить
            </n-button>
          </div>
          <n-text depth="3" tag="p" class="intent-hint">{{ META[intent].hint }}</n-text>
          <n-dynamic-tags v-model:value="phrases[intent]" @update:value="dirty[intent] = true" />
        </div>
      </n-spin>
    </n-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { NButton, NCard, NDynamicTags, NSpin, NText, useMessage } from 'naive-ui'
import { intentTriggers as api } from '@/api'

const message = useMessage()

// Render order + human-facing metadata. Phrases themselves live in the DB
// (seeded with built-ins on first backend start) and are edited as plain
// records — nothing about the defaults is mirrored here anymore.
const INTENT_ORDER = ['wishlist', 'recurring_payment', 'link_existing', 'detail_request']
const META = {
  wishlist: {
    label: '🛒 Желаемые покупки',
    hint: 'Сообщение про планируемую покупку → запись в «Желаемые покупки».',
  },
  recurring_payment: {
    label: '🔁 Регулярные платежи',
    hint: 'Оплата периодического/коммунального счёта → расход по регулярному платежу.',
  },
  link_existing: {
    label: '🔗 Привязать расход',
    hint: 'Привязать существующую запись расхода к регулярному/желаемому.',
  },
  detail_request: {
    label: '📝 Запрос на детализацию (ЗнД)',
    hint: 'Создать ЗнД и назначить его на члена семьи.',
  },
}

const loading = ref(false)
const phrases = reactive({})
const dirty = reactive({})
const saving = reactive({})

async function refresh() {
  loading.value = true
  try {
    const { data } = await api.list()
    const byIntent = Object.fromEntries((data || []).map((r) => [r.intent, r.phrases || []]))
    for (const intent of INTENT_ORDER) {
      phrases[intent] = [...(byIntent[intent] || [])]
      dirty[intent] = false
      saving[intent] = false
    }
  } catch {
    message.error('Не удалось загрузить триггеры')
  } finally {
    loading.value = false
  }
}

async function save(intent) {
  saving[intent] = true
  try {
    const { data } = await api.update(intent, phrases[intent])
    phrases[intent] = [...(data.phrases || [])]
    dirty[intent] = false
    message.success('Сохранено')
  } catch {
    message.error('Не удалось сохранить')
  } finally {
    saving[intent] = false
  }
}

onMounted(refresh)
</script>

<style scoped>
.intent-block {
  padding: 12px 0;
  border-top: 1px solid var(--n-border-color, rgba(128, 128, 128, 0.2));
}
.intent-block:first-of-type {
  border-top: none;
}
.intent-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}
.intent-label {
  font-weight: 600;
  font-size: 14px;
}
.intent-hint {
  font-size: 12px;
  margin: 0 0 8px;
}
</style>
