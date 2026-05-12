import { createRouter, createWebHistory } from 'vue-router'

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
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} — Семейный бюджет` : 'Семейный бюджет'
})

export default router
