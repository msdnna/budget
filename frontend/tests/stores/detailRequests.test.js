import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'
import api from '../../src/api/index.js'
import { useDetailRequestsStore } from '../../src/stores/detailRequests.js'

describe('detailRequests store', () => {
  let mock

  beforeEach(() => {
    setActivePinia(createPinia())
    mock = new MockAdapter(api)
  })

  it('openRequest/closeRequest toggle openRequestId', () => {
    const s = useDetailRequestsStore()
    s.openRequest('r1')
    expect(s.openRequestId).toBe('r1')
    s.closeRequest()
    expect(s.openRequestId).toBeNull()
  })

  it('startCreate/cancelCreate set and clear creatingForTx', () => {
    const s = useDetailRequestsStore()
    s.startCreate({ id: 'tx1' })
    expect(s.creatingForTx).toEqual({ id: 'tx1' })
    s.cancelCreate()
    expect(s.creatingForTx).toBeNull()
  })

  it('getMyOpen filters by assignee and open status', () => {
    const s = useDetailRequestsStore()
    s.items = [
      { id: 'a', status: 'open', assignee: { user_id: 'u1' } },
      { id: 'b', status: 'closed', assignee: { user_id: 'u1' } },
      { id: 'c', status: 'open', assignee: { user_id: 'u2' } },
    ]
    expect(s.getMyOpen('u1').map((r) => r.id)).toEqual(['a'])
  })

  it('getByParentTxId returns the matching request', () => {
    const s = useDetailRequestsStore()
    s.items = [
      { id: 'a', parent_transaction_id: 'tx1' },
      { id: 'b', parent_transaction_id: 'tx2' },
    ]
    expect(s.getByParentTxId('tx2').id).toBe('b')
    expect(s.getByParentTxId('tx-missing')).toBeUndefined()
  })

  it('fetchAll loads list', async () => {
    mock.onGet('/detail-requests').reply(200, [{ id: 'r1' }])
    const s = useDetailRequestsStore()
    await s.fetchAll()
    expect(s.items).toEqual([{ id: 'r1' }])
  })

  it('cancel hits the cancel endpoint and refetches', async () => {
    mock.onPost('/detail-requests/r1/cancel').reply(204)
    mock.onGet('/detail-requests').reply(200, [])
    const s = useDetailRequestsStore()
    await s.cancel('r1')
    expect(mock.history.post.length).toBe(1)
    expect(mock.history.get.length).toBe(1)
  })

  it('close hits the close endpoint and refetches', async () => {
    mock.onPost('/detail-requests/r1/close').reply(204)
    mock.onGet('/detail-requests').reply(200, [])
    const s = useDetailRequestsStore()
    await s.close('r1')
    expect(mock.history.post[0].url).toBe('/detail-requests/r1/close')
  })
})
