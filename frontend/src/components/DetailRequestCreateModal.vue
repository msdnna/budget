<template>
  <n-modal
    :show="show"
    preset="card"
    title="Создать запрос на детализацию"
    style="max-width: 420px"
    @update:show="(v) => !v && $emit('close')"
  >
    <div
      v-if="transaction"
      style="
        margin-bottom: 12px;
        padding: 10px 12px;
        background: var(--surface-alt);
        border-radius: 6px;
        border: 1px solid var(--border);
      "
    >
      <div style="font-size: 12px; opacity: 0.7">Транзакция</div>
      <div style="font-weight: 600; margin-top: 2px">
        {{ transaction.category }} · {{ transaction.amount.toLocaleString('ru-RU') }} ₽
      </div>
      <div v-if="transaction.purpose" style="font-size: 12px; opacity: 0.7">
        {{ transaction.purpose }}
      </div>
    </div>

    <div style="font-size: 12px; opacity: 0.7; margin-bottom: 6px">Кому назначить</div>
    <n-spin :show="loading">
      <n-list hoverable clickable bordered>
        <n-list-item v-for="u in users" :key="u.user_id" @click="pick(u)">
          <n-space align="center">
            <UserAvatar :displayName="u.display_name" :avatarUrl="u.avatar_url || ''" :size="28" />
            <n-text>{{ u.display_name }}</n-text>
            <n-tag v-if="u.user_id === auth.user?.user_id" size="small" round>я</n-tag>
          </n-space>
        </n-list-item>
      </n-list>
    </n-spin>

    <template #footer>
      <n-space justify="end">
        <n-button @click="$emit('close')">Отмена</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import { NModal, NSpin, NList, NListItem, NSpace, NText, NButton, NTag, useMessage } from 'naive-ui'
import { users as usersApi, detailRequests as api } from '@/api'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'

const props = defineProps({
  show: Boolean,
  transaction: Object,
})
const emit = defineEmits(['close', 'created'])

const auth = useAuthStore()
const message = useMessage()
const users = ref([])
const loading = ref(false)
const busy = ref(false)

async function loadUsers() {
  if (users.value.length) return
  loading.value = true
  try {
    const { data } = await usersApi.list()
    users.value = data
  } finally {
    loading.value = false
  }
}

async function pick(user) {
  if (busy.value || !props.transaction) return
  busy.value = true
  try {
    const { data } = await api.create({
      transaction_id: props.transaction.id,
      assignee_id: user.user_id,
    })
    message.success('Запрос на детализацию создан')
    emit('created', data)
    emit('close')
  } catch (e) {
    message.error(e.message)
  } finally {
    busy.value = false
  }
}

watch(
  () => props.show,
  (s) => {
    if (s) loadUsers()
  },
)
</script>
