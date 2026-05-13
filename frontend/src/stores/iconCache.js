import { defineStore } from 'pinia'
import { ref } from 'vue'
import { icons as iconsApi } from '@/api'

// Cache for custom-uploaded category icons. The `/api/icons/:id` endpoint
// requires Bearer auth, which `<img src=...>` can't supply directly — so we
// fetch each icon as a blob via axios (which attaches the token) and
// expose an object URL the templates can bind to as a normal image source.
//
// Object URLs are kept for the session lifetime; they're tied to the
// document and revoked automatically on page unload. We don't proactively
// revoke on store reset because the same icon often re-appears across
// chart re-renders and re-fetching every time would be wasteful.
export const useIconCacheStore = defineStore('iconCache', () => {
  const cache = ref(new Map()) // id → object URL
  const inflight = new Map() // id → Promise<string>

  async function resolve(id) {
    if (!id) return null
    if (cache.value.has(id)) return cache.value.get(id)
    if (inflight.has(id)) return inflight.get(id)
    const p = iconsApi
      .image(id)
      .then((r) => {
        const url = URL.createObjectURL(r.data)
        const next = new Map(cache.value)
        next.set(id, url)
        cache.value = next
        inflight.delete(id)
        return url
      })
      .catch((err) => {
        inflight.delete(id)
        throw err
      })
    inflight.set(id, p)
    return p
  }

  // Drops cached URLs for an icon id — call after admin deletes/replaces
  // an icon so the next chart render fetches fresh bytes.
  function invalidate(id) {
    if (!cache.value.has(id)) return
    URL.revokeObjectURL(cache.value.get(id))
    const next = new Map(cache.value)
    next.delete(id)
    cache.value = next
  }

  return { cache, resolve, invalidate }
})

// `"custom:<id>"` → `<id>`, anything else → null
export function parseCustomIconKey(key) {
  if (typeof key !== 'string' || !key.startsWith('custom:')) return null
  const id = key.slice('custom:'.length)
  return id || null
}
