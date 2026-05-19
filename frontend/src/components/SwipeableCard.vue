<template>
  <div class="sc-root">
    <!-- Action panel «под» содержимым: видна, когда контент смещён влево.
         Клики по action-кнопкам внутри сначала запускают их handler'ы (event
         bubbling), затем закрывают swipe через @click здесь.
         visibility: hidden пока offset=0 — иначе цветной фон актионов
         «просвечивает» по краям карточки (n-card не покрывает 100% .sc-root). -->
    <div
      v-if="$slots.actions"
      class="sc-actions"
      :class="{ revealed: offset !== 0 }"
      :style="{ width: revealWidth + 'px' }"
      @click="reset"
    >
      <slot name="actions" />
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
  // Сколько px вскрывается под полным свайпом (слева).
  revealWidth: { type: Number, default: 180 },
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
    // Только свайп-влево раскрывает actions (offset уходит в минус). Right-
    // overshoot snap'ится к 0.
    next = Math.min(0, Math.max(-props.revealWidth, next))
    offset.value = next
  }
}

function onTouchEnd() {
  clearLongPress()
  dragging.value = false
  if (mode === 'horizontal') {
    // Snap: если перетянули за середину reveal-зоны — открыть полностью,
    // иначе закрыть.
    if (Math.abs(offset.value) > props.revealWidth / 2) {
      offset.value = -props.revealWidth
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
  right: 0;
  height: 100%;
  display: flex;
  align-items: stretch;
  /* Прижимаем кнопки к правому краю reveal-зоны — если суммарная ширина
     action'ов меньше revealWidth, пустота окажется слева и будет скрыта
     контентом до полного свайпа. */
  justify-content: flex-end;
  z-index: 1;
  visibility: hidden;
  transition: visibility 0s linear 0.2s;
  /* Правые углы скруглены под форму карточки. Левые — острые: они
     стыкуются с .sc-content (карточка во время swipe «срезает» свои
     правые углы через CSS-transition потребителя). overflow:hidden
     обрезает <button>-дети по этой же форме — иначе их прямоугольный
     bg-цвет торчит за скруглением. */
  border-top-right-radius: v-bind('props.radius + "px"');
  border-bottom-right-radius: v-bind('props.radius + "px"');
  overflow: hidden;
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
