import { describe, it, expect, beforeEach, vi } from 'vitest'

// vi.mock is hoisted above module scope, so the mock fns must be hoisted too.
const { initMock, browserTracingMock } = vi.hoisted(() => ({
  initMock: vi.fn(),
  browserTracingMock: vi.fn(() => ({ name: 'BrowserTracing' })),
}))

vi.mock('@sentry/vue', () => ({
  init: initMock,
  browserTracingIntegration: browserTracingMock,
}))

import { initSentry } from '../src/sentry.js'

function mockFetch(body, ok = true) {
  global.fetch = vi.fn().mockResolvedValue({ ok, json: () => Promise.resolve(body) })
}

describe('initSentry', () => {
  beforeEach(() => {
    initMock.mockClear()
    browserTracingMock.mockClear()
  })

  it('does not init when backend reports sentry:null', async () => {
    mockFetch({ sentry: null })
    await initSentry({}, {})
    expect(initMock).not.toHaveBeenCalled()
  })

  it('does not init when config fetch fails', async () => {
    global.fetch = vi.fn().mockRejectedValue(new Error('network down'))
    await initSentry({}, {})
    expect(initMock).not.toHaveBeenCalled()
  })

  it('does not init on a non-2xx config response', async () => {
    mockFetch({}, false)
    await initSentry({}, {})
    expect(initMock).not.toHaveBeenCalled()
  })

  it('inits with tunnel, release and router tracing when dsn present', async () => {
    mockFetch({
      sentry: {
        dsn: 'http://key@host:9100/3',
        environment: 'production',
        tracesSampleRate: 0.2,
        tunnel: '/api/sentry-tunnel',
      },
    })
    const app = { id: 'app' }
    const router = { id: 'router' }

    await initSentry(app, router)

    expect(initMock).toHaveBeenCalledTimes(1)
    const opts = initMock.mock.calls[0][0]
    expect(opts.app).toBe(app)
    expect(opts.dsn).toBe('http://key@host:9100/3')
    expect(opts.environment).toBe('production')
    expect(opts.tunnel).toBe('/api/sentry-tunnel')
    expect(opts.tracesSampleRate).toBe(0.2)
    expect(opts.release).toBe('budget-web@test') // __APP_VERSION__ is 'test' under vitest
    expect(browserTracingMock).toHaveBeenCalledWith({ router })
  })
})
