import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useAuthStore } from '../../src/stores/auth.js'

describe('auth store', () => {
  let mock

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
    localStorage.clear()
  })

  it('starts unauthenticated when no token is stored', () => {
    const auth = useAuthStore()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.user).toBeNull()
  })

  it('persists token + user on successful login', async () => {
    mock.onPost('/auth/login').reply(200, {
      token: 'jwt-1',
      user_id: 'u1',
      display_name: 'Alice',
      avatar_url: '',
      expires_at: 9_999_999_999,
    })
    const auth = useAuthStore()
    await auth.login('alice', 'pw')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user.display_name).toBe('Alice')
    expect(localStorage.getItem('auth_token')).toBe('jwt-1')
    expect(JSON.parse(localStorage.getItem('auth_user')).user_id).toBe('u1')
  })

  it('logout clears state and localStorage', async () => {
    localStorage.setItem('auth_token', 't')
    localStorage.setItem('auth_user', JSON.stringify({ user_id: 'u1' }))
    setActivePinia(createPinia())
    const auth = useAuthStore()
    expect(auth.isAuthenticated).toBe(true)
    auth.logout()
    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem('auth_token')).toBeNull()
  })

  it('verify() logs out when /auth/me rejects', async () => {
    localStorage.setItem('auth_token', 'stale')
    localStorage.setItem('auth_user', JSON.stringify({ user_id: 'u1' }))
    setActivePinia(createPinia())
    mock.onGet('/auth/me').reply(401, { error: 'expired' })
    const auth = useAuthStore()
    await auth.verify()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('verify() is a no-op when no token is stored', async () => {
    const auth = useAuthStore()
    const spy = vi.spyOn(api, 'get')
    await auth.verify()
    expect(spy).not.toHaveBeenCalled()
  })
})
