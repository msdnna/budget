import { defineStore } from 'pinia'
import { ref } from 'vue'
import { transactions as api } from '@/api'

// Each scope ("income", "expenses", …) gets an independent Pinia store so that
// list filters, pagination, and the cached page do not bleed across views.
const stores = new Map()

export function useTransactionsStore(scope = 'default') {
  let factory = stores.get(scope)
  if (!factory) {
    factory = defineStore(`transactions:${scope}`, () => {
      const items = ref([])
      const total = ref(0)
      const loading = ref(false)
      const page = ref(1)
      const limit = ref(20)
      const filters = ref({
        type: '',
        category: '',
        categories: [],
        from: '',
        to: '',
        deposit: '',
        includeDetailed: false,
      })

      async function fetch() {
        loading.value = true
        try {
          const params = { page: page.value, limit: limit.value }
          if (filters.value.type) params.type = filters.value.type
          if (filters.value.categories && filters.value.categories.length) {
            params.categories = filters.value.categories.join(',')
          } else if (filters.value.category) {
            params.category = filters.value.category
          }
          if (filters.value.from) params.from = filters.value.from
          if (filters.value.to) params.to = filters.value.to
          if (filters.value.deposit) params.deposit = filters.value.deposit
          if (filters.value.includeDetailed) params.include_detailed = 'true'

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
        // Replace, don't merge: callers always pass the complete intended
        // filter spec, so unspecified fields must clear. Merging caused stale
        // category/date filters to persist across view remounts.
        filters.value = {
          type: '',
          category: '',
          categories: [],
          from: '',
          to: '',
          deposit: '',
          includeDetailed: false,
          ...f,
        }
        page.value = 1
        fetch()
      }

      return {
        items,
        total,
        loading,
        page,
        limit,
        filters,
        fetch,
        create,
        update,
        remove,
        toggle,
        setPage,
        setFilters,
      }
    })
    stores.set(scope, factory)
  }
  return factory()
}
