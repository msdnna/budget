<template>
  <div class="users-shell" :style="cssVars" :class="{ 'mobile-editing': isMobile && editing }">
    <!-- ── List ──────────────────────────────────────────────── -->
    <!-- List / editor swap на mobile через slide+fade (см. theme.css
         `.mobile-slide-*`). v-show вместо :class="{hidden}" — чтобы
         `<Transition>` мог цеплять enter/leave classes. Desktop не
         задет, transition gated @media (max-width: 767px). -->
    <Transition name="mobile-slide">
      <div v-show="!isMobile || !editing" class="users-list">
        <div class="list-header">
          <n-text strong style="font-size: 14px">Пользователи</n-text>
          <div class="list-header-actions">
            <n-spin v-if="loading" :size="14" />
            <!-- На мобильном добавление через FAB снизу-справа (см. ниже). -->
            <n-button
              v-if="!isMobile"
              size="small"
              type="primary"
              :disabled="loading"
              @click="startCreate"
            >
              <template #icon><n-icon :component="AddOutline" /></template>
              Добавить
            </n-button>
          </div>
        </div>
        <div class="list-rows">
          <div
            v-for="u in users"
            :key="u.id"
            class="user-row"
            :class="{ active: selectedId === u.id, blocked: !!u.blocked_at }"
            @click="select(u)"
          >
            <UserAvatar
              :display-name="u.display_name"
              :avatar-url="u.avatar_url || ''"
              :size="36"
            />
            <div class="user-row-text">
              <div class="user-name">
                {{ u.display_name }}
                <n-tag v-if="u.is_admin" size="tiny" :bordered="false" type="primary">admin</n-tag>
                <n-tag v-if="u.blocked_at" size="tiny" :bordered="false" type="warning">
                  заблокирован
                </n-tag>
                <n-tag v-if="u.id === auth.user?.user_id" size="tiny" :bordered="false">вы</n-tag>
              </div>
              <div class="user-login">@{{ u.login }}</div>
            </div>
          </div>
          <n-empty v-if="!loading && !users.length" description="Пусто" style="padding: 24px 0" />
        </div>
      </div>
    </Transition>

    <!-- ── Editor ─────────────────────────────────────────────── -->
    <Transition name="mobile-slide">
      <div v-show="!isMobile || editing" class="users-editor">
        <template v-if="editing">
          <div class="editor-header">
            <n-button v-if="isMobile" text size="small" title="Назад" @click="cancelEdit">
              <template #icon><n-icon :component="ArrowBackOutline" /></template>
            </n-button>
            <n-text strong style="font-size: 15px; flex: 1">
              {{ isCreating ? 'Новый пользователь' : editing.display_name }}
            </n-text>
            <n-button v-if="!isMobile" quaternary circle size="small" @click="cancelEdit">
              <template #icon><n-icon :component="CloseOutline" /></template>
            </n-button>
          </div>

          <div class="editor-body">
            <!-- Avatar (existing user only) -->
            <div v-if="!isCreating" class="avatar-section">
              <UserAvatar
                :display-name="editing.display_name"
                :avatar-url="editing.avatar_url || ''"
                :size="80"
              />
              <div class="avatar-controls">
                <label class="avatar-upload" :class="{ uploading: uploadingAvatar }">
                  <input
                    ref="avatarInput"
                    type="file"
                    accept="image/png,image/jpeg,image/svg+xml"
                    hidden
                    @change="handleAvatarUpload"
                  />
                  <n-button
                    size="small"
                    :loading="uploadingAvatar"
                    @click="$refs.avatarInput.click()"
                  >
                    <template #icon><n-icon :component="CloudUploadOutline" /></template>
                    Загрузить
                  </n-button>
                </label>
                <n-button
                  v-if="editing.avatar_url"
                  size="small"
                  quaternary
                  :disabled="uploadingAvatar"
                  @click="handleAvatarDelete"
                >
                  Убрать
                </n-button>
              </div>
              <n-text depth="3" style="font-size: 12px">PNG / JPEG / SVG, до 512 KB.</n-text>
            </div>

            <n-form label-placement="top" require-mark-placement="right-hanging">
              <n-form-item label="Логин">
                <n-input v-model:value="editForm.login" placeholder="alice" />
              </n-form-item>
              <n-form-item label="Отображаемое имя">
                <n-input v-model:value="editForm.display_name" placeholder="Имя Фамилия" />
              </n-form-item>
              <n-form-item v-if="isCreating" label="Пароль">
                <n-input
                  v-model:value="editForm.password"
                  type="password"
                  show-password-on="click"
                  placeholder="≥ 4 символов"
                />
              </n-form-item>
              <n-form-item label="Права">
                <n-space vertical size="small" style="width: 100%">
                  <n-checkbox
                    v-model:checked="editForm.is_admin"
                    :disabled="isSelf && editing.is_admin"
                  >
                    Администратор
                  </n-checkbox>
                  <n-checkbox
                    v-if="!isCreating"
                    v-model:checked="editForm.blocked"
                    :disabled="isSelf"
                  >
                    Заблокирован (вход запрещён)
                  </n-checkbox>
                </n-space>
              </n-form-item>
              <n-form-item v-if="!isCreating" label="Пароль">
                <n-button size="small" @click="showPasswordModal = true">
                  <template #icon><n-icon :component="KeyOutline" /></template>
                  Изменить пароль
                </n-button>
              </n-form-item>
            </n-form>
          </div>

          <div class="editor-footer">
            <n-popconfirm
              v-if="!isCreating && !isSelf"
              positive-text="Удалить"
              negative-text="Отмена"
              @positive-click="handleDelete"
            >
              <template #trigger>
                <n-button type="error" ghost :disabled="saving">Удалить</n-button>
              </template>
              Удалить пользователя «{{ editing.display_name }}»? Записи (транзакции, желания), где
              он указан как автор, останутся.
            </n-popconfirm>
            <span v-else />
            <n-space>
              <n-button :disabled="saving" @click="cancelEdit">Отмена</n-button>
              <n-button type="primary" :loading="saving" @click="saveEdit">
                {{ isCreating ? 'Создать' : 'Сохранить' }}
              </n-button>
            </n-space>
          </div>
        </template>
        <div v-else-if="!isMobile" class="editor-empty">
          <n-empty description="Выберите пользователя слева или нажмите «Добавить»" />
        </div>
      </div>
    </Transition>

    <!-- ── Password change modal ─────────────────────────────── -->
    <n-modal
      v-model:show="showPasswordModal"
      preset="card"
      :style="{ width: '420px', maxWidth: 'calc(100vw - 32px)' }"
      title="Смена пароля"
      :mask-closable="!savingPassword"
      @after-leave="resetPasswordForm"
    >
      <n-form label-placement="top">
        <n-form-item v-if="isSelf" label="Текущий пароль">
          <n-input
            v-model:value="passwordForm.old"
            type="password"
            show-password-on="click"
            placeholder="Старый пароль"
          />
        </n-form-item>
        <n-form-item label="Новый пароль">
          <n-input
            v-model:value="passwordForm.new"
            type="password"
            show-password-on="click"
            placeholder="≥ 4 символов"
          />
        </n-form-item>
        <n-form-item label="Подтвердите">
          <n-input
            v-model:value="passwordForm.confirm"
            type="password"
            show-password-on="click"
            placeholder="Тот же пароль"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button :disabled="savingPassword" @click="showPasswordModal = false">Отмена</n-button>
          <n-button
            type="primary"
            :loading="savingPassword"
            :disabled="!canSubmitPassword"
            @click="handlePasswordSubmit"
          >
            Сохранить
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- Mobile FAB — добавить пользователя. -->
    <FabButton v-if="isMobile && !editing" title="Добавить пользователя" @click="startCreate" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  NSpace,
  NText,
  NButton,
  NSpin,
  NEmpty,
  NIcon,
  NTag,
  NForm,
  NFormItem,
  NInput,
  NCheckbox,
  NPopconfirm,
  NModal,
  useMessage,
} from 'naive-ui'
import {
  AddOutline,
  CloseOutline,
  CloudUploadOutline,
  KeyOutline,
  ArrowBackOutline,
} from '@vicons/ionicons5'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'
import { useAuthStore } from '@/stores/auth'
import { adminUsers, authSelf } from '@/api'
import UserAvatar from '@/components/UserAvatar.vue'
import FabButton from '@/components/FabButton.vue'

const message = useMessage()
const auth = useAuthStore()
const { palette, primaryColor } = storeToRefs(useThemeStore())

const cssVars = computed(() => ({
  '--admin-surface': palette.value.surface,
  '--admin-card-bg': palette.value.cardSurface,
  '--admin-border': palette.value.border,
  '--admin-hover': palette.value.hover,
  '--admin-text1': palette.value.text1,
  '--admin-text2': palette.value.text2,
  '--admin-text3': palette.value.text3,
  '--admin-primary': primaryColor.value,
  '--admin-primary-soft': hexToAlpha(primaryColor.value, 0.16),
}))

function hexToAlpha(hex, alpha) {
  if (!hex || hex.length !== 7 || hex[0] !== '#') return `rgba(32, 128, 240, ${alpha})`
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

// ── Responsive (matches App.vue threshold) ──────────────────
const windowWidth = ref(window.innerWidth)
const isMobile = computed(() => windowWidth.value < 768)
function onResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => window.removeEventListener('resize', onResize))

// ── State ─────────────────────────────────────────────────────
const users = ref([])
const loading = ref(false)
const editing = ref(null)
const editForm = ref({
  login: '',
  display_name: '',
  password: '',
  is_admin: false,
  blocked: false,
})
const saving = ref(false)
const uploadingAvatar = ref(false)

const selectedId = computed(() => editing.value?.id || null)
const isCreating = computed(() => editing.value && !editing.value.id)
const isSelf = computed(() => !!editing.value?.id && editing.value.id === auth.user?.user_id)

async function load() {
  loading.value = true
  try {
    const { data } = await adminUsers.list()
    users.value = (data || []).sort((a, b) => {
      // Свой первым, потом админы, потом по имени.
      const selfA = a.id === auth.user?.user_id
      const selfB = b.id === auth.user?.user_id
      if (selfA !== selfB) return selfA ? -1 : 1
      if (a.is_admin !== b.is_admin) return a.is_admin ? -1 : 1
      return a.display_name.localeCompare(b.display_name)
    })
  } catch (err) {
    message.error(err?.message || 'Ошибка загрузки')
  } finally {
    loading.value = false
  }
}

onMounted(load)

function select(u) {
  editing.value = { ...u }
  editForm.value = {
    login: u.login,
    display_name: u.display_name,
    password: '',
    is_admin: !!u.is_admin,
    blocked: !!u.blocked_at,
  }
}

function startCreate() {
  editing.value = { id: null, display_name: '', avatar_url: '', is_admin: false }
  editForm.value = {
    login: '',
    display_name: '',
    password: '',
    is_admin: false,
    blocked: false,
  }
}

function cancelEdit() {
  editing.value = null
}

async function saveEdit() {
  if (!editing.value) return
  const login = editForm.value.login.trim()
  const name = editForm.value.display_name.trim()
  if (!login || !name) {
    message.error('Логин и отображаемое имя обязательны')
    return
  }
  saving.value = true
  try {
    if (isCreating.value) {
      if ((editForm.value.password || '').length < 4) {
        message.error('Пароль не короче 4 символов')
        saving.value = false
        return
      }
      const { data: created } = await adminUsers.create({
        login,
        password: editForm.value.password,
        display_name: name,
        is_admin: editForm.value.is_admin,
      })
      users.value = [created, ...users.value]
      select(created)
      message.success('Создан')
    } else {
      const patch = {}
      if (login !== editing.value.login) patch.login = login
      if (name !== editing.value.display_name) patch.display_name = name
      if (editForm.value.is_admin !== !!editing.value.is_admin)
        patch.is_admin = editForm.value.is_admin
      const wasBlocked = !!editing.value.blocked_at
      if (editForm.value.blocked !== wasBlocked) patch.blocked = editForm.value.blocked
      if (!Object.keys(patch).length) {
        message.info('Нет изменений')
        saving.value = false
        return
      }
      const { data: updated } = await adminUsers.update(editing.value.id, patch)
      replaceInList(updated)
      select(updated)
      message.success('Сохранено')
    }
  } catch (err) {
    message.error(err?.message || 'Ошибка сохранения')
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  if (!editing.value?.id) return
  saving.value = true
  try {
    await adminUsers.remove(editing.value.id)
    users.value = users.value.filter((u) => u.id !== editing.value.id)
    cancelEdit()
    message.success('Удалён')
  } catch (err) {
    message.error(err?.message || 'Ошибка удаления')
  } finally {
    saving.value = false
  }
}

function replaceInList(u) {
  const idx = users.value.findIndex((x) => x.id === u.id)
  if (idx >= 0) {
    const next = [...users.value]
    next[idx] = u
    users.value = next
  }
}

// ── Avatar ──────────────────────────────────────────────────────
async function handleAvatarUpload(e) {
  const file = e.target.files?.[0]
  if (!file || !editing.value?.id) return
  if (file.size > 512 * 1024) {
    message.error('Файл больше 512 KB')
    e.target.value = ''
    return
  }
  uploadingAvatar.value = true
  try {
    const { data } = await adminUsers.uploadAvatar(editing.value.id, file)
    replaceInList(data)
    editing.value = { ...editing.value, avatar_url: data.avatar_url }
    message.success('Аватар обновлён')
  } catch (err) {
    message.error(err?.message || 'Ошибка загрузки')
  } finally {
    uploadingAvatar.value = false
    e.target.value = ''
  }
}

async function handleAvatarDelete() {
  if (!editing.value?.id) return
  uploadingAvatar.value = true
  try {
    const { data } = await adminUsers.removeAvatar(editing.value.id)
    replaceInList(data)
    editing.value = { ...editing.value, avatar_url: '' }
    message.success('Аватар убран')
  } catch (err) {
    message.error(err?.message || 'Ошибка')
  } finally {
    uploadingAvatar.value = false
  }
}

// ── Password change ────────────────────────────────────────────
const showPasswordModal = ref(false)
const savingPassword = ref(false)
const passwordForm = ref({ old: '', new: '', confirm: '' })

const canSubmitPassword = computed(() => {
  const { new: n, confirm, old } = passwordForm.value
  if (!n || n.length < 4) return false
  if (n !== confirm) return false
  if (isSelf.value && !old) return false
  return true
})

function resetPasswordForm() {
  passwordForm.value = { old: '', new: '', confirm: '' }
}

async function handlePasswordSubmit() {
  if (!editing.value?.id) return
  savingPassword.value = true
  try {
    if (isSelf.value) {
      await authSelf.changePassword(passwordForm.value.old, passwordForm.value.new)
    } else {
      await adminUsers.setPassword(editing.value.id, passwordForm.value.new)
    }
    showPasswordModal.value = false
    message.success('Пароль обновлён')
  } catch (err) {
    message.error(err?.message || 'Ошибка смены пароля')
  } finally {
    savingPassword.value = false
  }
}
</script>

<style scoped>
.users-shell {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 16px;
  align-items: stretch;
  height: calc(100vh - var(--app-header-h, 64px) - 48px);
  min-height: 0;
}
@media (max-width: 767px) {
  .users-shell {
    /* Mobile: settings tabs strip (44px) + content padding + bottom nav */
    height: calc(100vh - var(--app-header-h, 64px) - 96px - 56px);
    grid-template-columns: 1fr;
  }
  .users-list,
  .users-editor {
    grid-column: 1;
    grid-row: 1;
  }
  .users-list.hidden,
  .users-editor.hidden {
    display: none;
  }
}

.users-list,
.users-editor {
  /* Theme-aware bg = Naive NCard.color (см. AdminCategoriesView). */
  background: var(--admin-card-bg);
  border: 1px solid var(--admin-border);
  border-radius: 3px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  color: var(--admin-text1);
}

.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px 8px;
  border-bottom: 1px solid var(--admin-border);
  margin-bottom: 6px;
  flex-shrink: 0;
}
.list-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.list-rows {
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  flex: 1 1 auto;
  min-height: 0;
  scrollbar-width: thin;
  scrollbar-color: rgba(127, 127, 127, 0.3) transparent;
}
.list-rows::-webkit-scrollbar {
  width: 6px;
}
.list-rows::-webkit-scrollbar-thumb {
  background: rgba(127, 127, 127, 0.25);
  border-radius: 3px;
}
.user-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  cursor: pointer;
  border-radius: 6px;
  color: var(--admin-text1);
  transition: background 0.12s;
}
.user-row:hover {
  background: var(--admin-hover);
}
.user-row.active {
  background: var(--admin-primary-soft);
}
.user-row.blocked .user-name,
.user-row.blocked .user-login {
  opacity: 0.6;
}
.user-row-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
}
.user-name {
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  line-height: 1.2;
}
.user-login {
  font-size: 12px;
  color: var(--admin-text3);
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-header {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border);
  margin-bottom: 12px;
  flex-shrink: 0;
}
.editor-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  /* Лёгкий inset, чтобы фокус-кольца чекбоксов/инпутов слева не клипались
     при `overflow:hidden` родительского `.users-editor`. */
  padding: 2px 4px 2px 4px;
  scrollbar-width: thin;
  scrollbar-color: rgba(127, 127, 127, 0.3) transparent;
}
.editor-body::-webkit-scrollbar {
  width: 6px;
}
.editor-body::-webkit-scrollbar-thumb {
  background: rgba(127, 127, 127, 0.25);
  border-radius: 3px;
}
.editor-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--admin-border);
  margin-top: 8px;
  flex-shrink: 0;
}
.editor-empty {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 4px 0 16px;
  border-bottom: 1px solid var(--admin-border);
  margin-bottom: 12px;
}
.avatar-controls {
  display: flex;
  gap: 8px;
}
.avatar-upload {
  display: inline-flex;
}
</style>
