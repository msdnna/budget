import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

// Attach JWT token from localStorage on every request.
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('auth_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Refresh-on-401: when an access-token call fails with 401, try exchanging
// the stored refresh token for a fresh pair and retry the original request.
// Coalesced — multiple concurrent 401s share a single in-flight refresh
// promise so we never hit /auth/refresh twice for the same expiry event.
let refreshInflight = null

async function refreshAccessToken() {
  if (refreshInflight) return refreshInflight
  const refreshToken = localStorage.getItem('auth_refresh_token')
  if (!refreshToken) return null
  refreshInflight = axios
    .post('/api/auth/refresh', { refresh_token: refreshToken })
    .then((res) => {
      const data = res.data || {}
      if (data.token) localStorage.setItem('auth_token', data.token)
      if (data.refresh_token) localStorage.setItem('auth_refresh_token', data.refresh_token)
      return data.token || null
    })
    .catch(() => null)
    .finally(() => {
      refreshInflight = null
    })
  return refreshInflight
}

api.interceptors.response.use(
  (res) => res,
  async (err) => {
    const original = err.config
    const isUnauthorized = err.response?.status === 401
    // Don't loop: /auth/refresh failures and already-retried requests bail.
    const isRefreshCall = original?.url?.includes('/auth/refresh')
    if (isUnauthorized && original && !original._retry && !isRefreshCall) {
      original._retry = true
      const newToken = await refreshAccessToken()
      if (newToken) {
        original.headers = original.headers || {}
        original.headers.Authorization = `Bearer ${newToken}`
        return api(original)
      }
    }
    if (isUnauthorized) {
      // Refresh failed or refresh token itself rejected — clear and notify.
      localStorage.removeItem('auth_token')
      localStorage.removeItem('auth_refresh_token')
      localStorage.removeItem('auth_user')
      window.dispatchEvent(new CustomEvent('auth:expired'))
    }
    const msg = err.response?.data?.error || err.message || 'Ошибка запроса'
    return Promise.reject(new Error(msg))
  },
)

export const transactions = {
  list: (params) => api.get('/transactions', { params }),
  create: (data) => api.post('/transactions', data),
  update: (id, data) => api.put(`/transactions/${id}`, data),
  remove: (id) => api.delete(`/transactions/${id}`),
}

export const statistics = {
  summary: (params) => api.get('/statistics/summary', { params }),
  byCategory: (params) => api.get('/statistics/by-category', { params }),
  monthly: (params) => api.get('/statistics/monthly', { params }),
  forecast: () => api.get('/statistics/forecast'),
}

export const wishlist = {
  list: () => api.get('/wishlist'),
  create: (data) => api.post('/wishlist', data),
  update: (id, data) => api.put(`/wishlist/${id}`, data),
  remove: (id) => api.delete(`/wishlist/${id}`),
  unlinkPeriod: (id) => api.post(`/wishlist/${id}/unlink-period`),
}

export const users = {
  list: () => api.get('/users'),
}

export const categories = {
  list: (section) => api.get('/categories', { params: { section } }),
  all: () => api.get('/categories/all'),
  create: (data) => api.post('/categories', data),
  update: (id, data) => api.patch(`/categories/${id}`, data),
  remove: (id) => api.delete(`/categories/${id}`),
}

export const icons = {
  list: () => api.get('/icons'),
  upload: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/icons', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  remove: (id) => api.delete(`/icons/${id}`),
  // URL for use in <img src="..."> — auth header is attached by axios on
  // .get(), but <img> doesn't go through axios. The endpoint is auth-only
  // by gin middleware, so we surface the URL through the same axios baseURL
  // and rely on cookie/session... no — we don't use cookies. So callers
  // must fetch as blob via this client OR we need to pass the token in a
  // way <img> can authenticate. For now, fetch+blob via `image()`.
  image: (id) => api.get(`/icons/${id}`, { responseType: 'blob' }),
}

export const versionApi = {
  get: () => api.get('/version'),
}

export const detailRequests = {
  list: (params) => api.get('/detail-requests', { params }),
  create: (data) => api.post('/detail-requests', data),
  get: (id) => api.get(`/detail-requests/${id}`),
  addChild: (id, data) => api.post(`/detail-requests/${id}/transactions`, data),
  close: (id) => api.post(`/detail-requests/${id}/close`),
  cancel: (id) => api.post(`/detail-requests/${id}/cancel`),
}

export const exportApi = {
  excel: (params) => api.get('/export/excel', { params, responseType: 'blob' }),
  pdf: (params) => api.get('/export/pdf', { params, responseType: 'blob' }),
}

export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export default api
