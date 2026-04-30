<template>
    <template v-if="auth.isAuthenticated">
        <slot />
    </template>
    <div v-else class="auth-gate">
        <n-result status="403" title="Требуется аутентификация" description="Войдите в систему, чтобы увидеть данные">
            <template #footer>
                <n-button type="primary" @click="emit('login')">
                    <template #icon><n-icon><LogInOutline /></n-icon></template>
                    Войти
                </n-button>
            </template>
        </n-result>
    </div>
</template>

<script setup>
import { NResult, NButton, NIcon } from 'naive-ui'
import { LogInOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const emit = defineEmits(['login'])
</script>

<style scoped>
.auth-gate {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 60vh;
}
</style>
