import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import FabButton from '../../src/components/FabButton.vue'

describe('FabButton', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountIt(props = {}) {
    return mount(FabButton, {
      props,
      global: {
        stubs: {
          NIcon: { template: '<span class="icon-stub"><slot /></span>' },
          AddOutline: { template: '<i class="i-add" />' },
        },
      },
    })
  }

  it('renders the default aria-label / title', () => {
    const w = mountIt()
    const btn = w.find('button.fab')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('aria-label')).toBe('Добавить')
    expect(btn.attributes('title')).toBe('Добавить')
  })

  it('honors the custom title prop', () => {
    const w = mountIt({ title: 'Создать запись' })
    expect(w.find('button.fab').attributes('aria-label')).toBe('Создать запись')
  })

  it('emits click on press', async () => {
    const w = mountIt()
    await w.find('button.fab').trigger('click')
    expect(w.emitted('click')).toHaveLength(1)
  })
})
