import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '@/api/index'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('auth_token') || '')
  const refreshToken = ref(localStorage.getItem('auth_refresh_token') || '')
  const user = ref(JSON.parse(localStorage.getItem('auth_user') || 'null'))

  const isAuthenticated = computed(() => !!token.value)
  const isAdmin = computed(() => !!user.value?.is_admin)

  function setAuth(loginResponse) {
    token.value = loginResponse.token
    refreshToken.value = loginResponse.refresh_token || ''
    user.value = {
      user_id: loginResponse.user_id,
      display_name: loginResponse.display_name,
      avatar_url: loginResponse.avatar_url || '',
      is_admin: !!loginResponse.is_admin,
      expires_at: loginResponse.expires_at,
    }
    localStorage.setItem('auth_token', token.value)
    if (refreshToken.value) {
      localStorage.setItem('auth_refresh_token', refreshToken.value)
    } else {
      localStorage.removeItem('auth_refresh_token')
    }
    localStorage.setItem('auth_user', JSON.stringify(user.value))
  }

  function logout() {
    token.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.removeItem('auth_token')
    localStorage.removeItem('auth_refresh_token')
    localStorage.removeItem('auth_user')
  }

  async function login(login, password) {
    const res = await api.post('/auth/login', { login, password })
    setAuth(res.data)
  }

  // Called on app start to verify the stored token is still valid.
  // Also refreshes is_admin from /auth/me — handy when an existing client
  // had a pre-1.17 token cached without the new flag.
  async function verify() {
    if (!token.value) return
    try {
      const res = await api.get('/auth/me')
      if (user.value && res.data) {
        user.value = { ...user.value, is_admin: !!res.data.is_admin }
        localStorage.setItem('auth_user', JSON.stringify(user.value))
      }
    } catch {
      logout()
    }
  }

  return { token, user, isAuthenticated, isAdmin, login, logout, verify, setAuth }
})
