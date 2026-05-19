<template>
  <div class="admin-shell" :style="cssVars" :class="{ 'mobile-editing': isMobile && editing }">
    <!-- Section tree (vertical on desktop, horizontal tabs on mobile) -->
    <div class="admin-sections" :class="{ hidden: isMobile && editing }">
      <div class="sections-title">Раздел</div>
      <div
        v-for="s in SECTIONS"
        :key="s.key"
        class="section-row"
        :class="{ active: activeSection === s.key }"
        @click="switchSection(s.key)"
      >
        <span>{{ s.label }}</span>
        <n-tag
          size="tiny"
          :bordered="false"
          :type="activeSection === s.key ? 'primary' : 'default'"
        >
          {{ all[s.key]?.length || 0 }}
        </n-tag>
      </div>
    </div>

    <!-- Categories list / editor swap на mobile через slide+fade (см.
         theme.css `.mobile-slide-*`). v-show вместо :class="{hidden}" —
         чтобы Vue `<Transition>` мог цеплять enter/leave classes; десктоп
         не задет, transition-CSS gated @media (max-width: 767px). -->
    <Transition name="mobile-slide">
      <div v-show="!isMobile || !editing" class="admin-list">
        <div class="list-header">
          <n-text strong style="font-size: 14px">{{ sectionLabel }}</n-text>
          <div class="list-header-actions">
            <n-spin v-if="loading" :size="14" />
            <!-- На мобильном добавление через FAB снизу-справа (см. ниже). -->
            <n-button
              v-if="!isMobile"
              size="small"
              type="primary"
              :disabled="loading"
              @click="startCreate"
            >
              <template #icon><n-icon :component="AddOutline" /></template>
              Добавить
            </n-button>
          </div>
        </div>
        <!-- `.list-rows` sits as a direct flex child of `.admin-list` so the
           parent's `min-height: 0` propagates and `overflow-y: auto` kicks
           in. Wrapping in `<n-spin>` injects intermediate divs that don't
           forward flex sizing and breaks the scroll containment. -->
        <div class="list-rows">
          <div
            v-for="c in displayCategories"
            :key="c.id"
            class="cat-row"
            :class="{ active: selectedId === c.id, 'cat-row-with-limit': limitProgressFor(c.id) }"
            @click="select(c)"
          >
            <div class="cat-row-main">
              <div class="cat-badge" :style="{ backgroundColor: resolveColor(c) }">
                <n-icon
                  v-if="iconKind(c.icon) === 'builtin'"
                  :component="categoryIcon(c.icon)"
                  :size="18"
                  color="#fff"
                />
                <img
                  v-else-if="customUrlFor(c.icon)"
                  :src="customUrlFor(c.icon)"
                  class="cat-badge-img"
                  :style="badgeImgStyle(c.icon_scale)"
                  alt=""
                />
              </div>
              <span class="cat-name">{{ c.name }}</span>
              <n-tag v-if="!c.is_default" size="tiny" :bordered="false" type="info">польз.</n-tag>
            </div>
            <div v-if="limitProgressFor(c.id)" class="cat-row-limit">
              <n-progress
                type="line"
                :percentage="Math.min(100, limitProgressFor(c.id).percent)"
                :show-indicator="false"
                :color="limitProgressColor(limitProgressFor(c.id).percent)"
                :height="6"
                :border-radius="3"
              />
              <n-text depth="3" class="cat-row-limit-text">
                {{ formatMoney(limitProgressFor(c.id).spent) }} /
                {{ formatMoney(limitProgressFor(c.id).limit) }} ₽ ({{
                  Math.round(limitProgressFor(c.id).percent)
                }}%)
              </n-text>
            </div>
          </div>
          <n-empty
            v-if="!loading && !displayCategories.length"
            description="Пусто"
            style="padding: 24px 0"
          />
        </div>
      </div>
    </Transition>

    <!-- Editor — mirror admin-list swap-обёртка. -->
    <Transition name="mobile-slide">
      <div v-show="!isMobile || editing" class="admin-editor">
        <template v-if="editing">
          <div class="editor-header">
            <n-button v-if="isMobile" text size="small" title="Назад" @click="cancelEdit">
              <template #icon><n-icon :component="ArrowBackOutline" /></template>
            </n-button>
            <n-text strong style="font-size: 15px; flex: 1">
              {{ isCreating ? 'Новая категория' : editing.name }}
            </n-text>
            <n-button v-if="!isMobile" quaternary circle size="small" @click="cancelEdit">
              <template #icon><n-icon :component="CloseOutline" /></template>
            </n-button>
          </div>

          <div class="editor-body">
            <n-form label-placement="top" require-mark-placement="right-hanging">
              <n-form-item label="Название">
                <n-input
                  v-model:value="editForm.name"
                  :disabled="!isCreating && editing.is_default"
                  placeholder="Название категории"
                />
              </n-form-item>

              <n-form-item label="Цвет">
                <n-color-picker
                  v-model:value="editForm.color"
                  :swatches="FALLBACK_PALETTE"
                  :show-alpha="false"
                  :modes="['hex']"
                />
              </n-form-item>

              <n-form-item label="Иконка">
                <!-- Wrap picker + hint in a flex-column so the picker stretches
                 to 100% of the form-item width (Naive's default row layout
                 collapses the grid to its minimum content otherwise). -->
                <div class="icon-section">
                  <div class="icon-picker">
                    <button
                      type="button"
                      class="icon-tile"
                      :class="{ active: !editForm.icon }"
                      :style="{ backgroundColor: editForm.color }"
                      title="Без иконки"
                      @click="editForm.icon = ''"
                    >
                      <span class="icon-tile-none">—</span>
                    </button>
                    <button
                      v-for="key in recentIcons"
                      :key="key"
                      type="button"
                      class="icon-tile"
                      :class="{ active: editForm.icon === key }"
                      :style="{ backgroundColor: editForm.color }"
                      :title="key"
                      @click="pickBuiltin(key)"
                    >
                      <n-icon :component="categoryIcon(key)" :size="22" color="#fff" />
                    </button>
                    <button
                      v-for="i in uploadedIcons"
                      :key="i.id"
                      type="button"
                      class="icon-tile"
                      :class="{ active: editForm.icon === 'custom:' + i.id }"
                      :style="{ backgroundColor: editForm.color }"
                      :title="`custom:${i.id}`"
                      @click="editForm.icon = 'custom:' + i.id"
                    >
                      <!-- Tiles render at scale 1 — the dedicated preview
                       below the slider shows the chosen scale effect. -->
                      <img
                        v-if="iconCache.cache.get(i.id)"
                        :src="iconCache.cache.get(i.id)"
                        class="icon-tile-img"
                        alt=""
                      />
                      <n-spin v-else size="small" />
                    </button>
                    <!-- Открытие через явный @click + NModal (на любых
                       экранах). Раньше использовали NPopover, но на узком
                       вьюпорте он вылетал за край: Naive не shift'ит
                       контент горизонтально, чтобы вписаться. -->
                    <button
                      type="button"
                      class="icon-tile icon-tile-upload"
                      title="Найти ещё или загрузить свою"
                      @click="pickerOpen = true"
                    >
                      <n-icon :component="AddOutline" :size="22" />
                    </button>
                    <n-modal
                      v-model:show="pickerOpen"
                      preset="card"
                      title="Выбор иконки"
                      :style="{ width: 'min(420px, calc(100vw - 32px))' }"
                    >
                      <div class="picker-popover">
                        <n-input
                          ref="pickerSearchRef"
                          v-model:value="iconQuery"
                          placeholder="Поиск (Cart, Bicycle, Phone…)"
                          clearable
                          size="small"
                        />
                        <div class="picker-results">
                          <button
                            v-for="name in iconSearchResults"
                            :key="name"
                            type="button"
                            class="picker-tile"
                            :style="{ backgroundColor: editForm.color }"
                            :title="name"
                            @click="pickFromSearch(name)"
                          >
                            <n-icon :component="iconByName(name)" :size="22" color="#fff" />
                          </button>
                          <div v-if="iconQuery && !iconSearchResults.length" class="picker-hint">
                            Ничего не найдено
                          </div>
                          <div v-else-if="!iconQuery" class="picker-hint">
                            Введите название на английском
                          </div>
                          <div
                            v-else-if="iconSearchResults.length === MAX_SEARCH"
                            class="picker-hint"
                          >
                            Показаны первые {{ MAX_SEARCH }} — уточните запрос
                          </div>
                        </div>
                        <n-divider style="margin: 4px 0" />
                        <label class="picker-upload" :class="{ uploading }">
                          <input
                            ref="fileInput"
                            type="file"
                            accept="image/png,image/svg+xml"
                            hidden
                            @change="handleUpload"
                          />
                          <n-icon v-if="!uploading" :component="CloudUploadOutline" :size="18" />
                          <n-spin v-else :size="14" />
                          <span>Загрузить свою иконку</span>
                        </label>
                      </div>
                    </n-modal>
                  </div>
                  <n-text depth="3" style="font-size: 12px">
                    Сетка собирает недавно использованные. PNG (с альфой, до 512×512) или SVG, до
                    512 KB — через «+».
                  </n-text>
                </div>
              </n-form-item>

              <n-form-item v-if="activeSection === 'expense'" label="Месячный лимит (₽)">
                <div class="limit-row">
                  <n-input-number
                    v-model:value="editForm.monthly_limit"
                    :min="0"
                    :step="100"
                    placeholder="Без лимита"
                    clearable
                    style="flex: 1"
                  />
                  <n-text depth="3" style="font-size: 12px">
                    Сбрасывается 1-го числа каждого месяца.
                  </n-text>
                </div>
              </n-form-item>

              <n-form-item v-if="showScale" label="Масштаб иконки в бейдже">
                <div class="scale-row">
                  <n-slider
                    v-model:value="editForm.icon_scale"
                    :min="0.6"
                    :max="2.2"
                    :step="0.05"
                    :tooltip="false"
                    style="flex: 1"
                  />
                  <div class="scale-preview" :style="{ backgroundColor: editForm.color }">
                    <img
                      v-if="previewCustomUrl"
                      :src="previewCustomUrl"
                      :style="previewImgStyle"
                      alt=""
                    />
                  </div>
                  <n-text depth="3" style="font-size: 12px; min-width: 48px; text-align: right">
                    ×{{ editForm.icon_scale.toFixed(2) }}
                  </n-text>
                </div>
              </n-form-item>
            </n-form>
          </div>

          <div class="editor-footer">
            <n-popconfirm
              v-if="!isCreating && !editing.is_default"
              positive-text="Удалить"
              negative-text="Отмена"
              @positive-click="handleDelete"
            >
              <template #trigger>
                <n-button type="error" ghost :disabled="saving">Удалить</n-button>
              </template>
              Удалить категорию «{{ editing.name }}»? Транзакции с этой категорией останутся.
            </n-popconfirm>
            <span v-else />
            <n-space>
              <n-button :disabled="saving" @click="cancelEdit">Отмена</n-button>
              <n-button type="primary" :loading="saving" @click="saveEdit">
                {{ isCreating ? 'Создать' : 'Сохранить' }}
              </n-button>
            </n-space>
          </div>
        </template>
        <div v-else-if="!isMobile" class="editor-empty">
          <n-empty description="Выберите категорию слева или нажмите «Добавить»" />
        </div>
      </div>
    </Transition>

    <!-- Mobile FAB — добавить категорию в активной секции. Скрыт во время
         edit/create, потому что редактор уже на месте списка. -->
    <FabButton
      v-if="isMobile && !editing"
      :title="`Добавить ${sectionLabel.toLowerCase()}`"
      @click="startCreate"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import {
  NSpace,
  NText,
  NButton,
  NSpin,
  NEmpty,
  NIcon,
  NTag,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NColorPicker,
  NPopconfirm,
  NModal,
  NSlider,
  NDivider,
  NProgress,
  useMessage,
} from 'naive-ui'
import * as Ionicons5 from '@vicons/ionicons5'
const { AddOutline, CloseOutline, CloudUploadOutline, ArrowBackOutline } = Ionicons5
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import { categories as catApi, icons as iconsApi } from '@/api'
import {
  CATEGORY_ICON_ORDER,
  FALLBACK_PALETTE,
  ICON_NAMES,
  categoryIcon,
  iconKind,
  normalizeIconKey,
  resolveCategoryColor,
} from '@/utils/categoryIcons'
import { useIconCacheStore, parseCustomIconKey } from '@/stores/iconCache'
import FabButton from '@/components/FabButton.vue'

const message = useMessage()
const iconCache = useIconCacheStore()
const { palette, primaryColor } = storeToRefs(useThemeStore())

// CSS variables drive the colours of the plain wrapper divs (admin-sections,
// admin-list, admin-editor and their inner rows). Naive UI components inside
// auto-theme themselves; these plain blocks don't, so we surface theme
// tokens via CSS vars and consume them from `<style scoped>`.
const cssVars = computed(() => ({
  '--admin-surface': palette.value.surface,
  '--admin-surface-alt': palette.value.surfaceAlt,
  '--admin-card-bg': palette.value.cardSurface,
  '--admin-border': palette.value.border,
  '--admin-hover': palette.value.hover,
  '--admin-text1': palette.value.text1,
  '--admin-text2': palette.value.text2,
  '--admin-text3': palette.value.text3,
  '--admin-primary': primaryColor.value,
  // 12% alpha primary for the "active" row highlight — looks good on both
  // themes without baking in a darker / lighter band per mode.
  '--admin-primary-soft': hexToAlpha(primaryColor.value, 0.16),
}))

function hexToAlpha(hex, alpha) {
  if (!hex || hex.length !== 7 || hex[0] !== '#') return `rgba(32, 128, 240, ${alpha})`
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

const SECTIONS = [
  { key: 'expense', label: 'Расходы' },
  { key: 'income', label: 'Доходы' },
  { key: 'wishlist', label: 'Желания' },
]

const activeSection = ref('expense')
const loading = ref(false)
const all = ref({ expense: [], income: [], wishlist: [] })
const uploadedIcons = ref([])
// Limit progress for the current month, keyed by category_id. Refreshed
// alongside the category list — admin edits to monthly_limit / fresh
// expense writes will only be reflected after the next reload.
const limitProgressById = ref(new Map())
const limitsTotalLimit = ref(0)
const limitsTotalSpent = ref(0)

// Responsive — keeps DOM consistent with App.vue's 768px breakpoint so the
// list/editor switch fires on the same threshold as the bottom-nav swap.
const windowWidth = ref(window.innerWidth)
const isMobile = computed(() => windowWidth.value < 768)
function onWinResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onWinResize))
onUnmounted(() => window.removeEventListener('resize', onWinResize))

async function loadAll() {
  loading.value = true
  try {
    const [catRes, iconRes] = await Promise.all([catApi.all(), iconsApi.list()])
    all.value = {
      expense: catRes.data?.expense || [],
      income: catRes.data?.income || [],
      wishlist: catRes.data?.wishlist || [],
    }
    uploadedIcons.value = iconRes.data || []
    for (const i of uploadedIcons.value) iconCache.resolve(i.id).catch(() => {})
    await loadLimitsProgress()
  } finally {
    loading.value = false
  }
}

// Refresh just the limits-progress map without re-pulling the whole
// category list. Called after admin edits to monthly_limit so the
// progress bars in the cat-row list reflect the new state immediately.
async function loadLimitsProgress() {
  try {
    const { data: lp } = await catApi.limitsProgress()
    if (!lp) {
      limitProgressById.value = new Map()
      limitsTotalLimit.value = 0
      limitsTotalSpent.value = 0
      return
    }
    const map = new Map()
    for (const row of lp.categories || []) map.set(row.category_id, row)
    limitProgressById.value = map
    limitsTotalLimit.value = lp.total_limit || 0
    limitsTotalSpent.value = lp.total_spent || 0
  } catch {
    // Soft-fail: stale bars stay until the next reload.
  }
}

// Progress-bar tint scales green→amber→red as spending climbs toward
// (and past) the limit. Uses theme-aware palette tokens so light/dark
// both look balanced (same scheme is mirrored in ExpensesView).
function limitProgressColor(percent) {
  if (percent >= 100) return palette.value.expense
  if (percent >= 80) return '#F59E0B'
  return palette.value.income
}

function limitProgressFor(catId) {
  return limitProgressById.value.get(catId) || null
}

function formatMoney(value) {
  if (value == null) return ''
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(value)
}

onMounted(loadAll)

const displayCategories = computed(() => all.value[activeSection.value] || [])
const sectionLabel = computed(
  () => SECTIONS.find((s) => s.key === activeSection.value)?.label || '',
)

function resolveColor(c) {
  return resolveCategoryColor({ name: c.name, color: c.color })
}
function customUrlFor(key) {
  const id = parseCustomIconKey(key)
  if (!id) return null
  return iconCache.cache.get(id) || null
}
// Scaling only applies to custom icons inside badges/list rows. Builtin
// icons are sized for their badge by default and ignore the field.
function badgeImgStyle(scale, iconKey) {
  const base = 22
  const s = scale && scale > 0 ? scale : 1
  // No scaling needed if the icon isn't custom — caller passes only when
  // it knows the key is custom, but the function tolerates being called
  // for builtins (no-op).
  if (iconKey && !parseCustomIconKey(iconKey)) return null
  const size = base * s
  return { width: size + 'px', height: size + 'px' }
}

function switchSection(key) {
  activeSection.value = key
  cancelEdit()
}

// ── Recently-used icons picker ──────────────────────────────────────
// Grid is locked to 16 columns × 2 rows = 32 cells: «—» + N recent + M
// custom + «+» = 32, so recent fills `30 - M` slots. The localStorage
// reserve keeps the full history (capped at 30) for the case where a
// custom icon is later removed and a recent slot opens up again.
const RECENT_STORAGE_KEY = 'category-icons-recent-v1'
const RECENT_STORAGE_CAP = 30
const GRID_VISIBLE_TOTAL = 30 // excluding «—» and «+» tiles
const MAX_SEARCH = 96

function loadRecent() {
  try {
    const raw = localStorage.getItem(RECENT_STORAGE_KEY)
    if (!raw) return [...CATEGORY_ICON_ORDER]
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return [...CATEGORY_ICON_ORDER]
    const cleaned = parsed.filter((k) => typeof k === 'string' && k.length > 0)
    return cleaned.length ? cleaned : [...CATEGORY_ICON_ORDER]
  } catch {
    return [...CATEGORY_ICON_ORDER]
  }
}

const recentStorage = ref(loadRecent())

// Trim builtin recents so the picker stays within 32 tiles total. Custom
// icons always show — they're hand-uploaded admin assets that mustn't be
// displaced by builtin recent churn.
const recentIcons = computed(() => {
  const maxBuiltin = Math.max(0, GRID_VISIBLE_TOTAL - uploadedIcons.value.length)
  return recentStorage.value.slice(0, maxBuiltin)
})

function bumpRecent(rawKey) {
  if (!rawKey) return
  const key = normalizeIconKey(rawKey)
  const next = [key, ...recentStorage.value.filter((k) => k !== key)].slice(0, RECENT_STORAGE_CAP)
  recentStorage.value = next
  try {
    localStorage.setItem(RECENT_STORAGE_KEY, JSON.stringify(next))
  } catch {
    // Quota exceeded or storage disabled — recent list is purely a UI
    // ergonomics aid, no point surfacing this to the user.
  }
}

function pickBuiltin(key) {
  editForm.value.icon = key
  bumpRecent(key)
}

// ── Search popover for the full ionicons5 catalog ───────────────────
// NPopover with `trigger="click"` handles both opening on the + tile
// click AND click-outside dismissal natively. This watcher resets the
// search and autofocuses the input each time the popover opens.
const pickerOpen = ref(false)
const iconQuery = ref('')
const pickerSearchRef = ref(null)

watch(pickerOpen, (open) => {
  if (open) {
    iconQuery.value = ''
    nextTick(() => {
      pickerSearchRef.value?.focus?.()
    })
  }
})

function iconByName(name) {
  return Ionicons5[name] || null
}

const iconSearchResults = computed(() => {
  const q = iconQuery.value.trim().toLowerCase()
  if (!q) return []
  const out = []
  for (const name of ICON_NAMES) {
    if (name.toLowerCase().includes(q)) {
      out.push(name)
      if (out.length >= MAX_SEARCH) break
    }
  }
  return out
})

function pickFromSearch(name) {
  const normalized = normalizeIconKey(name)
  editForm.value.icon = normalized
  bumpRecent(normalized)
  pickerOpen.value = false
}

// ── Editor state ────────────────────────────────────────────────────
const editing = ref(null)
const editForm = ref({ name: '', color: '', icon: '', icon_scale: 1, monthly_limit: null })
const selectedId = computed(() => editing.value?.id || null)
const isCreating = computed(() => editing.value && !editing.value.id)
const saving = ref(false)
const uploading = ref(false)
const fileInput = ref(null)

const showScale = computed(() => !!parseCustomIconKey(editForm.value.icon))

// Live WYSIWYG preview for the scale slider: pulls the cached blob URL
// of the currently-selected custom icon and applies the dialled scale.
const previewCustomUrl = computed(() => {
  const id = parseCustomIconKey(editForm.value.icon)
  return id ? iconCache.cache.get(id) || null : null
})
const previewImgStyle = computed(() => {
  const base = 28
  const s = editForm.value.icon_scale > 0 ? editForm.value.icon_scale : 1
  const size = base * s
  return {
    width: size + 'px',
    height: size + 'px',
    objectFit: 'contain',
    display: 'block',
  }
})

function select(c) {
  editing.value = c
  editForm.value = {
    name: c.name,
    color: c.color || resolveCategoryColor({ name: c.name }),
    icon: c.icon || '',
    icon_scale: c.icon_scale && c.icon_scale > 0 ? c.icon_scale : 1,
    monthly_limit: typeof c.monthly_limit === 'number' ? c.monthly_limit : null,
  }
  const cid = parseCustomIconKey(c.icon)
  if (cid) iconCache.resolve(cid).catch(() => {})
}

function startCreate() {
  editing.value = { section: activeSection.value, is_default: false }
  editForm.value = {
    name: '',
    color: FALLBACK_PALETTE[0],
    icon: '',
    icon_scale: 1,
    monthly_limit: null,
  }
}

function cancelEdit() {
  editing.value = null
}

async function saveEdit() {
  if (!editing.value) return
  saving.value = true
  try {
    if (isCreating.value) {
      const { data: created } = await catApi.create({
        section: activeSection.value,
        name: editForm.value.name.trim(),
        color: editForm.value.color || '',
        icon: editForm.value.icon || '',
      })
      // Scale + monthly_limit aren't part of Create — issue a follow-up
      // PATCH if the user set them during creation.
      let finalRow = created
      const followUp = {}
      if (parseCustomIconKey(created.icon) && editForm.value.icon_scale !== 1) {
        followUp.icon_scale = editForm.value.icon_scale
      }
      if (activeSection.value === 'expense' && typeof editForm.value.monthly_limit === 'number') {
        followUp.monthly_limit = editForm.value.monthly_limit
      }
      if (Object.keys(followUp).length) {
        const { data: patched } = await catApi.update(created.id, followUp)
        finalRow = patched
      }
      appendToList(finalRow)
      select(finalRow)
      // If the new category was created with a limit, the progress bar
      // should appear immediately — refresh in the background.
      if (activeSection.value === 'expense' && typeof editForm.value.monthly_limit === 'number') {
        loadLimitsProgress()
      }
      message.success('Создано')
    } else {
      const patch = {
        color: editForm.value.color || '',
        icon: editForm.value.icon || '',
        icon_scale: parseCustomIconKey(editForm.value.icon) ? editForm.value.icon_scale : 0,
      }
      if (!editing.value.is_default && editForm.value.name.trim() !== editing.value.name) {
        patch.name = editForm.value.name.trim()
      }
      // monthly_limit uses tri-state: present number = set; explicit `null`
      // = clear; key absent = leave unchanged. Backend's NullableFloat
      // custom unmarshaler distinguishes the three on the server side.
      if (editing.value.section === 'expense') {
        const prev =
          typeof editing.value.monthly_limit === 'number' ? editing.value.monthly_limit : null
        const next =
          typeof editForm.value.monthly_limit === 'number' ? editForm.value.monthly_limit : null
        if (prev !== next) patch.monthly_limit = next
      }
      const { data } = await catApi.update(editing.value.id, patch)
      replaceInList(data)
      select(data)
      // Refresh progress map when a limit was touched (or could have been
      // touched — we send `patch.monthly_limit` only on real change). The
      // check stays scoped to expense edits to avoid pointless refetches.
      if (editing.value.section === 'expense' && 'monthly_limit' in patch) {
        loadLimitsProgress()
      }
      message.success('Сохранено')
    }
  } catch (err) {
    message.error(err?.message || 'Ошибка сохранения')
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  if (!editing.value?.id || editing.value.is_default) return
  saving.value = true
  try {
    const wasLimited = limitProgressById.value.has(editing.value.id)
    await catApi.remove(editing.value.id)
    removeFromList(editing.value.id)
    cancelEdit()
    if (wasLimited) loadLimitsProgress()
    message.success('Удалено')
  } catch (err) {
    message.error(err?.message || 'Ошибка удаления')
  } finally {
    saving.value = false
  }
}

function appendToList(c) {
  const list = all.value[c.section] || []
  const next = [...list, c].sort((a, b) => {
    if (a.is_default !== b.is_default) return a.is_default ? -1 : 1
    return a.name.localeCompare(b.name)
  })
  all.value = { ...all.value, [c.section]: next }
}

function replaceInList(updated) {
  const list = all.value[updated.section] || []
  const idx = list.findIndex((c) => c.id === updated.id)
  if (idx === -1) return
  const next = [...list]
  next[idx] = updated
  all.value = { ...all.value, [updated.section]: next }
}

function removeFromList(id) {
  for (const s of Object.keys(all.value)) {
    const next = all.value[s].filter((c) => c.id !== id)
    if (next.length !== all.value[s].length) {
      all.value = { ...all.value, [s]: next }
    }
  }
}

async function handleUpload(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 512 * 1024) {
    message.error('Файл больше 512 KB')
    e.target.value = ''
    return
  }
  uploading.value = true
  try {
    const { data } = await iconsApi.upload(file)
    uploadedIcons.value = [data, ...uploadedIcons.value]
    await iconCache.resolve(data.id)
    editForm.value.icon = 'custom:' + data.id
    pickerOpen.value = false
    message.success('Иконка загружена')
  } catch (err) {
    message.error(err?.message || 'Ошибка загрузки')
  } finally {
    uploading.value = false
    e.target.value = ''
  }
}
</script>

<style scoped>
.admin-shell {
  display: grid;
  grid-template-columns: 200px 360px 1fr;
  gap: 16px;
  align-items: stretch;
  /* Fixed height tied to the actual chrome around the view so the parent
     `<n-layout style="overflow-y:auto">` never picks up an outer scrollbar.
     64px = `--app-header-h`, 48px = n-layout-content's 24px padding × 2.
     Without this the inner `overflow-y: auto` on the list row container
     has nothing to constrain against and the shell grows tall, scrolling
     the entire layout instead of just the list. */
  height: calc(100vh - var(--app-header-h, 64px) - 48px);
  min-height: 0;
}

/* Mobile: header (64px) + content padding (16 top + 80 bottom for the
   fixed bottom tab bar). Tab bar itself is `position: fixed`, so it's
   covered by padding-bottom in the layout, not subtracted here. */
@media (max-width: 767px) {
  .admin-shell {
    height: calc(100vh - var(--app-header-h, 64px) - 96px);
  }
}

.admin-sections,
.admin-list,
.admin-editor {
  /* Theme-aware bg, совпадает с Naive NCard.color (см. tokens.js
     cardSurface). Раньше palette.surface был светлее и «выпирал». */
  background: var(--admin-card-bg);
  border: 1px solid var(--admin-border);
  /* 3px = Naive default Card.borderRadius — единый стиль с NCard. */
  border-radius: 3px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  /* `min-height: 0` lets the flex child shrink below its content size,
     which is what enables the inner `overflow-y: auto` to take effect. */
  min-height: 0;
  overflow: hidden;
  color: var(--admin-text1);
}

.sections-title {
  font-size: 11px;
  text-transform: uppercase;
  color: var(--admin-text3);
  letter-spacing: 0.6px;
  padding: 4px 8px 8px;
}
.section-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 3px;
  cursor: pointer;
  color: var(--admin-text2);
  transition:
    background 0.12s,
    color 0.12s;
}
.section-row:hover {
  background: var(--admin-hover);
  color: var(--admin-text1);
}
.section-row.active {
  background: var(--admin-primary-soft);
  color: var(--admin-primary);
  font-weight: 500;
}

.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px 8px;
  border-bottom: 1px solid var(--admin-border);
  margin-bottom: 6px;
  flex-shrink: 0;
}
.list-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.list-rows {
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  flex: 1 1 auto;
  min-height: 0;
  /* Theme-neutral subtle scrollbar — translucent gray works on both light
     and dark, and reads as decoration rather than an OS chrome strip. */
  scrollbar-width: thin;
  scrollbar-color: rgba(127, 127, 127, 0.3) transparent;
}
.list-rows::-webkit-scrollbar {
  width: 6px;
}
.list-rows::-webkit-scrollbar-track {
  background: transparent;
}
.list-rows::-webkit-scrollbar-thumb {
  background: rgba(127, 127, 127, 0.25);
  border-radius: 3px;
}
.list-rows::-webkit-scrollbar-thumb:hover {
  background: rgba(127, 127, 127, 0.45);
}
.cat-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 6px;
  cursor: pointer;
  border-radius: 6px;
  color: var(--admin-text1);
  transition: background 0.12s;
}
.cat-row-main {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cat-row-limit {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: 42px; /* align with name column (badge 32px + gap 10px) */
}
.cat-row-limit-text {
  font-size: 11px;
}
.limit-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.cat-row:hover {
  background: var(--admin-hover);
}
.cat-row.active {
  background: var(--admin-primary-soft);
}
.cat-badge {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}
.cat-badge-img {
  object-fit: contain;
  display: block;
}
.cat-name {
  flex: 1 1 auto;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border);
  margin-bottom: 12px;
  flex-shrink: 0;
}
.editor-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  /* Лёгкий inset, чтобы фокус-кольца чекбоксов/инпутов слева не клипались
     при `overflow:hidden` родительского `.admin-editor`. */
  padding: 2px 4px 2px 4px;
  scrollbar-width: thin;
  scrollbar-color: rgba(127, 127, 127, 0.3) transparent;
}
.editor-body::-webkit-scrollbar {
  width: 6px;
}
.editor-body::-webkit-scrollbar-track {
  background: transparent;
}
.editor-body::-webkit-scrollbar-thumb {
  background: rgba(127, 127, 127, 0.25);
  border-radius: 3px;
}
.editor-body::-webkit-scrollbar-thumb:hover {
  background: rgba(127, 127, 127, 0.45);
}
.editor-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--admin-border);
  margin-top: 8px;
  flex-shrink: 0;
}
.editor-empty {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.icon-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.icon-picker {
  display: grid;
  /* Fixed 16 columns × 2 rows = 32 cells total. Tiles squish if the
     container is narrower; on cramped layouts the grid wraps to extra
     rows which is acceptable. The cell count budget (1 «—» + recent +
     customs + 1 «+» ≤ 32) is enforced JS-side via GRID_VISIBLE_TOTAL. */
  grid-template-columns: repeat(16, 1fr);
  gap: 6px;
  padding: 4px;
  width: 100%;
}
.icon-tile {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 10px;
  border: 2px solid transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
  overflow: hidden;
  transition:
    border-color 0.12s,
    transform 0.12s;
}
.icon-tile:hover {
  transform: translateY(-1px);
}
.icon-tile.active {
  border-color: var(--admin-surface);
  box-shadow: 0 0 0 2px var(--admin-primary);
}
.icon-tile-img {
  object-fit: contain;
  display: block;
  max-width: 100%;
  max-height: 100%;
}
.icon-tile-none {
  font-size: 20px;
  color: #fff;
  opacity: 0.75;
}
.icon-tile-upload {
  border-style: dashed;
  border-color: var(--admin-border);
  color: var(--admin-text2);
  background: transparent;
}
.icon-tile-upload.uploading {
  cursor: progress;
}

.picker-popover {
  /* На узком экране иначе вылазит за viewport — popover позиционируется к
     тайлу `+`, который посередине сетки иконок. Ограничиваем по ширине. */
  width: min(320px, calc(100vw - 32px));
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* Naive's NPopover ships ~12px vertical / ~16px horizontal padding, so
     the search input sits noticeably tighter to the top edge than the
     sides. Equalise via a small extra top inset. */
  padding-top: 4px;
  padding-bottom: 4px;
}
.picker-results {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(40px, 1fr));
  gap: 6px;
  max-height: 280px;
  overflow-y: auto;
  padding: 4px 2px;
  scrollbar-width: thin;
  scrollbar-color: rgba(127, 127, 127, 0.3) transparent;
}
.picker-results::-webkit-scrollbar {
  width: 6px;
}
.picker-results::-webkit-scrollbar-thumb {
  background: rgba(127, 127, 127, 0.25);
  border-radius: 3px;
}
.picker-tile {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 8px;
  border: 1px solid transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
  transition:
    transform 0.12s,
    border-color 0.12s;
}
.picker-tile:hover {
  transform: translateY(-1px);
  border-color: var(--text1, rgba(255, 255, 255, 0.6));
}
.picker-hint {
  grid-column: 1 / -1;
  text-align: center;
  font-size: 12px;
  color: var(--admin-text3);
  padding: 16px 0;
}
.picker-upload {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: var(--admin-text1);
  transition: background 0.12s;
}
.picker-upload:hover {
  background: var(--admin-hover);
}
.picker-upload.uploading {
  cursor: progress;
  opacity: 0.7;
}

.scale-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}
.scale-preview {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  flex-shrink: 0;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 1100px) {
  .admin-shell {
    grid-template-columns: 180px 1fr;
    grid-template-areas:
      'sections list'
      'sections editor';
  }
  .admin-sections {
    grid-area: sections;
  }
  .admin-list {
    grid-area: list;
  }
  .admin-editor {
    grid-area: editor;
  }
}

/* ── Mobile (≤720px): tabs for sections, list/editor exclusive ────
   Sections row gets converted into a horizontal tab-strip. The list
   and editor share the same grid slot; we toggle their visibility
   from JS via `.hidden` so back-button navigation reads as «list ⇄
   editor», not «scroll through both». */
@media (max-width: 767px) {
  .admin-shell {
    /* Mobile: settings tabs strip (44px) + content padding + bottom nav */
    height: calc(100vh - var(--app-header-h, 64px) - 96px - 56px);
    grid-template-columns: 1fr;
    grid-template-areas:
      'sections'
      'list';
    grid-template-rows: auto 1fr;
  }
  .admin-sections,
  .admin-list,
  .admin-editor {
    grid-column: 1;
  }
  .admin-list,
  .admin-editor {
    grid-row: 2;
  }
  .admin-list.hidden,
  .admin-editor.hidden,
  .admin-sections.hidden {
    display: none;
  }

  /* Section block becomes a horizontal tab strip — title hidden, rows
     line up in a flex row that scrolls horizontally if too narrow. */
  .admin-sections {
    padding: 6px;
    /* 3px — единый радиус по всем mobile tab-strip'ам (mirror
       `.settings-tabs` / `.forecast-tabs`) и дефолтному NCard
       borderRadius'у, чтобы tab-полоска не «выпирала» относительно
       соседних карточек. */
    border-radius: 3px;
  }
  .sections-title {
    display: none;
  }
  .admin-sections {
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
    gap: 4px;
  }
  .section-row {
    flex: 1 1 0;
    justify-content: center;
    padding: 8px 10px;
    gap: 6px; /* breathing room between label and counter tag */
    white-space: nowrap;
    /* Mobile tab strip: matches `.forecast-tab` / `.settings-tab`
       (3px) — единая пара 3/3px по всем primary mobile tab UI. */
    border-radius: 3px;
  }
  /* Чуть больше скругления у счётчика секции — n-tag scoped из-за
     deep-селектора (тэг — child компонента). */
  .section-row :deep(.n-tag) {
    border-radius: 8px;
  }

  /* Icon picker: ~6 columns on mobile via auto-fit, larger min so tiles
     stay tappable. Desktop stays locked to 16 × 2 (already declared). */
  .icon-picker {
    grid-template-columns: repeat(auto-fill, minmax(44px, 1fr));
  }
}
</style>
