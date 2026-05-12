import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useWishlistStore } from '../../src/stores/wishlist.js'

describe('wishlist store', () => {
  let mock

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
  })

  it('fetch() loads list and toggles loading flag', async () => {
    mock.onGet('/wishlist').reply(200, [{ id: 'w1' }])
    const store = useWishlistStore()
    const p = store.fetch()
    expect(store.loading).toBe(true)
    await p
    expect(store.loading).toBe(false)
    expect(store.items).toEqual([{ id: 'w1' }])
  })

  it('togglePurchased flips the flag through update()', async () => {
    mock.onPut('/wishlist/w1').reply((config) => {
      const body = JSON.parse(config.data)
      expect(body.purchased).toBe(true)
      return [200, { id: 'w1', purchased: true }]
    })
    mock.onGet('/wishlist').reply(200, [])
    const store = useWishlistStore()
    await store.togglePurchased({ id: 'w1', purchased: false })
  })

  it('toggleFavorite flips is_favorite', async () => {
    mock.onPut('/wishlist/w1').reply((config) => {
      const body = JSON.parse(config.data)
      expect(body.is_favorite).toBe(false)
      return [200, { id: 'w1', is_favorite: false }]
    })
    mock.onGet('/wishlist').reply(200, [])
    const store = useWishlistStore()
    await store.toggleFavorite({ id: 'w1', is_favorite: true })
  })

  it('remove() calls DELETE and refetches', async () => {
    mock.onDelete('/wishlist/w1').reply(204)
    mock.onGet('/wishlist').reply(200, [])
    const store = useWishlistStore()
    await store.remove('w1')
    expect(mock.history.delete.length).toBe(1)
    expect(mock.history.get.length).toBe(1)
  })
})
