import { defineStore } from 'pinia'
import { ref } from 'vue'
import { transactions as api } from '@/api'

export const useTransactionsStore = defineStore('transactions', () => {
  const items = ref([])
  const total = ref(0)
  const loading = ref(false)
  const page = ref(1)
  const limit = ref(20)
  const filters = ref({ type: '', category: '', from: '', to: '' })

  async function fetch() {
    loading.value = true
    try {
      const params = { page: page.value, limit: limit.value }
      if (filters.value.type) params.type = filters.value.type
      if (filters.value.category) params.category = filters.value.category
      if (filters.value.from) params.from = filters.value.from
      if (filters.value.to) params.to = filters.value.to

      const { data } = await api.list(params)
      items.value = data.data || []
      total.value = data.total || 0
    } finally {
      loading.value = false
    }
  }

  async function create(payload) {
    const { data } = await api.create(payload)
    await fetch()
    return data
  }

  async function update(id, payload) {
    const { data } = await api.update(id, payload)
    await fetch()
    return data
  }

  async function remove(id) {
    await api.remove(id)
    await fetch()
  }

  async function toggle(id, hidden) {
    await api.update(id, { hidden })
    await fetch()
  }

  function setPage(p) {
    page.value = p
    fetch()
  }

  function setFilters(f) {
    filters.value = { ...filters.value, ...f }
    page.value = 1
    fetch()
  }

  return { items, total, loading, page, limit, filters, fetch, create, update, remove, toggle, setPage, setFilters }
})
