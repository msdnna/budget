import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmActionButton from '../../src/components/ConfirmActionButton.vue'

describe('ConfirmActionButton — two-step confirm flow', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  function mountIt(props = {}) {
    return mount(ConfirmActionButton, {
      props: { label: 'Удалить', ...props },
    })
  }

  it('renders the label and primes on the first click without emitting confirm', async () => {
    const wrapper = mountIt()
    expect(wrapper.text()).toContain('Удалить')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    expect(wrapper.text()).toContain('Подтвердить?')
  })

  it('emits confirm on the second click', async () => {
    const wrapper = mountIt()
    await wrapper.find('button').trigger('click')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('auto-resets after the timeout', async () => {
    const wrapper = mountIt({ timeout: 500 })
    await wrapper.find('button').trigger('click')
    expect(wrapper.text()).toContain('Подтвердить?')
    vi.advanceTimersByTime(600)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Удалить')
  })

  it('disabled flag prevents priming and resets state', async () => {
    const wrapper = mountIt({ disabled: false })
    await wrapper.find('button').trigger('click')
    expect(wrapper.text()).toContain('Подтвердить?')
    await wrapper.setProps({ disabled: true })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Удалить')
  })

  it('uses the custom confirmLabel prop when primed', async () => {
    const wrapper = mountIt({ confirmLabel: 'Точно удалить' })
    await wrapper.find('button').trigger('click')
    expect(wrapper.text()).toContain('Точно удалить')
  })
})
