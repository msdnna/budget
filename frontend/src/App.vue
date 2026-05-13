<template>
  <n-config-provider :theme="naiveTheme" :theme-overrides="themeOverrides">
    <n-message-provider>
      <n-notification-provider>
        <n-layout style="min-height: 100vh">
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

            <!-- Content -->
            <n-layout style="overflow-y: auto">
              <n-layout-header
                bordered
                class="app-header"
                style="
                  padding: 0 24px;
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
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
              <n-layout-content style="padding: 24px">
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
                <!-- Mobile theme picker -->
                <n-popover trigger="click" placement="bottom-end" :show-arrow="false">
                  <template #trigger>
                    <n-button quaternary circle size="small" :title="'Цвет темы'">
                      <template #icon>
                        <n-icon size="18" :style="{ color: primaryColor }">
                          <ColorPaletteOutline />
                        </n-icon>
                      </template>
                    </n-button>
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
                <!-- Mobile hide/show values -->
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
                <!-- Mobile pie chart unit toggle -->
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
                <router-view />
              </AuthGate>
            </n-layout-content>

            <!-- Bottom navigation -->
            <div class="mobile-nav">
              <div
                v-for="item in mobileNavItems"
                :key="item.key"
                class="mobile-nav-item"
                :class="{ active: activeKey === item.key }"
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
  NButton,
  NSpace,
  NPopover,
  NTooltip,
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
import DetailRequestModal from '@/components/DetailRequestModal.vue'
import DetailRequestCreateModal from '@/components/DetailRequestCreateModal.vue'
import { useDetailRequestsStore } from '@/stores/detailRequests'

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
const collapsed = ref(false)
const windowWidth = ref(window.innerWidth)
const isMobile = computed(() => windowWidth.value < 768)

function onResize() {
  windowWidth.value = window.innerWidth
}
onMounted(async () => {
  window.addEventListener('resize', onResize)
  window.addEventListener('auth:expired', onAuthExpired)
  await auth.verify()
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
  if (auth.isAdmin) {
    items.push({
      label: 'Настройки',
      key: 'settings',
      icon: icon(SettingsOutline),
      children: [{ label: 'Категории', key: 'settings/categories' }],
    })
  }
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
  if (auth.isAdmin) {
    items.push({
      label: 'Настройки',
      key: 'settings/categories',
      icon: SettingsOutline,
    })
  }
  return items
})
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
