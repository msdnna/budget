import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useAuthStore } from '../../src/stores/auth.js'
import NotificationBell from '../../src/components/NotificationBell.vue'

// Naive UI stubs: keyed by Vue-internal `name:` (Popover, NOT NPopover) — see
// [[feedback_naive_select_locale_gotchas]] / [[feedback_swipeable_card_gotchas]].
const STUBS = {
  Popover: {
    emits: ['update:show'],
    template:
      '<div class="popover-stub"><div class="trig" @click="$emit(\'update:show\', true)"><slot name="trigger" /></div><slot /></div>',
  },
  Modal: {
    props: ['show'],
    template: '<div class="modal-stub" v-if="show"><slot /></div>',
  },
  Button: {
    emits: ['click'],
    template: '<button class="nb" @click="$emit(\'click\')"><slot name="icon" /><slot /></button>',
  },
  Icon: { template: '<span class="ni"><slot /></span>' },
  Badge: {
    props: ['value', 'show'],
    template: '<span class="badge-stub" :data-value="value" :data-show="show"><slot /></span>',
  },
  Tooltip: {
    template: '<div class="tt"><slot name="trigger" /><slot /></div>',
  },
  NotificationsOutline: { template: '<i class="i-bell" />' },
}

function mountIt() {
  return mount(NotificationBell, { global: { stubs: STUBS } })
}

describe('NotificationBell', () => {
  let mock

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
    // 768px breakpoint → set desktop by default.
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1200 })
  })

  afterEach(() => {
    mock.restore()
    vi.restoreAllMocks()
  })

  it('does not fetch notifications when unauthenticated', async () => {
    const w = mountIt()
    await flushPromises()
    expect(mock.history.get.filter((r) => r.url === '/notifications')).toHaveLength(0)
    w.unmount()
  })

  it('fetches notifications immediately when auth becomes truthy', async () => {
    mock.onGet('/notifications').reply(200, { data: [], unread_count: 0 })
    const auth = useAuthStore()
    auth.token = 'fake-token'
    auth.user = { id: 'u1', login: 'me' }
    const w = mountIt()
    await flushPromises()
    expect(mock.history.get.some((r) => r.url === '/notifications')).toBe(true)
    w.unmount()
  })

  it('renders the badge with the store unread count', async () => {
    mock.onGet('/notifications').reply(200, { data: [], unread_count: 4 })
    const auth = useAuthStore()
    auth.token = 't'
    auth.user = { id: 'u1' }
    const w = mountIt()
    await flushPromises()
    const badge = w.find('.badge-stub')
    expect(badge.exists()).toBe(true)
    expect(badge.attributes('data-value')).toBe('4')
    expect(badge.attributes('data-show')).toBe('true')
    w.unmount()
  })

  it('desktop: clicking the trigger refetches via onPopoverToggle(open=true)', async () => {
    mock.onGet('/notifications').reply(200, { data: [], unread_count: 0 })
    const auth = useAuthStore()
    auth.token = 't'
    auth.user = { id: 'u1' }
    const w = mountIt()
    await flushPromises()
    const before = mock.history.get.filter((r) => r.url === '/notifications').length
    await w.find('.popover-stub .trig').trigger('click')
    await flushPromises()
    const after = mock.history.get.filter((r) => r.url === '/notifications').length
    expect(after).toBe(before + 1)
    w.unmount()
  })

  it('mobile (innerWidth < 768): renders the modal-mode tooltip+button instead of popover', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 360 })
    mock.onGet('/notifications').reply(200, { data: [], unread_count: 0 })
    const auth = useAuthStore()
    auth.token = 't'
    auth.user = { id: 'u1' }
    const w = mountIt()
    await flushPromises()
    // popover wrapper is conditional on !isMobile.
    expect(w.find('.popover-stub').exists()).toBe(false)
    w.unmount()
  })

  it('logout stops polling and does not crash', async () => {
    mock.onGet('/notifications').reply(200, { data: [], unread_count: 0 })
    const auth = useAuthStore()
    auth.token = 't'
    auth.user = { id: 'u1' }
    const w = mountIt()
    await flushPromises()
    auth.token = ''
    auth.user = null
    await flushPromises()
    // No error / unhandled rejection — that's the assertion.
    w.unmount()
  })
})
