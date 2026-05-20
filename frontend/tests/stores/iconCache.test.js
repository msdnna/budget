import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useIconCacheStore, parseCustomIconKey } from '../../src/stores/iconCache.js'

describe('parseCustomIconKey()', () => {
  it('extracts the id from custom:<id>', () => {
    expect(parseCustomIconKey('custom:abc123')).toBe('abc123')
  })

  it('returns null for non-custom or malformed keys', () => {
    expect(parseCustomIconKey('cart')).toBeNull()
    expect(parseCustomIconKey('')).toBeNull()
    expect(parseCustomIconKey(null)).toBeNull()
    expect(parseCustomIconKey(42)).toBeNull()
    expect(parseCustomIconKey('custom:')).toBeNull()
  })
})

describe('iconCache store', () => {
  let mock
  let createdUrls
  let revokedUrls

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
    createdUrls = []
    revokedUrls = []
    // happy-dom doesn't ship URL.createObjectURL — stub it.
    let counter = 0
    globalThis.URL.createObjectURL = vi.fn((blob) => {
      const url = `blob:test/${counter++}`
      createdUrls.push({ url, blob })
      return url
    })
    globalThis.URL.revokeObjectURL = vi.fn((url) => revokedUrls.push(url))
  })

  it('resolve() returns null for empty id without hitting the network', async () => {
    const store = useIconCacheStore()
    await expect(store.resolve('')).resolves.toBeNull()
    await expect(store.resolve(null)).resolves.toBeNull()
    expect(createdUrls).toHaveLength(0)
  })

  it('resolve() fetches the icon and caches the object URL', async () => {
    mock.onGet('/icons/abc').reply(200, new Blob(['png-bytes']))
    const store = useIconCacheStore()
    const url = await store.resolve('abc')
    expect(url).toMatch(/^blob:test\//)
    expect(store.cache.get('abc')).toBe(url)
    expect(createdUrls).toHaveLength(1)

    // Second call hits the cache — no extra network round-trip and no new URL.
    const again = await store.resolve('abc')
    expect(again).toBe(url)
    expect(createdUrls).toHaveLength(1)
  })

  it('resolve() coalesces concurrent calls for the same id', async () => {
    mock.onGet('/icons/xyz').reply(200, new Blob(['x']))
    const store = useIconCacheStore()
    const [a, b, c] = await Promise.all([
      store.resolve('xyz'),
      store.resolve('xyz'),
      store.resolve('xyz'),
    ])
    expect(a).toBe(b)
    expect(b).toBe(c)
    // exactly one network call, one object URL allocated.
    expect(mock.history.get.filter((r) => r.url === '/icons/xyz')).toHaveLength(1)
    expect(createdUrls).toHaveLength(1)
  })

  it('resolve() propagates errors and clears inflight on failure', async () => {
    mock.onGet('/icons/bad').reply(500)
    const store = useIconCacheStore()
    await expect(store.resolve('bad')).rejects.toThrow()
    // After failure, a retry should be permitted (no stuck inflight).
    mock.onGet('/icons/bad').reply(200, new Blob(['ok']))
    await expect(store.resolve('bad')).resolves.toMatch(/^blob:/)
  })

  it('invalidate() revokes the URL and drops the entry', async () => {
    mock.onGet('/icons/del').reply(200, new Blob(['x']))
    const store = useIconCacheStore()
    const url = await store.resolve('del')
    store.invalidate('del')
    expect(store.cache.has('del')).toBe(false)
    expect(revokedUrls).toContain(url)
  })

  it('invalidate() is a no-op for unknown ids', () => {
    const store = useIconCacheStore()
    expect(() => store.invalidate('nope')).not.toThrow()
    expect(revokedUrls).toHaveLength(0)
  })
})
