import { defineStore } from 'pinia'
import { ref } from 'vue'
import { wishlist as api } from '@/api'

export const useWishlistStore = defineStore('wishlist', () => {
  const items = ref([])
  const loading = ref(false)

  async function fetch() {
    loading.value = true
    try {
      const { data } = await api.list()
      items.value = data || []
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

  async function togglePurchased(item) {
    return update(item.id, { purchased: !item.purchased })
  }

  async function toggleFavorite(item) {
    return update(item.id, { is_favorite: !item.is_favorite })
  }

  return { items, loading, fetch, create, update, remove, togglePurchased, toggleFavorite }
})
