import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { categories as catApi } from '@/api'

const USAGE_STORAGE_KEY = 'category-usage-v1'

function loadUsage() {
  try {
    const raw = localStorage.getItem(USAGE_STORAGE_KEY)
    if (!raw) return { expense: {}, income: {}, wishlist: {} }
    const parsed = JSON.parse(raw)
    return {
      expense: parsed.expense || {},
      income: parsed.income || {},
      wishlist: parsed.wishlist || {},
    }
  } catch {
    return { expense: {}, income: {}, wishlist: {} }
  }
}

export const useCategoriesStore = defineStore('categories', () => {
  const bySection = ref({ expense: [], income: [], wishlist: [] })
  const loading = ref({ expense: false, income: false, wishlist: false })
  const usageBySection = ref(loadUsage())

  watch(usageBySection, (v) => {
    try { localStorage.setItem(USAGE_STORAGE_KEY, JSON.stringify(v)) } catch {}
  }, { deep: true })

  async function load(section) {
    if (loading.value[section]) return
    loading.value[section] = true
    try {
      const { data } = await catApi.list(section)
      bySection.value[section] = data || []
    } finally {
      loading.value[section] = false
    }
  }

  async function add(section, name) {
    const trimmed = name.trim()
    if (!trimmed) return null
    const existing = bySection.value[section].find(c => c.name === trimmed)
    if (existing) return existing
    const { data } = await catApi.create({ section, name: trimmed })
    bySection.value[section].push(data)
    return data
  }

  async function remove(id, section) {
    await catApi.remove(id)
    bySection.value[section] = bySection.value[section].filter(c => c.id !== id)
  }

  function recordUse(section, name) {
    if (!name) return
    const map = { ...(usageBySection.value[section] || {}) }
    map[name] = Date.now()
    usageBySection.value = { ...usageBySection.value, [section]: map }
  }

  function sortByRecentUse(section, list) {
    const usage = usageBySection.value[section] || {}
    return [...list].sort((a, b) => {
      const ta = usage[a.name] || 0
      const tb = usage[b.name] || 0
      if (tb !== ta) return tb - ta
      if (a.is_default !== b.is_default) return a.is_default ? -1 : 1
      return a.name.localeCompare(b.name)
    })
  }

  function options(section) {
    return sortByRecentUse(section, bySection.value[section]).map(c => ({
      label: c.name,
      value: c.name,
      id: c.id,
      is_default: c.is_default,
    }))
  }

  return { bySection, load, add, remove, options, recordUse, sortByRecentUse }
})
