import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', redirect: '/statistics' },
  { path: '/income', component: () => import('@/views/IncomeView.vue'), meta: { title: 'Доходы' } },
  {
    path: '/expenses',
    component: () => import('@/views/ExpensesView.vue'),
    meta: { title: 'Расходы' },
  },
  {
    path: '/statistics',
    component: () => import('@/views/StatisticsView.vue'),
    meta: { title: 'Статистика' },
  },
  {
    path: '/forecast',
    component: () => import('@/views/ForecastingView.vue'),
    meta: { title: 'Прогноз' },
  },
  {
    path: '/export',
    component: () => import('@/views/ExportView.vue'),
    meta: { title: 'Экспорт' },
  },
  {
    path: '/settings/categories',
    component: () => import('@/views/AdminCategoriesView.vue'),
    meta: { title: 'Управление категориями', adminOnly: true },
  },
  {
    path: '/settings/users',
    component: () => import('@/views/UsersAdminView.vue'),
    meta: { title: 'Управление пользователями', adminOnly: true },
  },
  {
    path: '/settings/portability',
    component: () => import('@/views/PortabilityView.vue'),
    meta: { title: 'Импорт/экспорт', adminOnly: true },
  },
  {
    path: '/settings/telegram',
    component: () => import('@/views/SettingsTelegramView.vue'),
    meta: { title: 'Telegram' },
  },
  {
    path: '/settings/glossary',
    component: () => import('@/views/SettingsGlossaryView.vue'),
    meta: { title: 'Глоссарий', adminOnly: true },
  },
  // Default settings landing depends on role — admins see the
  // categories tab, regular users land directly on Telegram (the only
  // tab they can access).
  {
    path: '/settings',
    redirect: () => {
      const auth = useAuthStore()
      return auth.isAdmin ? '/settings/categories' : '/settings/telegram'
    },
  },
  // Backwards-compat redirect — earlier Phase 2 build used `/admin`.
  { path: '/admin', redirect: '/settings/categories' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// Non-admins typing /admin into the URL get bounced to /statistics. The
// sidebar link is already gated by isAdmin, so this is just defence in
// depth — the backend rejects the underlying PATCH/POST anyway.
router.beforeEach((to) => {
  if (to.meta?.adminOnly) {
    const auth = useAuthStore()
    if (!auth.isAdmin) return { path: '/statistics' }
  }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} — Семейный бюджет` : 'Семейный бюджет'
})

export default router
