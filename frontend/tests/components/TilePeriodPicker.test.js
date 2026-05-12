import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TilePeriodPicker from '../../src/components/TilePeriodPicker.vue'

function mountIt(props = {}) {
  return mount(TilePeriodPicker, {
    props,
    global: {
      stubs: {
        // Naive UI primitives — keep them as slot pass-throughs so we can
        // assert on rendered content without standing up the real components.
        // Stub names match each component's internal `name:` (Vue's resolution
        // key), not the imported `NXxx` alias.
        Popover: {
          template: '<div class="popover-stub"><slot name="trigger" /><slot /></div>',
        },
        Input: {
          props: ['value'],
          template: '<div class="input-stub">{{ value }}<slot name="suffix" /></div>',
        },
        Button: { template: '<button><slot /></button>' },
        Icon: { template: '<span><slot /></span>' },
        CalendarOutline: { template: '<i />' },
      },
    },
  })
}

describe('TilePeriodPicker — month mode', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('formats the selected timestamp as "<MonthName> <Year>"', () => {
    const may2026 = new Date(2026, 4, 15).getTime()
    const wrapper = mountIt({ value: may2026, type: 'month' })
    expect(wrapper.text()).toContain('Май 2026')
  })

  it('renders 12 month tiles and marks the selected one as active', () => {
    const may2026 = new Date(2026, 4, 15).getTime()
    const wrapper = mountIt({ value: may2026, type: 'month' })
    const cells = wrapper.findAll('.tpp-cell')
    expect(cells).toHaveLength(12)
    const active = cells.filter((c) => c.classes().includes('active'))
    expect(active).toHaveLength(1)
    expect(active[0].text()).toBe('Май')
  })

  it('selecting a tile emits update:value with the chosen month timestamp', async () => {
    const may2026 = new Date(2026, 4, 15).getTime()
    const wrapper = mountIt({ value: may2026, type: 'month' })
    await wrapper.findAll('.tpp-cell')[0].trigger('click') // Jan
    const evt = wrapper.emitted('update:value')
    expect(evt).toHaveLength(1)
    const emitted = new Date(evt[0][0])
    expect(emitted.getMonth()).toBe(0)
    expect(emitted.getFullYear()).toBe(2026)
  })

  it('navPrev steps the cursor back by one year', async () => {
    const may2026 = new Date(2026, 4, 15).getTime()
    const wrapper = mountIt({ value: may2026, type: 'month' })
    expect(wrapper.find('.tpp-title').text()).toBe('2026')
    await wrapper.find('.tpp-arrow').trigger('click') // first arrow = prev
    expect(wrapper.find('.tpp-title').text()).toBe('2025')
  })
})

describe('TilePeriodPicker — year mode', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('formats the selected timestamp as just the year', () => {
    const wrapper = mountIt({ value: new Date(2026, 0, 1).getTime(), type: 'year' })
    expect(wrapper.text()).toContain('2026')
  })

  it('year-mode grid spans a decade with two muted boundary cells', () => {
    const wrapper = mountIt({ value: new Date(2026, 0, 1).getTime(), type: 'year' })
    const cells = wrapper.findAll('.tpp-cell')
    expect(cells).toHaveLength(12)
    const muted = cells.filter((c) => c.classes().includes('muted'))
    expect(muted).toHaveLength(2)
  })

  it('clear button emits update:value(null) when clearable + value present', async () => {
    const wrapper = mountIt({
      value: new Date(2026, 0, 1).getTime(),
      type: 'year',
      clearable: true,
    })
    const buttons = wrapper.findAll('button')
    const clearBtn = buttons.find((b) => b.text() === 'Очистить')
    expect(clearBtn).toBeDefined()
    await clearBtn.trigger('click')
    expect(wrapper.emitted('update:value')[0]).toEqual([null])
  })

  it('"Этот год" button picks January 1 of the current year', async () => {
    const wrapper = mountIt({ value: null, type: 'year' })
    const buttons = wrapper.findAll('button')
    const nowBtn = buttons.find((b) => b.text() === 'Этот год')
    await nowBtn.trigger('click')
    const evt = wrapper.emitted('update:value')
    expect(evt).toHaveLength(1)
    const emitted = new Date(evt[0][0])
    expect(emitted.getFullYear()).toBe(new Date().getFullYear())
    expect(emitted.getMonth()).toBe(0)
  })
})
