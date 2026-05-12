import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useCategoriesStore } from '../../src/stores/categories.js'

describe('categories store', () => {
  let mock

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
    localStorage.clear()
  })

  it('load() populates the section list', async () => {
    mock.onGet('/categories').reply(200, [
      { id: '1', name: 'Еда', section: 'expense', is_default: true },
      { id: '2', name: 'Кофе', section: 'expense', is_default: false },
    ])
    const store = useCategoriesStore()
    await store.load('expense')
    expect(store.bySection.expense).toHaveLength(2)
  })

  it('add() trims input and returns existing category instead of duplicating', async () => {
    const store = useCategoriesStore()
    store.bySection.expense = [{ id: '1', name: 'Еда', section: 'expense' }]
    const existing = await store.add('expense', '  Еда  ')
    expect(existing.id).toBe('1')
    expect(mock.history.post.length).toBe(0)
  })

  it('add() POSTs and appends the new category', async () => {
    mock
      .onPost('/categories')
      .reply(200, { id: '9', name: 'Транспорт', section: 'expense', is_default: false })
    const store = useCategoriesStore()
    const created = await store.add('expense', 'Транспорт')
    expect(created.id).toBe('9')
    expect(store.bySection.expense).toContainEqual(expect.objectContaining({ id: '9' }))
  })

  it('add() returns null for empty/whitespace input', async () => {
    const store = useCategoriesStore()
    expect(await store.add('expense', '   ')).toBeNull()
  })

  it('remove() calls the API and filters from the section', async () => {
    mock.onDelete('/categories/1').reply(204)
    const store = useCategoriesStore()
    store.bySection.expense = [
      { id: '1', name: 'Еда' },
      { id: '2', name: 'Кофе' },
    ]
    await store.remove('1', 'expense')
    expect(store.bySection.expense.map((c) => c.id)).toEqual(['2'])
  })

  it('recordUse + sortByRecentUse: recently-used items come first', () => {
    const store = useCategoriesStore()
    store.recordUse('expense', 'Кофе')
    const sorted = store.sortByRecentUse('expense', [
      { name: 'Еда', is_default: true },
      { name: 'Кофе', is_default: false },
    ])
    expect(sorted[0].name).toBe('Кофе')
  })

  it('sortByRecentUse: default categories rank above non-defaults among unused', () => {
    const store = useCategoriesStore()
    const sorted = store.sortByRecentUse('expense', [
      { name: 'Custom', is_default: false },
      { name: 'Default', is_default: true },
    ])
    expect(sorted.map((c) => c.name)).toEqual(['Default', 'Custom'])
  })

  it('options() returns NSelect-shaped descriptors', () => {
    const store = useCategoriesStore()
    store.bySection.expense = [{ id: '1', name: 'Еда', is_default: true }]
    const opts = store.options('expense')
    expect(opts[0]).toEqual({ label: 'Еда', value: 'Еда', id: '1', is_default: true })
  })
})
