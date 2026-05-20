import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import SettingsTabs from '../../src/components/SettingsTabs.vue'

function makeRouter(initialPath = '/settings/categories') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings/categories', component: { template: '<div />' } },
      { path: '/settings/users', component: { template: '<div />' } },
      { path: '/settings/portability', component: { template: '<div />' } },
    ],
  })
  router.push(initialPath)
  return router
}

async function mountIt(path) {
  const router = makeRouter(path)
  await router.isReady()
  const wrapper = mount(SettingsTabs, {
    global: { plugins: [router] },
  })
  return { wrapper, router }
}

describe('SettingsTabs', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders three tabs in fixed order', async () => {
    const { wrapper } = await mountIt('/settings/categories')
    const tabs = wrapper.findAll('button.settings-tab')
    expect(tabs.map((t) => t.text())).toEqual(['Категории', 'Пользователи', 'Импорт/экспорт'])
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
    const portability = wrapper.findAll('button.settings-tab')[2]
    await portability.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings/portability')
  })
})
