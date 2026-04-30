import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '@/api/index'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('auth_token') || '')
  const user = ref(JSON.parse(localStorage.getItem('auth_user') || 'null'))

  const isAuthenticated = computed(() => !!token.value)

  function setAuth(loginResponse) {
    token.value = loginResponse.token
    user.value = {
      user_id:      loginResponse.user_id,
      display_name: loginResponse.display_name,
      avatar_url:   loginResponse.avatar_url || '',
      expires_at:   loginResponse.expires_at,
    }
    localStorage.setItem('auth_token', token.value)
    localStorage.setItem('auth_user', JSON.stringify(user.value))
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('auth_token')
    localStorage.removeItem('auth_user')
  }

  async function login(login, password) {
    const res = await api.post('/auth/login', { login, password })
    setAuth(res.data)
  }

  // Called on app start to verify the stored token is still valid.
  async function verify() {
    if (!token.value) return
    try {
      await api.get('/auth/me')
    } catch {
      logout()
    }
  }

  return { token, user, isAuthenticated, login, logout, verify }
})
