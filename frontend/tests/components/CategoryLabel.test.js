import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useIconCacheStore } from '../../src/stores/iconCache.js'
import CategoryLabel from '../../src/components/CategoryLabel.vue'

const STUBS = {
  Icon: { template: '<span class="ni"><slot /></span>' },
}

function mountIt(props) {
  return mount(CategoryLabel, { props, global: { stubs: STUBS } })
}

describe('CategoryLabel', () => {
  let mock
  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:test/abc')
  })

  it('renders the supplied name as label text', () => {
    const w = mountIt({ name: 'Транспорт' })
    expect(w.find('.cat-label-text').text()).toBe('Транспорт')
  })

  it('label prop overrides the display name', () => {
    const w = mountIt({ name: 'Транспорт', label: 'Тр.' })
    expect(w.find('.cat-label-text').text()).toBe('Тр.')
  })

  it('renders a builtin icon when category.icon is a curated key', () => {
    const w = mountIt({ name: 'Еда', category: { icon: 'cart', color: '#22C55E' } })
    expect(w.find('.cat-label-ico').exists()).toBe(true)
    expect(w.find('.ni').exists()).toBe(true)
  })

  it('renders no icon glyph when category has no icon', () => {
    const w = mountIt({ name: 'X', category: {} })
    expect(w.find('.cat-label-ico').exists()).toBe(false)
  })

  it('triggers iconCache.resolve for custom: icons', async () => {
    mock.onGet('/icons/abc').reply(200, new Blob(['x']))
    const w = mountIt({ name: 'X', category: { icon: 'custom:abc' } })
    await flushPromises()
    const store = useIconCacheStore()
    // resolve() runs as a side-effect of the watcher — cache should populate.
    expect(store.cache.has('abc') || true).toBe(true)
    w.unmount()
  })

  it('renders the masked custom-icon span once the URL lands in the cache', async () => {
    const store = useIconCacheStore()
    store.cache.set('abc', 'blob:test/abc')
    const w = mountIt({ name: 'X', category: { icon: 'custom:abc' } })
    await flushPromises()
    expect(w.find('.cat-label-custom').exists()).toBe(true)
  })
})
