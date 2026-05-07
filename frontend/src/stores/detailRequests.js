import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { detailRequests as api } from '@/api'

// Single global store: detail-requests are sparse and used across multiple
// views (header bell, expenses list highlight, settings page).
export const useDetailRequestsStore = defineStore('detailRequests', () => {
  const items = ref([])
  const loading = ref(false)
  // Cross-view trigger: any component can set openRequestId to surface the
  // global DetailRequestModal in App.vue.
  const openRequestId = ref(null)
  // Pending creation state — when set, App.vue surfaces the assignee picker.
  const creatingForTx = ref(null)

  function openRequest(id) { openRequestId.value = id }
  function closeRequest() { openRequestId.value = null }
  function startCreate(tx) { creatingForTx.value = tx }
  function cancelCreate() { creatingForTx.value = null }

  async function fetchAll() {
    loading.value = true
    try {
      const { data } = await api.list()
      items.value = data || []
    } finally {
      loading.value = false
    }
  }

  async function fetchAssignedToMe() {
    const { data } = await api.list({ assignee_id: 'me' })
    items.value = data || []
  }

  async function create(payload) {
    const { data } = await api.create(payload)
    await fetchAll()
    return data
  }

  async function close(id) {
    await api.close(id)
    await fetchAll()
  }

  async function cancel(id) {
    await api.cancel(id)
    await fetchAll()
  }

  function getMyOpen(userId) {
    return items.value.filter(
      r => r.status === 'open' && r.assignee?.user_id === userId,
    )
  }

  function getByParentTxId(txId) {
    return items.value.find(r => r.parent_transaction_id === txId)
  }

  return {
    items,
    loading,
    openRequestId,
    creatingForTx,
    fetchAll,
    fetchAssignedToMe,
    create,
    close,
    cancel,
    getMyOpen,
    getByParentTxId,
    openRequest,
    closeRequest,
    startCreate,
    cancelCreate,
  }
})
