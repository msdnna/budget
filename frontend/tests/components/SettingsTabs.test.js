import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import SettingsTabs from '../../src/components/SettingsTabs.vue'
import { useAuthStore } from '../../src/stores/auth'

function makeRouter(initialPath = '/settings/categories') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings/categories', component: { template: '<div />' } },
      { path: '/settings/users', component: { template: '<div />' } },
      { path: '/settings/portability', component: { template: '<div />' } },
      { path: '/settings/glossary', component: { template: '<div />' } },
      { path: '/settings/intent-triggers', component: { template: '<div />' } },
      { path: '/settings/telegram', component: { template: '<div />' } },
    ],
  })
  router.push(initialPath)
  return router
}

async function mountIt(path, { admin = true } = {}) {
  const router = makeRouter(path)
  await router.isReady()
  // Tabs filter by auth.isAdmin — seed the store before mounting so the
  // computed `visibleTabs` resolves to the expected set.
  const auth = useAuthStore()
  auth.user = admin ? { is_admin: true } : { is_admin: false }
  const wrapper = mount(SettingsTabs, {
    global: { plugins: [router] },
  })
  return { wrapper, router }
}

describe('SettingsTabs', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders all admin tabs in fixed order', async () => {
    const { wrapper } = await mountIt('/settings/categories')
    const tabs = wrapper.findAll('button.settings-tab')
    expect(tabs.map((t) => t.text())).toEqual([
      'Категории',
      'Пользователи',
      'Глоссарий',
      'Триггеры бота',
      'Импорт/экспорт',
      'Telegram',
    ])
  })

  it('renders only Telegram tab for non-admins', async () => {
    const { wrapper } = await mountIt('/settings/telegram', { admin: false })
    const tabs = wrapper.findAll('button.settings-tab')
    expect(tabs.map((t) => t.text())).toEqual(['Telegram'])
  })

  it('marks the active tab by route path', async () => {
    const { wrapper } = await mountIt('/settings/users')
    const tabs = wrapper.findAll('button.settings-tab')
    const active = tabs.filter((t) => t.classes().includes('active'))
    expect(active).toHaveLength(1)
    expect(active[0].text()).toBe('Пользователи')
  })

  it('navigates on click', async () => {
    const { wrapper, router } = await mountIt('/settings/categories')
    // Order: Категории, Пользователи, Глоссарий, Триггеры бота, Импорт/экспорт, Telegram
    const portability = wrapper.findAll('button.settings-tab')[4]
    await portability.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings/portability')
  })
})
