import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useNotificationsStore } from '../../src/stores/notifications.js'

describe('notifications store', () => {
  let mock

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
    localStorage.clear()
  })

  it('fetchAll() populates items + unread_count', async () => {
    mock.onGet('/notifications').reply(200, {
      data: [
        {
          id: 'n1',
          type: 'category_limit_exceeded',
          period: '2026-05',
          category_id: 'c1',
          category_name: 'Транспорт',
          limit: 100,
          spent: 150,
          created_at: '2026-05-15T10:00:00Z',
          read: false,
        },
        {
          id: 'n2',
          type: 'global_limit_exceeded',
          period: '2026-05',
          limit: 100,
          spent: 200,
          created_at: '2026-05-15T10:05:00Z',
          read: true,
        },
      ],
      unread_count: 1,
    })
    const store = useNotificationsStore()
    await store.fetchAll()
    expect(store.items).toHaveLength(2)
    expect(store.unreadCount).toBe(1)
    expect(store.unread).toHaveLength(1)
    expect(store.unread[0].id).toBe('n1')
  })

  it('markAllRead() flips read state locally + resets unread count', async () => {
    mock.onGet('/notifications').reply(200, {
      data: [
        {
          id: 'n1',
          type: 'category_limit_exceeded',
          read: false,
          limit: 0,
          spent: 0,
          period: '2026-05',
        },
      ],
      unread_count: 1,
    })
    mock.onPost('/notifications/read-all').reply(200, { ok: true })
    const store = useNotificationsStore()
    await store.fetchAll()
    await store.markAllRead()
    expect(store.unreadCount).toBe(0)
    expect(store.items.every((n) => n.read)).toBe(true)
  })

  it('fetchAll() soft-fails when the endpoint errors', async () => {
    mock.onGet('/notifications').reply(500, { error: 'boom' })
    const store = useNotificationsStore()
    await expect(store.fetchAll()).resolves.toBeUndefined()
    expect(store.items).toEqual([])
    expect(store.unreadCount).toBe(0)
  })
})
