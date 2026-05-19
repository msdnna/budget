<template>
  <template v-if="isMobile">
    <n-tooltip trigger="hover" placement="bottom">
      <template #trigger>
        <n-button quaternary circle size="small" @click="show = true">
          <template #icon>
            <n-badge :value="badgeCount" :max="9" :offset="[2, -2]" :show="badgeCount > 0">
              <n-icon size="18"><NotificationsOutline /></n-icon>
            </n-badge>
          </template>
        </n-button>
      </template>
      Уведомления
    </n-tooltip>
    <n-modal
      v-model:show="show"
      preset="card"
      title="Уведомления"
      :style="{ width: 'min(420px, calc(100vw - 32px))' }"
    >
      <NotificationsList :items="store.items" @read-all="store.markAllRead" />
    </n-modal>
  </template>

  <n-popover
    v-else
    trigger="click"
    placement="bottom-end"
    :show-arrow="false"
    @update:show="onPopoverToggle"
  >
    <template #trigger>
      <n-tooltip trigger="hover" placement="bottom">
        <template #trigger>
          <n-button quaternary circle size="small">
            <template #icon>
              <n-badge :value="badgeCount" :max="9" :offset="[2, -2]" :show="badgeCount > 0">
                <n-icon size="18"><NotificationsOutline /></n-icon>
              </n-badge>
            </template>
          </n-button>
        </template>
        Уведомления
      </n-tooltip>
    </template>
    <div class="notif-popover">
      <div class="notif-popover-title" :style="{ color: palette.text3 }">Уведомления</div>
      <NotificationsList :items="store.items" @read-all="store.markAllRead" />
    </div>
  </n-popover>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { NPopover, NModal, NButton, NIcon, NBadge, NTooltip } from 'naive-ui'
import { NotificationsOutline } from '@vicons/ionicons5'
import { useNotificationsStore } from '@/stores/notifications'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { storeToRefs } from 'pinia'
import NotificationsList from './NotificationsList.vue'

const store = useNotificationsStore()
const auth = useAuthStore()
const { palette } = storeToRefs(useThemeStore())
const show = ref(false)

const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
const isMobile = computed(() => windowWidth.value < 768)
function onWinResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

const badgeCount = computed(() => store.unreadCount)

// Poll every 60s while the tab is visible — cheap (single index lookup),
// keeps the badge fresh between user-initiated CRUD on transactions.
let pollHandle = null
function startPolling() {
  stopPolling()
  pollHandle = setInterval(() => {
    if (auth.isAuthenticated && !document.hidden) store.fetchAll()
  }, 60000)
}
function stopPolling() {
  if (pollHandle) clearInterval(pollHandle)
  pollHandle = null
}
function onVisibilityChange() {
  if (!document.hidden && auth.isAuthenticated) store.fetchAll()
}

watch(
  () => auth.isAuthenticated,
  (v) => {
    if (v) {
      store.fetchAll()
      startPolling()
    } else {
      stopPolling()
    }
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onUnmounted(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

function onPopoverToggle(open) {
  if (open) store.fetchAll()
}
</script>

<style scoped>
.notif-popover {
  padding: 6px 2px;
  width: min(360px, calc(100vw - 32px));
}
.notif-popover-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 10px;
}
</style>
