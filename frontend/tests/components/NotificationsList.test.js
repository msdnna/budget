import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import NotificationsList from '../../src/components/NotificationsList.vue'

function mountIt(items) {
  return mount(NotificationsList, {
    props: { items },
    global: {
      stubs: {
        // Naive UI стабы резолвятся по внутреннему name: (Button, не NButton).
        Button: {
          props: ['disabled'],
          emits: ['click'],
          template: `<button class="nb" :disabled="disabled" @click="$emit('click')"><slot /></button>`,
        },
      },
    },
  })
}

describe('NotificationsList', () => {
  it('shows empty placeholder when there are no items', () => {
    const w = mountIt([])
    expect(w.text()).toContain('Уведомлений нет')
    expect(w.find('.notif-list').exists()).toBe(false)
  })

  it('renders one row per item with type-specific titles', () => {
    const w = mountIt([
      {
        id: 'n1',
        type: 'category_limit_exceeded',
        category_name: 'Транспорт',
        period: '2026-05',
        limit: 1000,
        spent: 1500,
        read: false,
        created_at: '2026-05-15T10:00:00Z',
      },
      {
        id: 'n2',
        type: 'global_limit_exceeded',
        period: '2026-05',
        limit: 2000,
        spent: 2500,
        read: true,
        created_at: '2026-05-15T11:00:00Z',
      },
      {
        id: 'n3',
        type: 'unknown_future_type',
        period: '2026-05',
        limit: 0,
        spent: 0,
        read: true,
        created_at: '2026-05-15T11:00:00Z',
      },
    ])
    const rows = w.findAll('.notif-row')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('Превышен лимит: Транспорт')
    expect(rows[1].text()).toContain('Превышен общий лимит расходов')
    expect(rows[2].text()).toContain('Уведомление')
  })

  it('falls back to "категория" when category_name is missing', () => {
    const w = mountIt([
      {
        id: 'n1',
        type: 'category_limit_exceeded',
        period: '2026-05',
        limit: 0,
        spent: 0,
        read: false,
        created_at: '2026-05-15T10:00:00Z',
      },
    ])
    expect(w.text()).toContain('Превышен лимит: категория')
  })

  it('marks unread rows with the .unread class', () => {
    const w = mountIt([
      {
        id: 'a',
        type: 'global_limit_exceeded',
        period: '2026-05',
        limit: 0,
        spent: 0,
        read: false,
        created_at: '2026-05-15T10:00:00Z',
      },
      {
        id: 'b',
        type: 'global_limit_exceeded',
        period: '2026-05',
        limit: 0,
        spent: 0,
        read: true,
        created_at: '2026-05-15T10:00:00Z',
      },
    ])
    const rows = w.findAll('.notif-row')
    expect(rows[0].classes()).toContain('unread')
    expect(rows[1].classes()).not.toContain('unread')
  })

  it('formats money values via Intl.NumberFormat ru-RU', () => {
    const w = mountIt([
      {
        id: 'n1',
        type: 'global_limit_exceeded',
        period: '2026-05',
        limit: 1500,
        spent: 12345,
        read: false,
        created_at: '2026-05-15T10:00:00Z',
      },
    ])
    const meta = w.find('.notif-row-meta').text()
    // ru-RU uses non-breaking space as group separator — match the digits only.
    expect(meta.replace(/\s/g, '')).toContain('12345/1500')
  })

  it('renders empty money when value is null', () => {
    const w = mountIt([
      {
        id: 'n1',
        type: 'global_limit_exceeded',
        period: '2026-05',
        limit: null,
        spent: null,
        read: false,
        created_at: '2026-05-15T10:00:00Z',
      },
    ])
    // No NaN / null fall-through — just slashes around blanks.
    expect(w.find('.notif-row-meta').text()).not.toContain('null')
    expect(w.find('.notif-row-meta').text()).not.toContain('NaN')
  })

  it('disables "Прочитать все" when all items are read', () => {
    const w = mountIt([
      {
        id: 'a',
        type: 'global_limit_exceeded',
        period: '2026-05',
        limit: 0,
        spent: 0,
        read: true,
        created_at: '2026-05-15T10:00:00Z',
      },
    ])
    expect(w.find('button.nb').attributes('disabled')).toBeDefined()
  })

  it('emits "read-all" when the button is clicked', async () => {
    const w = mountIt([
      {
        id: 'a',
        type: 'global_limit_exceeded',
        period: '2026-05',
        limit: 0,
        spent: 0,
        read: false,
        created_at: '2026-05-15T10:00:00Z',
      },
    ])
    await w.find('button.nb').trigger('click')
    expect(w.emitted('read-all')).toHaveLength(1)
  })
})
