import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useTransactionsStore } from '../../src/stores/transactions.js'

describe('transactions store factory', () => {
  let mock

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
  })

  it('returns scope-isolated stores so filters do not bleed', () => {
    const income = useTransactionsStore('income')
    const expenses = useTransactionsStore('expenses')
    income.filters.type = 'income'
    expenses.filters.type = 'expense'
    expect(income.filters.type).toBe('income')
    expect(expenses.filters.type).toBe('expense')
  })

  it('returns the same store instance for the same scope', () => {
    const a = useTransactionsStore('income')
    const b = useTransactionsStore('income')
    a.page = 5
    expect(b.page).toBe(5)
  })

  it('fetch() flattens filter spec into query params', async () => {
    const store = useTransactionsStore(`fetch-${Math.random()}`)
    store.filters = {
      type: 'expense',
      category: '',
      categories: ['Еда', 'Кофе'],
      from: '2026-01-01',
      to: '2026-01-31',
      includeDetailed: true,
    }
    mock.onGet('/transactions').reply((config) => {
      expect(config.params.type).toBe('expense')
      expect(config.params.categories).toBe('Еда,Кофе')
      expect(config.params.from).toBe('2026-01-01')
      expect(config.params.to).toBe('2026-01-31')
      expect(config.params.include_detailed).toBe('true')
      return [200, { data: [{ id: 'tx1' }], total: 1 }]
    })
    await store.fetch()
    expect(store.items).toHaveLength(1)
    expect(store.total).toBe(1)
  })

  it('fetch() falls back to single category when categories[] is empty', async () => {
    const store = useTransactionsStore(`single-cat-${Math.random()}`)
    store.filters.category = 'Еда'
    mock.onGet('/transactions').reply((config) => {
      expect(config.params.category).toBe('Еда')
      expect(config.params.categories).toBeUndefined()
      return [200, { data: [], total: 0 }]
    })
    await store.fetch()
  })

  it('setFilters() replaces (does NOT merge) and resets to page 1', async () => {
    const store = useTransactionsStore(`replace-${Math.random()}`)
    store.filters.type = 'expense'
    store.filters.from = '2026-01-01'
    store.page = 5
    mock.onGet('/transactions').reply(200, { data: [], total: 0 })
    store.setFilters({ type: 'income' })
    // Wait for the implicit fetch() to fire.
    await new Promise((r) => setTimeout(r, 0))
    expect(store.filters.type).toBe('income')
    expect(store.filters.from).toBe('') // cleared by replace semantics
    expect(store.page).toBe(1)
  })

  it('create/update/remove trigger a refetch', async () => {
    const store = useTransactionsStore(`crud-${Math.random()}`)
    mock.onPost('/transactions').reply(200, { id: 'new' })
    mock.onGet('/transactions').reply(200, { data: [{ id: 'new' }], total: 1 })
    await store.create({ amount: 100 })
    expect(mock.history.get.length).toBe(1)

    mock.onPut('/transactions/new').reply(200, { id: 'new', amount: 200 })
    await store.update('new', { amount: 200 })
    expect(mock.history.get.length).toBe(2)

    mock.onDelete('/transactions/new').reply(204)
    await store.remove('new')
    expect(mock.history.get.length).toBe(3)
  })
})
