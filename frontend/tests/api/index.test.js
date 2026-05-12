import { describe, it, expect, beforeEach, vi } from 'vitest'
import MockAdapter from 'axios-mock-adapter'

// The api module creates a singleton axios instance at import-time, so we need
// to import it lazily inside each test to bind a fresh mock adapter to it.
async function loadApi() {
  const mod = await import('../../src/api/index.js')
  return mod
}

describe('api/index.js — axios instance', () => {
  let api, transactions, mock

  beforeEach(async () => {
    vi.resetModules()
    const mod = await loadApi()
    api = mod.default
    transactions = mod.transactions
    mock = new MockAdapter(api)
    localStorage.clear()
  })

  it('attaches Bearer token from localStorage on every request', async () => {
    localStorage.setItem('auth_token', 'jwt-xyz')
    mock.onGet('/transactions').reply((config) => {
      expect(config.headers.Authorization).toBe('Bearer jwt-xyz')
      return [200, { data: [], total: 0 }]
    })
    const res = await transactions.list({})
    expect(res.status).toBe(200)
  })

  it('omits Authorization header when no token is stored', async () => {
    mock.onGet('/transactions').reply((config) => {
      expect(config.headers.Authorization).toBeUndefined()
      return [200, { data: [], total: 0 }]
    })
    await transactions.list({})
  })

  it('clears auth on 401 and emits the auth:expired event', async () => {
    localStorage.setItem('auth_token', 'stale')
    localStorage.setItem('auth_user', '{"id":"u1"}')
    const handler = vi.fn()
    window.addEventListener('auth:expired', handler)

    mock.onGet('/transactions').reply(401, { error: 'token expired' })
    await expect(transactions.list({})).rejects.toThrow('token expired')

    expect(localStorage.getItem('auth_token')).toBeNull()
    expect(localStorage.getItem('auth_user')).toBeNull()
    expect(handler).toHaveBeenCalledOnce()
    window.removeEventListener('auth:expired', handler)
  })

  it('rejects with the backend error message when present', async () => {
    mock.onPost('/transactions').reply(400, { error: 'amount must be positive' })
    await expect(transactions.create({ amount: -1 })).rejects.toThrow('amount must be positive')
  })

  it('rejects with axios message when no backend error is provided', async () => {
    mock.onGet('/transactions').networkError()
    await expect(transactions.list({})).rejects.toThrow(/Network Error/)
  })

  it('builds the correct endpoint paths for resource methods', async () => {
    mock.onPut('/transactions/abc').reply(200, { id: 'abc' })
    mock.onDelete('/transactions/abc').reply(204)
    await transactions.update('abc', { amount: 100 })
    await transactions.remove('abc')
    expect(mock.history.put.length).toBe(1)
    expect(mock.history.delete.length).toBe(1)
  })
})

describe('downloadBlob helper', () => {
  it('creates an object URL, triggers an <a> download, and revokes the URL', async () => {
    const { downloadBlob } = await loadApi()
    const createSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake')
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clickSpy = vi.fn()
    const origCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const el = origCreate(tag)
      if (tag === 'a') el.click = clickSpy
      return el
    })

    downloadBlob(new Blob(['x']), 'report.xlsx')
    expect(createSpy).toHaveBeenCalledOnce()
    expect(clickSpy).toHaveBeenCalledOnce()
    expect(revokeSpy).toHaveBeenCalledWith('blob:fake')
  })
})
