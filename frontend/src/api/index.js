import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

// Attach JWT token from localStorage on every request.
api.interceptors.request.use(config => {
  const token = localStorage.getItem('auth_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      // Clear stale credentials and emit a custom event so App.vue can react.
      localStorage.removeItem('auth_token')
      localStorage.removeItem('auth_user')
      window.dispatchEvent(new CustomEvent('auth:expired'))
    }
    const msg = err.response?.data?.error || err.message || 'Ошибка запроса'
    return Promise.reject(new Error(msg))
  }
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
}

export const users = {
  list: () => api.get('/users'),
}

export const categories = {
  list: (section) => api.get('/categories', { params: { section } }),
  create: (data) => api.post('/categories', data),
  remove: (id) => api.delete(`/categories/${id}`),
}

export const versionApi = {
  get: () => api.get('/version'),
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
