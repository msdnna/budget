import { afterEach, vi } from 'vitest'

// Reset localStorage between tests so store snapshots don't bleed across cases.
afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})
