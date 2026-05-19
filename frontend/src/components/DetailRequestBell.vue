<template>
  <!-- На мобильном Naive NPopover не shift'ит контент горизонтально и
       вылетает за viewport. Используем NModal: предсказуемое центрирование
       + клавиатура не «дёргает» bottom-nav. На десктопе оставляем поповер
       — он там корректно якорится к колокольчику. -->
  <template v-if="isMobile">
    <n-tooltip trigger="hover" placement="bottom">
      <template #trigger>
        <n-button quaternary circle size="small" @click="show = true">
          <template #icon>
            <n-badge :value="badgeCount" :max="9" :offset="[2, -2]" :show="badgeCount > 0">
              <n-icon size="18"><AlertCircleOutline /></n-icon>
            </n-badge>
          </template>
        </n-button>
      </template>
      Запросы на детализацию
    </n-tooltip>
    <n-modal
      v-model:show="show"
      preset="card"
      title="Запросы на детализацию"
      :style="{ width: 'min(420px, calc(100vw - 32px))' }"
    >
      <n-tabs
        v-model:value="tab"
        type="line"
        size="small"
        justify-content="space-evenly"
        pane-style="padding-top: 6px;"
      >
        <n-tab-pane name="open" :tab="`Открытые${openCount ? ' · ' + openCount : ''}`">
          <DrList :items="myOpen" :empty="'Нет открытых запросов'" @open="open" />
        </n-tab-pane>
        <n-tab-pane name="closed" tab="Закрытые">
          <DrList :items="myClosed" :empty="'Нет закрытых запросов'" @open="open" />
        </n-tab-pane>
      </n-tabs>
      <div class="dr-popover-footer">
        <n-button size="tiny" text type="primary" @click="store.fetchAll">Обновить</n-button>
      </div>
    </n-modal>
  </template>

  <n-popover v-else trigger="click" placement="bottom-end" :show-arrow="false">
    <template #trigger>
      <n-tooltip trigger="hover" placement="bottom">
        <template #trigger>
          <n-button quaternary circle size="small">
            <template #icon>
              <n-badge :value="badgeCount" :max="9" :offset="[2, -2]" :show="badgeCount > 0">
                <n-icon size="18"><AlertCircleOutline /></n-icon>
              </n-badge>
            </template>
          </n-button>
        </template>
        Запросы на детализацию
      </n-tooltip>
    </template>

    <div class="dr-popover">
      <div class="dr-popover-title" :style="{ color: palette.text3 }">Запросы на детализацию</div>

      <n-tabs
        v-model:value="tab"
        type="line"
        size="small"
        justify-content="space-evenly"
        pane-style="padding-top: 6px;"
      >
        <n-tab-pane name="open" :tab="`Открытые${openCount ? ' · ' + openCount : ''}`">
          <DrList :items="myOpen" :empty="'Нет открытых запросов'" @open="open" />
        </n-tab-pane>
        <n-tab-pane name="closed" tab="Закрытые">
          <DrList :items="myClosed" :empty="'Нет закрытых запросов'" @open="open" />
        </n-tab-pane>
      </n-tabs>

      <div class="dr-popover-footer">
        <n-button size="tiny" text type="primary" @click="store.fetchAll">Обновить</n-button>
      </div>
    </div>
  </n-popover>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { NPopover, NModal, NButton, NIcon, NBadge, NTooltip, NTabs, NTabPane } from 'naive-ui'
import { AlertCircleOutline } from '@vicons/ionicons5'
import { useDetailRequestsStore } from '@/stores/detailRequests'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { storeToRefs } from 'pinia'
import DrList from './DetailRequestBellList.vue'

const store = useDetailRequestsStore()
const auth = useAuthStore()
const { palette } = storeToRefs(useThemeStore())
const tab = ref('open')
const show = ref(false)

const windowWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1024)
const isMobile = computed(() => windowWidth.value < 768)
function onWinResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

const myOpen = computed(() =>
  store.items.filter((r) => r.status === 'open' && r.assignee?.user_id === auth.user?.user_id),
)
const myClosed = computed(() =>
  store.items.filter(
    (r) =>
      r.status === 'closed' &&
      (r.assignee?.user_id === auth.user?.user_id || r.creator?.user_id === auth.user?.user_id),
  ),
)
const openCount = computed(() => myOpen.value.length)
const badgeCount = computed(() => openCount.value)

function open(id) {
  store.openRequest(id)
}

watch(
  () => auth.isAuthenticated,
  (v) => {
    if (v) store.fetchAll()
  },
  { immediate: true },
)

onMounted(() => {
  if (auth.isAuthenticated) store.fetchAll()
})
</script>

<style scoped>
.dr-popover {
  padding: 6px 2px;
  width: min(360px, calc(100vw - 32px));
}
.dr-popover-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 10px;
}
.dr-popover-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 6px;
}
</style>
