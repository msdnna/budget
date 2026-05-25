// Shared helpers для адаптивных NDataTable'ов (Forecast / Income / Expenses).
//
// Цель: одна точка истины для responsive-логики таблиц записей:
//   • `useAdaptiveTable(threshold)` — ResizeObserver + единый `compact`-флаг.
//   • Атомарные render-фабрики — pencil-кнопка, ok/cancel, иконочный action
//     с тултипом, popover «•••» для overflow-actions, plain text cell.
//   • `renderEditableCell(item, field, displayNode, opts, ctx)` — общий
//     шаблон inline-edit (content + pencil top-right). Ctx инжектит view-
//     специфичные функции/refs (`isEditing`, `startEdit`, `editValue`, …).
//
// View передаёт свой ctx-объект через замыкание, поэтому ситуация-зависимая
// логика (input-fabric для разных полей) остаётся в самой view.

import { ref, computed, onMounted, onUnmounted, h } from 'vue'
import { NButton, NIcon, NTooltip, NPopover, NPopconfirm } from 'naive-ui'
import { CheckmarkOutline, CloseOutline, EllipsisHorizontalOutline } from '@vicons/ionicons5'

// Единый порог компактификации. Ниже него action-кнопки сворачиваются в
// popover, pencils скрываются, текстовые колонки получают `ellipsis: { tooltip: true }`.
export const COMPACT_TH = 740

export function useAdaptiveTable(threshold = COMPACT_TH) {
  const paneRef = ref(null)
  const paneWidth = ref(0)
  const compact = computed(() => paneWidth.value > 0 && paneWidth.value < threshold)
  let ro = null
  onMounted(() => {
    if (paneRef.value) {
      paneWidth.value = paneRef.value.offsetWidth
      ro = new ResizeObserver(([e]) => {
        paneWidth.value = e.contentRect.width
      })
      ro.observe(paneRef.value)
    }
  })
  onUnmounted(() => ro?.disconnect())
  return { paneRef, paneWidth, compact }
}

// ── Render-фабрики ──────────────────────────────────────────────────────────

export const pencilSvg = () =>
  h(
    'svg',
    {
      width: 12,
      height: 12,
      viewBox: '0 0 24 24',
      fill: 'none',
      stroke: 'currentColor',
      'stroke-width': '2',
      'stroke-linecap': 'round',
      'stroke-linejoin': 'round',
      style: 'display:block',
    },
    [
      h('path', { d: 'M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7' }),
      h('path', { d: 'M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z' }),
    ],
  )

export const pencilBtn = (onClick) =>
  h(
    'span',
    {
      style:
        'opacity:0.28;cursor:pointer;display:inline-flex;align-items:center;margin-left:3px;vertical-align:middle;transition:opacity .15s;color:inherit',
      onMouseenter: (e) => {
        e.currentTarget.style.opacity = '0.72'
      },
      onMouseleave: (e) => {
        e.currentTarget.style.opacity = '0.28'
      },
      onClick,
    },
    [pencilSvg()],
  )

export const okBtn = (onClick) =>
  h(
    NButton,
    {
      size: 'tiny',
      type: 'primary',
      style: 'padding:0 4px;min-width:22px;height:22px',
      onClick,
    },
    { icon: () => h(NIcon, null, { default: () => h(CheckmarkOutline) }) },
  )

export const cancelBtn = (onClick) =>
  h(
    NButton,
    {
      size: 'tiny',
      style: 'padding:0 4px;min-width:22px;height:22px',
      onClick,
    },
    { icon: () => h(NIcon, null, { default: () => h(CloseOutline) }) },
  )

// Иконочная кнопка с тултипом (используется в action-колонке).
export const iconActionBtn = ({ icon, tooltip, type = 'default', loading = false, onClick }) =>
  h(NTooltip, null, {
    trigger: () =>
      h(
        NButton,
        { size: 'small', quaternary: true, type, loading, onClick },
        { icon: () => h(NIcon, null, { default: () => h(icon) }) },
      ),
    default: () => tooltip,
  })

// Action-descriptor → готовая кнопка (с popconfirm-обёрткой если нужно).
export function renderActionButton(a) {
  const btn = iconActionBtn({
    icon: a.icon,
    tooltip: a.label,
    type: a.type,
    loading: a.loading,
    onClick: a.confirm ? undefined : a.onClick,
  })
  if (a.confirm) {
    return h(
      NPopconfirm,
      { onPositiveClick: a.onClick },
      { trigger: () => btn, default: () => a.confirm },
    )
  }
  return btn
}

// Action-descriptor → строка в popover'е (с label-ом).
function renderActionRow(a) {
  const inner = h(
    NButton,
    {
      size: 'small',
      quaternary: true,
      type: a.type || 'default',
      block: true,
      loading: a.loading,
      style: 'justify-content:flex-start;text-align:left',
      onClick: a.confirm ? undefined : a.onClick,
    },
    {
      icon: () => h(NIcon, null, { default: () => h(a.icon) }),
      default: () => a.label,
    },
  )
  if (a.confirm) {
    return h(
      NPopconfirm,
      { onPositiveClick: a.onClick },
      { trigger: () => inner, default: () => a.confirm },
    )
  }
  return inner
}

// Action-cell в compact-режиме: одна «•••» иконка с popover-списком действий.
export function renderActionsPopover(actions) {
  const items = actions.filter(Boolean)
  if (!items.length) return null
  return h(
    NPopover,
    { trigger: 'click', placement: 'bottom-end' },
    {
      trigger: () =>
        h(NTooltip, null, {
          trigger: () =>
            h(
              NButton,
              { size: 'small', quaternary: true },
              {
                icon: () => h(NIcon, null, { default: () => h(EllipsisHorizontalOutline) }),
              },
            ),
          default: () => 'Действия',
        }),
      default: () =>
        h(
          'div',
          { style: 'display:flex;flex-direction:column;gap:2px;min-width:170px' },
          items.map((a) => renderActionRow(a)),
        ),
    },
  )
}

// Action-row с разделением quick (всегда видны) + more (под «⋯»). В compact-
// режиме оба массива схлопываются в один popover (renderActionsPopover).
// Cтандарт: больше 3 действий → quick = 2 наиболее частых (eye/delete),
// остальное уезжает в more.
export function renderActionsRow({ quick = [], more = [], compact = false }) {
  if (compact) return renderActionsPopover([...quick, ...more].filter(Boolean))
  const items = []
  for (const a of quick) {
    if (a) items.push(renderActionButton(a))
  }
  const moreFiltered = more.filter(Boolean)
  if (moreFiltered.length) items.push(renderActionsPopover(moreFiltered))
  return h(
    'div',
    { style: 'display:flex;justify-content:flex-end;align-items:center;gap:2px' },
    items,
  )
}

// Plain-text cell: span с опциональным inline-style. Используется в compact-
// режиме в паре с column-level `ellipsis: { tooltip: true }`.
export function plainTextCell(text, extraStyle = '') {
  return h('span', { style: extraStyle }, text)
}

// Общий шаблон редактируемой cell'ы.
//   • Editing — input + ok/cancel inline.
//   • Wide — content (`flex:1; min-width:0`) + pencil в top-right углу.
//   • Compact (`opts.hidePencil = true`) — только displayNode, чтобы Naive's
//     column ellipsis truncate'ил без обёртки flex-дива.
// `ctx` инжектит view-специфичные функции/refs:
//   { isEditing, startEdit, cancelEdit, confirmEdit, makeInput }
// где `makeInput(field, item)` возвращает VNode редактирующего инпута.
export function renderEditableCell(item, field, displayNode, opts, ctx) {
  if (ctx.isEditing(item.id, field)) {
    return h('div', { style: 'display:flex;align-items:center;gap:2px;min-width:0' }, [
      ctx.makeInput(field, item),
      okBtn(() => ctx.confirmEdit(item, field)),
      cancelBtn(ctx.cancelEdit),
    ])
  }
  if (opts?.hidePencil) return displayNode
  return h('div', { style: 'display:flex;align-items:flex-start;gap:4px;min-width:0;width:100%' }, [
    h('span', { style: 'flex:1;min-width:0' }, [displayNode]),
    pencilBtn(() => ctx.startEdit(item, field)),
  ])
}

// Selection-checkbox для bulk-режима (круглый, primary-цвет когда выбран).
export function selectionCheckbox(checked, onClick, { primaryColor, text3 }) {
  return h(
    'div',
    {
      style: `cursor:pointer;display:flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;border:2px solid ${checked ? primaryColor : text3};background:${checked ? primaryColor : 'transparent'};color:#fff;transition:background .15s,border-color .15s;box-sizing:border-box`,
      onClick,
    },
    checked
      ? [
          h(
            'svg',
            {
              width: 12,
              height: 12,
              viewBox: '0 0 24 24',
              fill: 'none',
              stroke: 'currentColor',
              'stroke-width': '3',
              'stroke-linecap': 'round',
              'stroke-linejoin': 'round',
              style: 'display:block',
            },
            [h('polyline', { points: '20 6 9 17 4 12' })],
          ),
        ]
      : [],
  )
}
