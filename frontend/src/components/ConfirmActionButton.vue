<template>
  <n-button
    :type="primed ? 'warning' : type"
    :size="size"
    :disabled="disabled"
    :loading="loading"
    @click="onClick"
    @blur="reset"
  >
    <template v-if="!primed && $slots.icon" #icon>
      <slot name="icon" />
    </template>
    {{ primed ? confirmLabel : label }}
  </n-button>
</template>

<script setup>
import { ref, onUnmounted, watch } from 'vue'
import { NButton } from 'naive-ui'

const props = defineProps({
  label: { type: String, required: true },
  confirmLabel: { type: String, default: 'Подтвердить?' },
  type: { type: String, default: 'default' },
  size: { type: String, default: 'small' },
  disabled: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  timeout: { type: Number, default: 3000 },
})
const emit = defineEmits(['confirm'])

const primed = ref(false)
let timer = null

function reset() {
  primed.value = false
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function onClick() {
  if (props.disabled || props.loading) return
  if (primed.value) {
    reset()
    emit('confirm')
  } else {
    primed.value = true
    timer = setTimeout(reset, props.timeout)
  }
}

watch(
  () => props.disabled,
  (d) => {
    if (d) reset()
  },
)

onUnmounted(() => {
  if (timer) clearTimeout(timer)
})
</script>
