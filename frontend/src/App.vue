<template>
  <n-config-provider
    :theme="naiveTheme"
    :theme-overrides="themeOverrides"
    :locale="ruRU"
    :date-locale="dateRuRU"
  >
    <n-message-provider>
      <n-notification-provider>
        <n-dialog-provider>
          <!-- First-run wizard takes over the whole UI while the DB has no
             users. SetupWizard emits `done` after admin creation + optional
             import; we flip needsSetup=false and the normal layout below
             takes over without a page reload. -->
          <SetupWizard v-if="needsSetup" @done="onSetupDone" />
          <n-layout v-else style="min-height: 100vh">
            <!-- ── Desktop sidebar layout ──────────────────────────── -->
            <n-layout v-if="!isMobile" has-sider style="height: 100vh">
              <n-layout-sider
                bordered
                collapse-mode="width"
                :collapsed-width="64"
                :width="220"
                :collapsed="collapsed"
                show-trigger
                @collapse="collapsed = true"
                @expand="collapsed = false"
              >
                <div class="sider-inner">
                  <!-- Logo -->
                  <div class="logo" :class="{ collapsed }">
                    <MbLogo :color="primaryColor" :size="26" aria-label="msdnna budget" />
                    <div v-if="!collapsed" class="logo-name">
                      <span class="logo-name-main">msdnna budget</span>
                      <span class="logo-name-sub">Система ведения бюджета</span>
                    </div>
                  </div>

                  <!-- Navigation -->
                  <n-menu
                    v-model:expanded-keys="expandedMenuKeys"
                    :collapsed="collapsed"
                    :collapsed-width="64"
                    :collapsed-icon-size="22"
                    :options="menuOptions"
                    :value="activeKey"
                    @update:value="navigate"
                  />

                  <div style="flex: 1" />

                  <!-- User / Login section -->
                  <div class="user-section" :class="{ collapsed }">
                    <template v-if="auth.isAuthenticated">
                      <div v-if="!collapsed" class="user-info">
                        <UserAvatar
                          :display-name="auth.user?.display_name || ''"
                          :avatar-url="auth.user?.avatar_url || ''"
                          :size="28"
                        />
                        <n-text
                          depth="2"
                          style="
                            font-size: 12px;
                            flex: 1;
                            overflow: hidden;
                            text-overflow: ellipsis;
                            white-space: nowrap;
                          "
                        >
                          {{ auth.user?.display_name }}
                        </n-text>
                        <n-tooltip trigger="hover" placement="top">
                          <template #trigger>
                            <n-button
                              quaternary
                              circle
                              size="tiny"
                              title="Выйти"
                              @click="handleLogout"
                            >
                              <template #icon>
                                <n-icon size="14"><LogOutOutline /></n-icon>
                              </template>
                            </n-button>
                          </template>
                          Выйти
                        </n-tooltip>
                      </div>
                      <n-tooltip v-else trigger="hover" placement="right">
                        <template #trigger>
                          <div class="user-avatar-btn" @click="handleLogout">
                            <UserAvatar
                              :display-name="auth.user?.display_name || ''"
                              :avatar-url="auth.user?.avatar_url || ''"
                              :size="32"
                            />
                          </div>
                        </template>
                        {{ auth.user?.display_name }} — выйти
                      </n-tooltip>
                    </template>
                    <template v-else>
                      <n-button
                        v-if="!collapsed"
                        block
                        type="primary"
                        size="small"
                        ghost
                        @click="showLogin = true"
                      >
                        <template #icon>
                          <n-icon><LogInOutline /></n-icon>
                        </template>
                        Войти
                      </n-button>
                      <n-tooltip v-else trigger="hover" placement="right">
                        <template #trigger>
                          <n-button quaternary circle @click="showLogin = true">
                            <template #icon>
                              <n-icon><LogInOutline /></n-icon>
                            </template>
                          </n-button>
                        </template>
                        Войти
                      </n-tooltip>
                    </template>
                  </div>
                </div>
                <!-- /sider-inner -->
              </n-layout-sider>

              <!-- Content. Inner layout is a flex column so the header
                 stays pinned at the top while only the content area
                 scrolls — a single scrollbar over the body (not the
                 header) is far less visually noisy. -->
              <n-layout class="content-layout">
                <n-layout-header
                  bordered
                  class="app-header"
                  style="
                    padding: 0 24px;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    flex-shrink: 0;
                  "
                >
                  <n-text strong style="font-size: 16px">{{ currentTitle }}</n-text>
                  <n-space align="center" :size="8">
                    <n-text depth="3" style="font-size: 13px">{{ today }}</n-text>
                    <!-- Color picker -->
                    <n-popover trigger="click" placement="bottom-end" :show-arrow="false">
                      <template #trigger>
                        <n-tooltip trigger="hover" placement="bottom">
                          <template #trigger>
                            <n-button quaternary circle size="small">
                              <template #icon>
                                <n-icon size="16" :style="{ color: primaryColor }">
                                  <ColorPaletteOutline />
                                </n-icon>
                              </template>
                            </n-button>
                          </template>
                          Цвет темы
                        </n-tooltip>
                      </template>
                      <div style="padding: 6px 2px">
                        <div
                          :style="{
                            fontSize: '11px',
                            color: palette.text3,
                            textTransform: 'uppercase',
                            letterSpacing: '0.05em',
                            marginBottom: '8px',
                          }"
                        >
                          Цвет темы
                        </div>
                        <div class="theme-dots">
                          <div
                            v-for="t in COLOR_THEMES"
                            :key="t.key"
                            class="theme-dot"
                            :title="t.name"
                            :style="dotStyle(t)"
                            @click="selectTheme(t)"
                          />
                        </div>
                      </div>
                    </n-popover>
                    <!-- Info popover -->
                    <n-popover trigger="click" placement="bottom-end" :show-arrow="false">
                      <template #trigger>
                        <n-tooltip trigger="hover" placement="bottom">
                          <template #trigger>
                            <n-button quaternary circle size="small">
                              <template #icon>
                                <n-icon size="18"><InformationCircleOutline /></n-icon>
                              </template>
                            </n-button>
                          </template>
                          О приложении
                        </n-tooltip>
                      </template>
                      <div class="version-popover">
                        <div class="version-popover-title" :style="{ color: palette.text3 }">
                          О приложении
                        </div>
                        <div class="version-row">
                          <span>Веб</span>
                          <span class="version-val">v{{ webVersion }}</span>
                        </div>
                        <div class="version-row">
                          <span>API</span>
                          <span class="version-val">{{ apiVersion ? 'v' + apiVersion : '…' }}</span>
                        </div>
                      </div>
                    </n-popover>
                    <!-- Detail-requests bell -->
                    <DetailRequestBell v-if="auth.isAuthenticated" />
                    <!-- Limit-overflow notifications bell -->
                    <NotificationBell v-if="auth.isAuthenticated" />
                    <!-- Hide/show values toggle -->
                    <n-tooltip trigger="hover" placement="bottom">
                      <template #trigger>
                        <n-button quaternary circle size="small" @click="toggleValuesHidden">
                          <template #icon>
                            <n-icon size="16">
                              <EyeOutline v-if="valuesHidden" />
                              <EyeOffOutline v-else />
                            </n-icon>
                          </template>
                        </n-button>
                      </template>
                      {{ valuesHidden ? 'Показать суммы' : 'Скрыть суммы' }}
                    </n-tooltip>
                    <!-- Pie chart unit toggle -->
                    <n-tooltip trigger="hover" placement="bottom">
                      <template #trigger>
                        <n-button quaternary circle size="small" @click="togglePieChartUnit">
                          <span style="font-size: 12px; font-weight: 700; line-height: 1">
                            {{ pieChartUnit === 'percent' ? '%' : '₽' }}
                          </span>
                        </n-button>
                      </template>
                      {{ pieChartUnit === 'percent' ? 'Диаграммы: проценты' : 'Диаграммы: рубли' }}
                    </n-tooltip>
                    <!-- Dark mode toggle -->
                    <n-tooltip trigger="hover" placement="bottom">
                      <template #trigger>
                        <n-button quaternary circle size="small" @click="toggleDarkMode">
                          <template #icon>
                            <n-icon size="16">
                              <SunnyOutline v-if="isDark" />
                              <MoonOutline v-else />
                            </n-icon>
                          </template>
                        </n-button>
                      </template>
                      {{ isDark ? 'Светлая тема' : 'Тёмная тема' }}
                    </n-tooltip>
                  </n-space>
                </n-layout-header>
                <n-layout-content class="scrollable-content" content-style="padding: 24px">
                  <AuthGate @login="showLogin = true">
                    <router-view />
                  </AuthGate>
                </n-layout-content>
              </n-layout>
            </n-layout>

            <!-- ── Mobile layout ──────────────────────────────────── -->
            <n-layout v-else>
              <n-layout-header
                bordered
                style="
                  padding: 10px 16px;
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                "
              >
                <MbLogo :color="primaryColor" :size="22" aria-label="msdnna budget" />
                <n-space align="center" :size="6">
                  <!-- Mobile login/user -->
                  <template v-if="auth.isAuthenticated">
                    <n-tooltip trigger="click" placement="bottom-end">
                      <template #trigger>
                        <div style="cursor: pointer">
                          <UserAvatar
                            :display-name="auth.user?.display_name || ''"
                            :avatar-url="auth.user?.avatar_url || ''"
                            :size="28"
                          />
                        </div>
                      </template>
                      <div>
                        <div style="font-weight: 600; margin-bottom: 6px">
                          {{ auth.user?.display_name }}
                        </div>
                        <n-button size="small" type="error" ghost @click="handleLogout">
                          Выйти
                        </n-button>
                      </div>
                    </n-tooltip>
                  </template>
                  <!-- Mobile detail-requests bell -->
                  <DetailRequestBell v-if="auth.isAuthenticated" />
                  <!-- Mobile notifications bell -->
                  <NotificationBell v-if="auth.isAuthenticated" />
                  <!-- Mobile visuals menu — single popover wrapping the four
                     visual toggles (theme color, hide values, pie unit,
                     dark mode). Saves three header slots so the row fits
                     on narrow phones without the wraparound that pushed
                     `15 мая 2026 г.` onto its own line. -->
                  <n-popover trigger="click" placement="bottom-end" :show-arrow="false">
                    <template #trigger>
                      <n-button quaternary circle size="small" title="Внешний вид">
                        <template #icon>
                          <n-icon size="18" :style="{ color: primaryColor }">
                            <ColorPaletteOutline />
                          </n-icon>
                        </template>
                      </n-button>
                    </template>
                    <div class="visuals-menu">
                      <div class="visuals-menu-section">
                        <div class="visuals-menu-label" :style="{ color: palette.text3 }">
                          Цвет темы
                        </div>
                        <div class="theme-dots">
                          <div
                            v-for="t in COLOR_THEMES"
                            :key="t.key"
                            class="theme-dot"
                            :title="t.name"
                            :style="dotStyle(t)"
                            @click="selectTheme(t)"
                          />
                        </div>
                      </div>
                      <div class="visuals-menu-section">
                        <button type="button" class="visuals-menu-row" @click="toggleValuesHidden">
                          <n-icon size="16">
                            <EyeOutline v-if="valuesHidden" />
                            <EyeOffOutline v-else />
                          </n-icon>
                          <span>{{ valuesHidden ? 'Показать суммы' : 'Скрыть суммы' }}</span>
                        </button>
                        <button type="button" class="visuals-menu-row" @click="togglePieChartUnit">
                          <span class="visuals-menu-glyph">
                            {{ pieChartUnit === 'percent' ? '%' : '₽' }}
                          </span>
                          <span>
                            {{
                              pieChartUnit === 'percent'
                                ? 'Диаграммы: проценты'
                                : 'Диаграммы: рубли'
                            }}
                          </span>
                        </button>
                        <button type="button" class="visuals-menu-row" @click="toggleDarkMode">
                          <n-icon size="16">
                            <SunnyOutline v-if="isDark" />
                            <MoonOutline v-else />
                          </n-icon>
                          <span>{{ isDark ? 'Светлая тема' : 'Тёмная тема' }}</span>
                        </button>
                      </div>
                    </div>
                  </n-popover>
                  <!-- Mobile info popover -->
                  <n-popover trigger="click" placement="bottom-end" :show-arrow="false">
                    <template #trigger>
                      <n-button quaternary circle size="small">
                        <template #icon>
                          <n-icon size="18"><InformationCircleOutline /></n-icon>
                        </template>
                      </n-button>
                    </template>
                    <div class="version-popover">
                      <div class="version-popover-title" :style="{ color: palette.text3 }">
                        О приложении
                      </div>
                      <div class="version-row">
                        <span>Веб</span>
                        <span class="version-val">v{{ webVersion }}</span>
                      </div>
                      <div class="version-row">
                        <span>API</span>
                        <span class="version-val">{{ apiVersion ? 'v' + apiVersion : '…' }}</span>
                      </div>
                    </div>
                  </n-popover>
                  <n-text depth="3" style="font-size: 12px">{{ today }}</n-text>
                </n-space>
              </n-layout-header>

              <n-layout-content style="padding: 16px; padding-bottom: 80px">
                <AuthGate @login="showLogin = true">
                  <SettingsTabs v-if="isSettingsRoute" />
                  <router-view />
                </AuthGate>
              </n-layout-content>

              <!-- Bottom navigation -->
              <div class="mobile-nav">
                <div
                  v-for="item in mobileNavItems"
                  :key="item.key"
                  class="mobile-nav-item"
                  :class="{ active: mobileActiveKey === item.key }"
                  @click="navigate(item.key)"
                >
                  <n-icon :component="item.icon" size="22" />
                  <span>{{ item.label }}</span>
                </div>
              </div>
            </n-layout>
          </n-layout>

          <LoginModal :show="showLogin" @success="showLogin = false" @close="showLogin = false" />
          <DetailRequestModal
            :show="!!detailReq.openRequestId"
            :request-id="detailReq.openRequestId"
            @close="detailReq.closeRequest()"
            @updated="detailReq.fetchAll()"
            @closed="detailReq.fetchAll()"
          />
          <DetailRequestCreateModal
            :show="!!detailReq.creatingForTx"
            :transaction="detailReq.creatingForTx"
            @close="detailReq.cancelCreate()"
            @created="onRequestCreated"
          />
        </n-dialog-provider>
      </n-notification-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup>
import { computed, ref, h, onMounted, onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import {
  NConfigProvider,
  NLayout,
  NLayoutSider,
  NLayoutContent,
  NLayoutHeader,
  NMenu,
  NIcon,
  NText,
  NMessageProvider,
  NNotificationProvider,
  NDialogProvider,
  NButton,
  NSpace,
  NPopover,
  NTooltip,
  ruRU,
  dateRuRU,
} from 'naive-ui'
import {
  TrendingUpOutline,
  TrendingDownOutline,
  BarChartOutline,
  BulbOutline,
  CloudDownloadOutline,
  ColorPaletteOutline,
  SettingsOutline,
  LogInOutline,
  LogOutOutline,
  SunnyOutline,
  MoonOutline,
  InformationCircleOutline,
  EyeOutline,
  EyeOffOutline,
} from '@vicons/ionicons5'
import { useThemeStore, COLOR_THEMES } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import { versionApi } from '@/api/index.js'
import LoginModal from '@/components/LoginModal.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import AuthGate from '@/components/AuthGate.vue'
import MbLogo from '@/components/MbLogo.vue'
import DetailRequestBell from '@/components/DetailRequestBell.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import DetailRequestModal from '@/components/DetailRequestModal.vue'
import DetailRequestCreateModal from '@/components/DetailRequestCreateModal.vue'
import SettingsTabs from '@/components/SettingsTabs.vue'
import SetupWizard from '@/components/SetupWizard.vue'
import { useDetailRequestsStore } from '@/stores/detailRequests'
import api from '@/api/index'

// ── Version info ──────────────────────────────────────────────────
const webVersion = __APP_VERSION__
const apiVersion = ref(null)

// ── Theme ─────────────────────────────────────────────────────────
const themeStore = useThemeStore()
const {
  activeTheme,
  primaryColor,
  palette,
  naiveTheme,
  themeOverrides,
  isDark,
  valuesHidden,
  pieChartUnit,
} = storeToRefs(themeStore)
const { selectTheme, toggleDarkMode, toggleValuesHidden, togglePieChartUnit } = themeStore

function dotStyle(t) {
  const isActive = activeTheme.value.key === t.key
  return {
    backgroundColor: t.primary,
    boxShadow: isActive ? `0 0 0 2px ${palette.value.surface}, 0 0 0 4px ${t.primary}` : 'none',
  }
}

// ── Auth ──────────────────────────────────────────────────────────
const auth = useAuthStore()
const showLogin = ref(false)
const detailReq = useDetailRequestsStore()

function onRequestCreated() {
  detailReq.fetchAll()
}

function handleLogout() {
  auth.logout()
}

function onAuthExpired() {
  showLogin.value = true
}

// ── Responsive ────────────────────────────────────────────────────
const router = useRouter()
const route = useRoute()
// Sidebar collapsed state persists across reloads via localStorage —
// the trigger-button click flips it through the `@collapse`/`@expand`
// handlers, and the watcher below mirrors the change to storage.
const collapsed = ref(localStorage.getItem('budget-sidebar-collapsed') === 'true')
watch(collapsed, (v) => {
  localStorage.setItem('budget-sidebar-collapsed', String(v))
})
const windowWidth = ref(window.innerWidth)
const isMobile = computed(() => windowWidth.value < 768)

function onResize() {
  windowWidth.value = window.innerWidth
}
// First-run setup gate. Hit /api/setup/status before mounting the
// regular shell — if the backend says the DB has no users, render the
// wizard exclusively (no sidebar, no login modal). Once the wizard
// emits `done` we flip this flag and the normal layout renders.
const needsSetup = ref(false)
async function checkSetupStatus() {
  try {
    const res = await api.get('/setup/status')
    needsSetup.value = !!res.data?.needs_setup
  } catch {
    // Backend unavailable — fall through to the regular login flow so
    // the existing error surfaces (rather than locking the user into
    // a wizard they can't escape).
    needsSetup.value = false
  }
}
function onSetupDone() {
  needsSetup.value = false
  // Land on /statistics by default. setAuth already wrote the token in
  // step 1, so the rest of the app will pick it up.
  router.replace('/statistics')
}

onMounted(async () => {
  window.addEventListener('resize', onResize)
  window.addEventListener('auth:expired', onAuthExpired)
  await checkSetupStatus()
  if (!needsSetup.value) {
    await auth.verify()
  }
  versionApi
    .get()
    .then((r) => {
      apiVersion.value = r.data.api
    })
    .catch(() => {})
})
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('auth:expired', onAuthExpired)
})

// ── Navigation ────────────────────────────────────────────────────
const today = computed(() =>
  new Date().toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' }),
)

// Menu keys mirror full paths so nested entries (e.g. `settings/categories`)
// resolve naturally — drop the leading slash and use the rest as-is.
const activeKey = computed(() => route.path.replace(/^\//, '') || 'statistics')

// Mutable so the user can click the parent to expand/collapse — bound via
// `v-model:expanded-keys` on NMenu. We seed it from the route on mount and
// auto-expand the parent whenever the active route changes to a child, so
// deep links / back-button land on an already-open submenu without
// overriding user intent on subsequent clicks.
const expandedMenuKeys = ref([])
watch(
  activeKey,
  (k) => {
    if (k.includes('/')) {
      const parent = k.split('/')[0]
      if (!expandedMenuKeys.value.includes(parent)) {
        expandedMenuKeys.value = [...expandedMenuKeys.value, parent]
      }
    }
  },
  { immediate: true },
)
const currentTitle = computed(() => {
  const map = {
    income: 'Доходы',
    expenses: 'Расходы',
    statistics: 'Статистика',
    forecast: 'Прогноз',
    export: 'Экспорт',
    settings: 'Настройки',
    'settings/categories': 'Настройки · Категории',
    'settings/users': 'Настройки · Пользователи',
    'settings/portability': 'Настройки · Импорт/экспорт',
    'settings/glossary': 'Настройки · Глоссарий',
    'settings/telegram': 'Настройки · Telegram',
  }
  return map[activeKey.value] ?? 'Статистика'
})

function navigate(key) {
  router.push('/' + key)
}
function icon(comp) {
  return () => h(NIcon, null, { default: () => h(comp) })
}

// Tree-structured options: top-level items use single-key, the Настройки
// group gets `children` so NMenu renders it as an expandable submenu.
// Children keys must mirror the route path so `activeKey` highlights
// them automatically.
const menuOptions = computed(() => {
  const items = [
    { label: 'Статистика', key: 'statistics', icon: icon(BarChartOutline) },
    { label: 'Доходы', key: 'income', icon: icon(TrendingUpOutline) },
    { label: 'Расходы', key: 'expenses', icon: icon(TrendingDownOutline) },
    { label: 'Прогноз', key: 'forecast', icon: icon(BulbOutline) },
    { label: 'Экспорт', key: 'export', icon: icon(CloudDownloadOutline) },
  ]
  // Settings menu is visible to everyone; admin-only subsections (Категории /
  // Пользователи / Импорт-экспорт) appear only for admins. Regular users see
  // just the Telegram tab — they can still bind their personal bot account.
  const settingsChildren = []
  if (auth.isAdmin) {
    settingsChildren.push(
      { label: 'Категории', key: 'settings/categories' },
      { label: 'Пользователи', key: 'settings/users' },
      { label: 'Глоссарий', key: 'settings/glossary' },
      { label: 'Импорт/экспорт', key: 'settings/portability' },
    )
  }
  settingsChildren.push({ label: 'Telegram', key: 'settings/telegram' })
  items.push({
    label: 'Настройки',
    key: 'settings',
    icon: icon(SettingsOutline),
    children: settingsChildren,
  })
  return items
})

// Bottom tab bar. Admins get a 6th tab "Настройки" — flat-rendered with
// the categories key (rather than the parent group) so a single tap
// navigates straight to the only subscreen we currently have.
const mobileNavItems = computed(() => {
  const items = [
    { label: 'Статистика', key: 'statistics', icon: BarChartOutline },
    { label: 'Доходы', key: 'income', icon: TrendingUpOutline },
    { label: 'Расходы', key: 'expenses', icon: TrendingDownOutline },
    { label: 'Прогноз', key: 'forecast', icon: BulbOutline },
    { label: 'Экспорт', key: 'export', icon: CloudDownloadOutline },
  ]
  // Mobile bottom-nav «Настройки» теперь видна всем — открывает дефолтную
  // вкладку по роли (Категории для админа, Telegram для остальных). Внутренний
  // tab-strip переключает между подразделами в зависимости от прав.
  items.push({
    label: 'Настройки',
    key: auth.isAdmin ? 'settings/categories' : 'settings/telegram',
    icon: SettingsOutline,
  })
  return items
})

// Bottom-nav active-state matcher: любая /settings/* вкладка подсвечивает
// единственную «Настройки» — используем тот же дефолтный key, что и в items.
const mobileActiveKey = computed(() => {
  if (activeKey.value.startsWith('settings/')) {
    return auth.isAdmin ? 'settings/categories' : 'settings/telegram'
  }
  return activeKey.value
})

const isSettingsRoute = computed(() => activeKey.value.startsWith('settings/'))
</script>

<style scoped>
/* ── Sidebar inner flex container ─────────────────────────────── */
.sider-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* ── App header / sider logo (same height for visual alignment) ─── */
.app-header {
  min-height: var(--app-header-h, 64px);
}

/* Inner layout: pinned header + scrolling body. `:deep` is required
   because `<n-layout>` renders its own wrapper div. */
.content-layout {
  height: 100vh;
}
.content-layout :deep(.n-layout-scroll-container) {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.scrollable-content {
  flex: 1;
  min-height: 0;
}
.scrollable-content :deep(.n-layout-scroll-container) {
  overflow-y: auto;
}

/* ── Logo ──────────────────────────────────────────────────────── */
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  min-height: var(--app-header-h, 64px);
  box-sizing: border-box;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
  transition:
    padding 0.3s,
    border-color 0.3s;
}
.logo.collapsed {
  padding: 0;
  justify-content: center;
}
.logo-name {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  overflow: hidden;
}
.logo-name-main {
  font-size: 12px;
  font-weight: 800;
  color: v-bind(primaryColor);
  white-space: nowrap;
  letter-spacing: -0.2px;
}
.logo-name-sub {
  font-size: 9.5px;
  font-weight: 400;
  color: var(--text-muted, #93a3c8);
  white-space: nowrap;
  opacity: 0.75;
}

/* ── Version popover ──────────────────────────────────────────── */
.version-popover {
  padding: 6px 2px;
  min-width: 150px;
}
.version-popover-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 10px;
}
.version-row {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  font-size: 13px;
  margin-bottom: 4px;
}
.version-val {
  opacity: 0.6;
  font-variant-numeric: tabular-nums;
}

/* ── Mobile visuals menu ───────────────────────────────────────── */
.visuals-menu {
  padding: 4px 2px;
  width: min(220px, calc(100vw - 32px));
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.visuals-menu-section + .visuals-menu-section {
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.visuals-menu-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 8px;
}
.visuals-menu-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 6px;
  border-radius: 6px;
  background: transparent;
  border: none;
  cursor: pointer;
  color: inherit;
  font: inherit;
  text-align: left;
  transition: background 0.12s;
}
.visuals-menu-row:hover {
  background: var(--hover);
}
.visuals-menu-glyph {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}

/* ── Color picker dots ─────────────────────────────────────────── */
.theme-dots {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.theme-dot {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  cursor: pointer;
  flex-shrink: 0;
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease;
}
.theme-dot:hover {
  transform: scale(1.2);
}

/* ── User section ──────────────────────────────────────────────── */
.user-section {
  padding: 10px 16px 16px;
  border-top: 1px solid var(--border);
  transition: border-color 0.3s;
}
.user-section.collapsed {
  padding: 10px 8px 16px;
  display: flex;
  justify-content: center;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-avatar-btn {
  cursor: pointer;
  border-radius: 50%;
  transition: opacity 0.15s;
}
.user-avatar-btn:hover {
  opacity: 0.8;
}

/* ── Mobile bottom nav ─────────────────────────────────────────── */
.mobile-nav {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 64px;
  background: var(--nav-bg);
  border-top: 1px solid var(--nav-border);
  display: flex;
  align-items: center;
  z-index: 100;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.08);
  transition:
    background 0.3s,
    border-color 0.3s;
}
.mobile-nav-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--nav-item);
  cursor: pointer;
  padding: 6px 0;
  transition: color 0.2s;
}
.mobile-nav-item.active {
  color: v-bind(primaryColor);
}
.mobile-nav-item span {
  font-size: 10px;
}
</style>
