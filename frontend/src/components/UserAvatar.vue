<template>
  <div class="user-avatar" :style="containerStyle" :title="displayName">
    <img v-if="resolvedUrl" :src="resolvedUrl" :alt="displayName" class="avatar-img" />
    <span v-else class="avatar-initials">{{ initials }}</span>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import api from '@/api/index'

const props = defineProps({
  displayName: { type: String, default: '' },
  avatarUrl: { type: String, default: '' },
  size: { type: Number, default: 24 },
})

// Авторизация: внутренние `/api/users/:id/avatar?v=<ts>` требуют Bearer-токен,
// который `<img src>` не умеет ставить. Скачиваем такие URL через axios
// (interceptor подставит токен), сохраняем как blob-URL и отдаём в <img>.
// Кэш модульного уровня: ключ — полный URL (`?v=` меняется при перезаливке
// аватара, так что мы естественно не показываем устаревший blob).
const blobCache = new Map() // url → object URL
const blobInflight = new Map() // url → Promise<string>

function isInternalAvatar(url) {
  return typeof url === 'string' && url.startsWith('/api/')
}

async function fetchAvatarBlob(url) {
  if (blobCache.has(url)) return blobCache.get(url)
  if (blobInflight.has(url)) return blobInflight.get(url)
  const p = api
    .get(url.replace(/^\/api/, ''), { responseType: 'blob' })
    .then((r) => {
      const obj = URL.createObjectURL(r.data)
      blobCache.set(url, obj)
      blobInflight.delete(url)
      return obj
    })
    .catch((err) => {
      blobInflight.delete(url)
      throw err
    })
  blobInflight.set(url, p)
  return p
}

const fetchedBlobUrl = ref('')
watch(
  () => props.avatarUrl,
  async (url) => {
    if (!isInternalAvatar(url)) {
      fetchedBlobUrl.value = ''
      return
    }
    try {
      fetchedBlobUrl.value = await fetchAvatarBlob(url)
    } catch {
      fetchedBlobUrl.value = ''
    }
  },
  { immediate: true },
)

const resolvedUrl = computed(() => {
  if (!props.avatarUrl) return ''
  if (isInternalAvatar(props.avatarUrl)) return fetchedBlobUrl.value
  return props.avatarUrl
})

// First letter of each word, max 2 characters.
const initials = computed(() => {
  return (props.displayName || '?')
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w[0].toUpperCase())
    .slice(0, 2)
    .join('')
})

// Deterministic color from the display name string.
const bgColor = computed(() => {
  const palette = [
    '#e57373',
    '#f06292',
    '#ba68c8',
    '#7986cb',
    '#4fc3f7',
    '#4dd0e1',
    '#4db6ac',
    '#81c784',
    '#aed581',
    '#ffb74d',
    '#ff8a65',
    '#a1887f',
  ]
  let hash = 0
  for (let i = 0; i < props.displayName.length; i++) {
    hash = props.displayName.charCodeAt(i) + ((hash << 5) - hash)
  }
  return palette[Math.abs(hash) % palette.length]
})

const containerStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  borderRadius: '50%',
  overflow: 'hidden',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: '0',
  backgroundColor: resolvedUrl.value ? 'transparent' : bgColor.value,
  fontSize: `${Math.max(8, Math.floor(props.size * 0.38))}px`,
  fontWeight: '600',
  color: '#fff',
  userSelect: 'none',
  cursor: 'default',
}))
</script>

<style scoped>
.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.avatar-initials {
  line-height: 1;
  letter-spacing: 0.03em;
}
</style>
