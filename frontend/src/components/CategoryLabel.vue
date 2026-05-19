<template>
  <span class="cat-label" :class="{ 'cat-label-row': true }">
    <span v-if="iconComp || customUrl" class="cat-label-ico" :style="iconWrapStyle">
      <n-icon v-if="iconComp" :component="iconComp" :size="iconPx" :color="tint" />
      <span
        v-else-if="customUrl"
        class="cat-label-custom"
        :style="customMaskStyle"
        role="img"
        :aria-label="name"
      />
    </span>
    <span class="cat-label-text" :style="textStyle">{{ displayName }}</span>
  </span>
</template>

<script setup>
import { computed, watch } from 'vue'
import { NIcon } from 'naive-ui'
import { categoryIcon, resolveCategoryColor } from '@/utils/categoryIcons'
import { useIconCacheStore, parseCustomIconKey } from '@/stores/iconCache'

const props = defineProps({
  name: { type: String, required: true },
  // Optional pre-resolved meta. If omitted we just fall back to the hash-based
  // colour and no icon.
  category: { type: Object, default: null },
  // Px size of the icon. Defaults to ~14px (matches body-small text).
  size: { type: Number, default: 14 },
  // Override the rendered label text (e.g. show the inline category badge but
  // a different display name).
  label: { type: String, default: '' },
  // Inherits font-weight/size from surrounding context by default; consumers
  // can pass extra inline style to set color/weight if needed.
  textStyle: { type: [String, Object], default: '' },
})

const iconCache = useIconCacheStore()
const customId = computed(() => parseCustomIconKey(props.category?.icon))
const iconComp = computed(() => (customId.value ? null : categoryIcon(props.category?.icon)))
const customUrl = computed(() => {
  if (!customId.value) return null
  return iconCache.cache.get(customId.value) ?? null
})

// Kick off the blob fetch as a side-effect rather than inside `customUrl`
// (computed must be pure). Resolved URLs land in the cache and the
// computed re-evaluates via Map-reactivity.
watch(
  customId,
  (id) => {
    if (id && !iconCache.cache.get(id)) {
      iconCache.resolve(id).catch(() => {
        // Best-effort — missing icons fall back to no glyph.
      })
    }
  },
  { immediate: true },
)

const tint = computed(() =>
  resolveCategoryColor({ name: props.name, color: props.category?.color }),
)

const iconPx = computed(() => props.size)
const iconWrapStyle = computed(() => ({
  width: `${iconPx.value}px`,
  height: `${iconPx.value}px`,
}))

const customMaskStyle = computed(() => {
  const url = customUrl.value
  if (!url) return {}
  return {
    width: `${iconPx.value}px`,
    height: `${iconPx.value}px`,
    backgroundColor: tint.value,
    WebkitMaskImage: `url(${url})`,
    maskImage: `url(${url})`,
    WebkitMaskSize: 'contain',
    maskSize: 'contain',
    WebkitMaskRepeat: 'no-repeat',
    maskRepeat: 'no-repeat',
    WebkitMaskPosition: 'center',
    maskPosition: 'center',
  }
})

const displayName = computed(() => props.label || props.name)
</script>

<style scoped>
.cat-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  /* `vertical-align: middle` aligns this inline-flex box to the vertical
     midline of the surrounding line-box. Without it, the box sits on the
     parent's text baseline — which lands the visual centre of the
     icon+text combo slightly above the parent control's vertical centre
     (most noticeable on n-select / n-tag triggers with a 22-28px line
     height). `line-height: 1` collapses the box's own line height so it
     matches the icon's tight 1× height. */
  vertical-align: middle;
  line-height: 1;
}
.cat-label-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  /* Lock the icon's box so neither the inherited line-height nor n-icon's
     own inline-flex layout adds padding above/below the glyph. */
  line-height: 0;
  /* Nudge the icon up 1px to compensate for the asymmetry caused by the
     text's line-height (1.1 leaves slightly more space below the baseline
     than above the cap line). align-items: center on .cat-label centres the
     boxes geometrically, but the *visual* centre of the icon glyph and the
     x-height centre of the text don't coincide — a tiny translate restores
     parity without affecting layout. */
  transform: translateY(-1px);
}
.cat-label-custom {
  display: inline-block;
  flex-shrink: 0;
}
.cat-label-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* `line-height: 1` collapses the descender/ascender padding around the
     text so its visual centre lines up with the icon's centre. The 1.2
     fallback used to leave a ~1px upward offset because the text's line
     box was taller than the icon's. */
  line-height: 1.1;
}
</style>
