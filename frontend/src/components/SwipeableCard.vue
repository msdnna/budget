<template>
  <div class="sc-root">
    <!-- Right action panel — revealed by swipe-left (offset < 0). -->
    <div
      v-if="$slots.actions"
      class="sc-actions sc-actions-right"
      :class="{ revealed: offset < 0 }"
      :style="{ width: revealWidth + 'px' }"
      @click="reset"
    >
      <slot name="actions" />
    </div>
    <!-- Left action panel — revealed by swipe-right (offset > 0). Width is
         pinned (revealLeftWidth, default 96px = one icon). Hidden if no slot. -->
    <div
      v-if="$slots.actionsLeft"
      class="sc-actions sc-actions-left"
      :class="{ revealed: offset > 0 }"
      :style="{ width: revealLeftWidth + 'px' }"
      @click="reset"
    >
      <slot name="actionsLeft" />
    </div>
    <div
      class="sc-content"
      :class="{ revealed: offset !== 0 }"
      :style="contentStyle"
      @touchstart.passive="onTouchStart"
      @touchmove="onTouchMove"
      @touchend="onTouchEnd"
      @touchcancel="onTouchCancel"
      @click="onClick"
    >
      <slot />
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  // Сколько px вскрывается под полным свайпом влево (open RIGHT panel).
  revealWidth: { type: Number, default: 180 },
  // Сколько px вскрывается под полным свайпом вправо (open LEFT panel).
  // 0 = swipe-right отключён (back-compat для существующих consumers).
  revealLeftWidth: { type: Number, default: 0 },
  // Long-press timeout (мс). 0 = выключить.
  longPressMs: { type: Number, default: 1000 },
  // Минимум px движения чтобы зафиксировать направление gesture'а.
  threshold: { type: Number, default: 10 },
  // Border-radius правых углов action-панели (px). Совпадает с радиусом
  // карточки, которая лежит во вкладыше — чтобы при свайпе action и
  // карта сходились по форме. Default 8 — `.tx-card` в Income/Expenses.
  radius: { type: Number, default: 8 },
})
const emit = defineEmits(['tap', 'longpress'])

const offset = ref(0)
const dragging = ref(false)

// Контент-слой: translateX по offset; при drag отключаем transition чтобы
// движение шло за пальцем, при release включаем — для snap-анимации.
const contentStyle = computed(() => ({
  transform: `translateX(${offset.value}px)`,
  transition: dragging.value ? 'none' : 'transform 0.2s ease-out',
}))

let startX = 0
let startY = 0
let startOffset = 0
let mode = null // null | 'horizontal' | 'vertical'
let longPressTimer = null
let longPressFired = false
let moved = false
let swipeJustHappened = false

function clearLongPress() {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

function onTouchStart(e) {
  if (!e.touches?.length) return
  const t = e.touches[0]
  startX = t.clientX
  startY = t.clientY
  startOffset = offset.value
  mode = null
  longPressFired = false
  moved = false
  swipeJustHappened = false
  dragging.value = false
  if (props.longPressMs > 0) {
    longPressTimer = setTimeout(() => {
      longPressTimer = null
      if (!moved) {
        longPressFired = true
        emit('longpress')
      }
    }, props.longPressMs)
  }
}

function onTouchMove(e) {
  if (!e.touches?.length) return
  const t = e.touches[0]
  const dx = t.clientX - startX
  const dy = t.clientY - startY

  if (mode === null) {
    if (Math.abs(dx) > props.threshold || Math.abs(dy) > props.threshold) {
      moved = true
      clearLongPress()
      // Доминирующая ось решает: горизонталь → swipe, вертикаль → пусть
      // страница скроллится (мы не preventDefault'им).
      mode = Math.abs(dx) > Math.abs(dy) ? 'horizontal' : 'vertical'
      if (mode === 'horizontal') dragging.value = true
    }
  }

  if (mode === 'horizontal') {
    // touchstart был .passive — preventDefault на touchmove всё ещё работает
    // в большинстве браузеров, но не во всех (Safari). Используем
    // `touch-action: pan-y` на content'е чтобы блокировать horizontal
    // scroll'инг страницы во время свайпа.
    if (e.cancelable) e.preventDefault()
    let next = startOffset + dx
    // Свайп-влево раскрывает правый actions-panel (offset → -revealWidth).
    // Свайп-вправо — левый actions-panel (offset → +revealLeftWidth, если slot
    // подключен). Если revealLeftWidth=0 — right-overshoot снапится к 0.
    next = Math.min(props.revealLeftWidth, Math.max(-props.revealWidth, next))
    offset.value = next
  }
}

function onTouchEnd() {
  clearLongPress()
  dragging.value = false
  if (mode === 'horizontal') {
    // Snap: середина reveal-зоны решает open/close, отдельная логика для
    // левой/правой панелей чтобы пороги совпадали с их фактическими ширинами.
    if (offset.value < 0) {
      offset.value = offset.value < -props.revealWidth / 2 ? -props.revealWidth : 0
    } else if (offset.value > 0 && props.revealLeftWidth > 0) {
      offset.value = offset.value > props.revealLeftWidth / 2 ? props.revealLeftWidth : 0
    } else {
      offset.value = 0
    }
    swipeJustHappened = true
    // Сбрасываем флаг чуть позже — synthetic click срабатывает после
    // touchend, и мы должны его подавить.
    setTimeout(() => {
      swipeJustHappened = false
    }, 50)
  }
  mode = null
}

function onTouchCancel() {
  clearLongPress()
  dragging.value = false
  if (mode === 'horizontal') offset.value = startOffset
  mode = null
}

function onClick(e) {
  // Подавляем synthetic click сразу после свайпа.
  if (swipeJustHappened) {
    e.preventDefault()
    e.stopPropagation()
    return
  }
  // Если открыта action-панель — click по контенту закрывает её, не дёргая
  // tap-handler.
  if (offset.value !== 0) {
    offset.value = 0
    e.preventDefault()
    e.stopPropagation()
    return
  }
  // long-press уже отстрелил эмит — пропускаем click чтобы tap не выстрелил
  // вдогонку.
  if (longPressFired) {
    longPressFired = false
    return
  }
  emit('tap')
}

function reset() {
  offset.value = 0
}

defineExpose({ reset })
</script>

<style scoped>
.sc-root {
  position: relative;
  /* `overflow: hidden` обрезает «вылет» .sc-content за левую границу при
     swipe. Border-radius НЕ ставим на .sc-root: при mismatch'е с
     n-card/.tx-card радиусом клип «съедает» border ребёнка у углов.
     Скругление переносим на action-панель (правые углы). */
  overflow: hidden;
}
.sc-actions {
  position: absolute;
  top: 0;
  height: 100%;
  display: flex;
  align-items: stretch;
  z-index: 1;
  visibility: hidden;
  transition: visibility 0s linear 0.2s;
  overflow: hidden;
}
.sc-actions-right {
  right: 0;
  /* Прижимаем правый-панельные кнопки к правому краю reveal-зоны. */
  justify-content: flex-end;
  border-top-right-radius: v-bind('props.radius + "px"');
  border-bottom-right-radius: v-bind('props.radius + "px"');
}
.sc-actions-left {
  left: 0;
  /* Левый action прижат к левому краю карточки. */
  justify-content: flex-start;
  border-top-left-radius: v-bind('props.radius + "px"');
  border-bottom-left-radius: v-bind('props.radius + "px"');
}
/* Single-action left rail: the button stretches to fill the reveal width
   so the card's right edge meets the button's right edge — no gap between
   them during swipe-right. */
.sc-actions-left :slotted(.swipe-action) {
  width: 100%;
}
.sc-actions.revealed {
  visibility: visible;
  transition-delay: 0s;
}
.sc-content {
  position: relative;
  z-index: 2;
  /* Браузер обрабатывает только вертикальный скролл — горизонтальные
     touch'и идут нам на swipe. */
  touch-action: pan-y;
  -webkit-tap-highlight-color: transparent;
}
.sc-content.revealed {
  /* Чтобы было визуально понятно, что под контентом что-то есть — slight
     shadow на правом краю в открытом состоянии. */
  box-shadow: -4px 0 8px rgba(0, 0, 0, 0.08);
}
</style>
