import * as Sentry from '@sentry/vue'

// initSentry wires browser error + performance telemetry, driven entirely by the
// backend's runtime config (GET /api/client-config) — nothing is baked into the
// bundle. If the server reports no DSN (telemetry disabled, or config
// unreachable) Sentry is never initialised and the app starts normally.
//
// Events are sent through the same-origin tunnel the backend advertises
// (sentry.tunnel), so the browser never contacts the self-hosted (LAN-only)
// Sentry directly — that's what makes this work off-LAN and past ad-blockers.
export async function initSentry(app, router) {
  let sentry
  try {
    // Bound the wait so a slow/absent backend never blocks app startup.
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 3000)
    try {
      const res = await fetch('/api/client-config', {
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      })
      if (!res.ok) return
      sentry = (await res.json())?.sentry
    } finally {
      clearTimeout(timer)
    }
  } catch {
    return // telemetry config unreachable — start the app without Sentry
  }

  if (!sentry?.dsn) return

  Sentry.init({
    app,
    dsn: sentry.dsn,
    environment: sentry.environment,
    release: `budget-web@${__APP_VERSION__}`,
    // Same-origin relay; the host inside dsn is irrelevant because the browser
    // posts to this path and the backend forwards to Sentry.
    tunnel: sentry.tunnel || undefined,
    tracesSampleRate: sentry.tracesSampleRate ?? 0.1,
    integrations: [Sentry.browserTracingIntegration({ router })],
    // Noise from browser internals / extensions that isn't our bug.
    ignoreErrors: [
      'ResizeObserver loop limit exceeded',
      'ResizeObserver loop completed with undelivered notifications.',
    ],
  })
}
