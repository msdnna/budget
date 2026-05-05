import { defineStore } from 'pinia'
import { ref } from 'vue'
import { categories as catApi } from '@/api'

export const useCategoriesStore = defineStore('categories', () => {
  const bySection = ref({ expense: [], income: [], wishlist: [] })
  const loading = ref({ expense: false, income: false, wishlist: false })

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

  function options(section) {
    return bySection.value[section].map(c => ({
      label: c.name,
      value: c.name,
      id: c.id,
      is_default: c.is_default,
    }))
  }

  return { bySection, load, add, remove, options }
})
