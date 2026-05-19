import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { notifications as notifApi } from '@/api'

// Family-wide notifications (limit overflows for now; new types are
// additive). Read-state is per-user — we trust the backend's `read` flag
// on every row instead of mirroring `read_by` arrays locally.
export const useNotificationsStore = defineStore('notifications', () => {
  const items = ref([])
  const unreadCount = ref(0)
  const loading = ref(false)

  async function fetchAll() {
    loading.value = true
    try {
      const { data } = await notifApi.list()
      items.value = data?.data || []
      unreadCount.value = data?.unread_count || 0
    } catch {
      // Soft-fail: bell just shows nothing if the endpoint is down.
    } finally {
      loading.value = false
    }
  }

  async function markAllRead() {
    try {
      await notifApi.readAll()
      for (const n of items.value) n.read = true
      unreadCount.value = 0
    } catch {
      // Surface failure silently; user can retry via the same button.
    }
  }

  const unread = computed(() => items.value.filter((n) => !n.read))

  return { items, unreadCount, unread, loading, fetchAll, markAllRead }
})
