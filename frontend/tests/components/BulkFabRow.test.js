import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import BulkFabRow from '../../src/components/BulkFabRow.vue'

const STUBS = {
  Icon: { template: '<span class="ni"><slot /></span>' },
  TestIcon: { template: '<i class="ti" />' },
}

const Icon = { name: 'TestIcon', template: '<i class="ti" />' }

function mountIt(actions) {
  return mount(BulkFabRow, {
    props: { actions },
    global: { stubs: STUBS },
  })
}

describe('BulkFabRow', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders one button per action with the correct variant class', () => {
    const w = mountIt([
      { icon: Icon, title: 'A', onClick: () => {} },
      { icon: Icon, title: 'B', variant: 'danger', onClick: () => {} },
      { icon: Icon, title: 'C', variant: 'primary', onClick: () => {} },
    ])
    const btns = w.findAll('button.bulk-fab')
    expect(btns).toHaveLength(3)
    expect(btns[0].classes()).toContain('bulk-fab-default')
    expect(btns[1].classes()).toContain('bulk-fab-danger')
    expect(btns[2].classes()).toContain('bulk-fab-primary')
  })

  it('non-confirm action fires onClick immediately', async () => {
    const onClick = vi.fn()
    const w = mountIt([{ icon: Icon, title: 'A', onClick }])
    await w.find('button.bulk-fab').trigger('click')
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('confirm action requires two taps; first marks pending', async () => {
    const onClick = vi.fn()
    const w = mountIt([{ icon: Icon, title: 'A', confirm: true, onClick }])
    const btn = w.find('button.bulk-fab')
    await btn.trigger('click')
    expect(onClick).not.toHaveBeenCalled()
    expect(btn.classes()).toContain('is-pending')
    await btn.trigger('click')
    expect(onClick).toHaveBeenCalledOnce()
    expect(btn.classes()).not.toContain('is-pending')
  })

  it('confirm pending state auto-resets after 2.5s', async () => {
    const onClick = vi.fn()
    const w = mountIt([{ icon: Icon, title: 'A', confirm: true, onClick }])
    const btn = w.find('button.bulk-fab')
    await btn.trigger('click')
    expect(btn.classes()).toContain('is-pending')
    vi.advanceTimersByTime(2600)
    await w.vm.$nextTick()
    expect(btn.classes()).not.toContain('is-pending')
    expect(onClick).not.toHaveBeenCalled()
  })

  it('disabled / loading actions render the disabled attribute', () => {
    const w = mountIt([
      { icon: Icon, title: 'A', disabled: true, onClick: () => {} },
      { icon: Icon, title: 'B', loading: true, onClick: () => {} },
    ])
    const btns = w.findAll('button.bulk-fab')
    expect(btns[0].attributes('disabled')).toBeDefined()
    expect(btns[1].attributes('disabled')).toBeDefined()
  })
})
