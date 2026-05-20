/* eslint-disable vue/one-component-per-file */
// Этот файл — тесты render-фабрик: десятки inline-комплейминов с `defineComponent({ render: () => vnode })`
// неизбежны, потому что мы проверяем VNode'ы для каждой утилиты отдельно.
import { describe, it, expect, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'

// adaptiveTable.js вызывает h(NButton, ...) с прямым импортом — стандартные
// global.stubs не сработают (резолвятся по name только при template-render'е).
// Мокаем модули, на которые опирается utils, до импорта самого utils.

vi.mock('naive-ui', () => ({
  NButton: {
    name: 'NButton',
    props: ['loading', 'type'],
    emits: ['click'],
    template: `<button class="nb" :disabled="loading" @click="$emit('click', $event)">
      <slot name="icon" /><slot />
    </button>`,
  },
  NIcon: {
    name: 'NIcon',
    template: '<span class="ni"><slot /></span>',
  },
  NTooltip: {
    name: 'NTooltip',
    template: '<div class="nt"><slot name="trigger" /><slot /></div>',
  },
  NPopover: {
    name: 'NPopover',
    template: '<div class="np"><slot name="trigger" /><slot /></div>',
  },
  NPopconfirm: {
    name: 'NPopconfirm',
    emits: ['positive-click'],
    template: `<div class="npc" @click="$emit('positive-click')">
      <slot name="trigger" /><slot />
    </div>`,
  },
}))

vi.mock('@vicons/ionicons5', () => ({
  CheckmarkOutline: { name: 'CheckmarkOutline', template: '<i class="i-check" />' },
  CloseOutline: { name: 'CloseOutline', template: '<i class="i-close" />' },
  EllipsisHorizontalOutline: {
    name: 'EllipsisHorizontalOutline',
    template: '<i class="i-ellipsis" />',
  },
}))

const {
  COMPACT_TH,
  useAdaptiveTable,
  pencilSvg,
  pencilBtn,
  okBtn,
  cancelBtn,
  iconActionBtn,
  renderActionButton,
  renderActionsPopover,
  plainTextCell,
  renderEditableCell,
  selectionCheckbox,
} = await import('../../src/utils/adaptiveTable.js')

function wrap(vnode) {
  const Host = defineComponent({ render: () => vnode })
  return mount(Host)
}

describe('adaptiveTable — pencilSvg / pencilBtn', () => {
  it('pencilSvg() renders an <svg> with the pencil path', () => {
    const w = wrap(pencilSvg())
    expect(w.find('svg').exists()).toBe(true)
    expect(w.findAll('path').length).toBe(2)
  })

  it('pencilBtn() click fires the supplied handler', async () => {
    const onClick = vi.fn()
    const w = wrap(pencilBtn(onClick))
    const span = w.find('span')
    await span.trigger('click')
    expect(onClick).toHaveBeenCalledOnce()
    await span.trigger('mouseenter')
    await span.trigger('mouseleave')
  })
})

describe('adaptiveTable — okBtn / cancelBtn', () => {
  it('okBtn() renders an NButton with check icon and forwards onClick', async () => {
    const onClick = vi.fn()
    const w = wrap(okBtn(onClick))
    expect(w.find('.i-check').exists()).toBe(true)
    await w.find('.nb').trigger('click')
    expect(onClick).toHaveBeenCalled()
  })

  it('cancelBtn() renders an NButton with close icon and forwards onClick', async () => {
    const onClick = vi.fn()
    const w = wrap(cancelBtn(onClick))
    expect(w.find('.i-close').exists()).toBe(true)
    await w.find('.nb').trigger('click')
    expect(onClick).toHaveBeenCalled()
  })
})

describe('adaptiveTable — iconActionBtn / renderActionButton', () => {
  const Icon = { name: 'TestIcon', template: '<i class="custom-icon" />' }

  it('iconActionBtn renders tooltip + button with the supplied icon', async () => {
    const onClick = vi.fn()
    const w = wrap(iconActionBtn({ icon: Icon, tooltip: 'Удалить', onClick }))
    expect(w.find('.custom-icon').exists()).toBe(true)
    await w.find('.nb').trigger('click')
    expect(onClick).toHaveBeenCalled()
  })

  it('renderActionButton wraps in Popconfirm when confirm prop is set', async () => {
    const onClick = vi.fn()
    const w = wrap(renderActionButton({ icon: Icon, label: 'Удалить', confirm: 'Точно?', onClick }))
    expect(w.find('.npc').exists()).toBe(true)
    // positiveClick на popconfirm триггерит onClick.
    await w.find('.npc').trigger('click')
    expect(onClick).toHaveBeenCalled()
  })

  it('renderActionButton without confirm wires onClick straight to the button', async () => {
    const onClick = vi.fn()
    const w = wrap(renderActionButton({ icon: Icon, label: 'Edit', onClick }))
    expect(w.find('.npc').exists()).toBe(false)
    await w.find('.nb').trigger('click')
    expect(onClick).toHaveBeenCalled()
  })
})

describe('adaptiveTable — renderActionsPopover', () => {
  const Icon = { name: 'TestIcon', template: '<i class="ai" />' }

  it('returns null when no actions are supplied', () => {
    expect(renderActionsPopover([])).toBeNull()
    expect(renderActionsPopover([null, false])).toBeNull()
  })

  it('renders a popover with one row per action', () => {
    const w = wrap(
      renderActionsPopover([
        { icon: Icon, label: 'A', onClick: () => {} },
        { icon: Icon, label: 'B', onClick: () => {} },
      ]),
    )
    expect(w.find('.np').exists()).toBe(true)
    expect(w.text()).toContain('A')
    expect(w.text()).toContain('B')
  })

  it('wraps an action in Popconfirm when it has a confirm prop', () => {
    const w = wrap(
      renderActionsPopover([{ icon: Icon, label: 'X', confirm: 'sure?', onClick: () => {} }]),
    )
    expect(w.find('.npc').exists()).toBe(true)
  })
})

describe('adaptiveTable — plainTextCell', () => {
  it('returns a span with the given text and inline style', () => {
    const w = wrap(plainTextCell('hello', 'color:red'))
    const span = w.find('span')
    expect(span.text()).toBe('hello')
    expect(span.attributes('style')).toContain('color: red')
  })
})

describe('adaptiveTable — renderEditableCell', () => {
  function makeCtx(overrides = {}) {
    return {
      isEditing: () => false,
      startEdit: vi.fn(),
      cancelEdit: vi.fn(),
      confirmEdit: vi.fn(),
      makeInput: () => h('input', { class: 'edit-input' }),
      ...overrides,
    }
  }

  it('view mode: renders displayNode + pencil', () => {
    const ctx = makeCtx()
    const display = h('span', { class: 'disp' }, 'value')
    const w = wrap(renderEditableCell({ id: '1' }, 'name', display, {}, ctx))
    expect(w.find('.disp').exists()).toBe(true)
    expect(w.find('svg').exists()).toBe(true)
  })

  it('view mode with hidePencil: renders only displayNode', () => {
    const ctx = makeCtx()
    const display = h('span', { class: 'disp' }, 'value')
    const w = wrap(renderEditableCell({ id: '1' }, 'name', display, { hidePencil: true }, ctx))
    expect(w.find('.disp').exists()).toBe(true)
    expect(w.find('svg').exists()).toBe(false)
  })

  it('editing mode: renders the input + ok/cancel and wires their callbacks', async () => {
    const ctx = makeCtx({ isEditing: (id, f) => id === '1' && f === 'name' })
    const w = wrap(renderEditableCell({ id: '1' }, 'name', h('span', null, 'x'), {}, ctx))
    expect(w.find('.edit-input').exists()).toBe(true)
    const buttons = w.findAll('.nb')
    expect(buttons.length).toBe(2) // ok + cancel
    await buttons[0].trigger('click')
    expect(ctx.confirmEdit).toHaveBeenCalledWith({ id: '1' }, 'name')
    await buttons[1].trigger('click')
    expect(ctx.cancelEdit).toHaveBeenCalled()
  })

  it('pencil click invokes startEdit', async () => {
    const ctx = makeCtx()
    const display = h('span', null, 'x')
    const w = wrap(renderEditableCell({ id: '7' }, 'amount', display, {}, ctx))
    // pencilBtn — это последний <span> в обёртке, с onClick + svg внутри.
    const spans = w.findAll('span').filter((s) => s.find('svg').exists())
    expect(spans.length).toBeGreaterThan(0)
    await spans[0].trigger('click')
    expect(ctx.startEdit).toHaveBeenCalledWith({ id: '7' }, 'amount')
  })
})

describe('adaptiveTable — selectionCheckbox', () => {
  const palette = { primaryColor: '#22C55E', text3: '#888' }

  it('unchecked: renders empty circle, no checkmark', () => {
    const w = wrap(selectionCheckbox(false, () => {}, palette))
    expect(w.find('svg').exists()).toBe(false)
  })

  it('checked: renders the checkmark', () => {
    const w = wrap(selectionCheckbox(true, () => {}, palette))
    expect(w.find('svg').exists()).toBe(true)
    expect(w.find('polyline').exists()).toBe(true)
  })

  it('click invokes the supplied handler', async () => {
    const onClick = vi.fn()
    const w = wrap(selectionCheckbox(false, onClick, palette))
    await w.find('div').trigger('click')
    expect(onClick).toHaveBeenCalled()
  })
})

describe('adaptiveTable — useAdaptiveTable', () => {
  it('exports the shared COMPACT_TH threshold', () => {
    expect(typeof COMPACT_TH).toBe('number')
    expect(COMPACT_TH).toBe(740)
  })

  it('returns refs + compact computed; ResizeObserver is wired on mount', async () => {
    const captured = []
    globalThis.ResizeObserver = class {
      constructor(cb) {
        captured.push(cb)
      }
      observe() {}
      disconnect() {}
    }

    const Host = defineComponent({
      setup() {
        const { paneRef, paneWidth, compact } = useAdaptiveTable(500)
        return { paneRef, paneWidth, compact }
      },
      template: '<div ref="paneRef" />',
    })
    const w = mount(Host, { attachTo: document.body })
    await nextTick()

    // jsdom/happy-dom offsetWidth=0 → compact stays false initially.
    expect(w.vm.compact).toBe(false)
    expect(captured.length).toBeGreaterThan(0)

    captured[0]([{ contentRect: { width: 320 } }])
    await nextTick()
    expect(w.vm.paneWidth).toBe(320)
    expect(w.vm.compact).toBe(true)

    captured[0]([{ contentRect: { width: 900 } }])
    await nextTick()
    expect(w.vm.compact).toBe(false)

    w.unmount()
  })
})
