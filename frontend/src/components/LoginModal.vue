<template>
    <n-modal
        :show="show"
        @update:show="val => { if (!val) emit('close') }"
        preset="card"
        style="width: 380px"
        title="Вход в систему"
        :bordered="false"
    >
        <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" @keydown.enter="submit">
            <n-form-item label="Логин" path="login">
                <n-input
                    v-model:value="form.login"
                    placeholder="Введите логин"
                    :disabled="loading"
                    autocomplete="username"
                />
            </n-form-item>
            <n-form-item label="Пароль" path="password">
                <n-input
                    v-model:value="form.password"
                    type="password"
                    show-password-on="click"
                    placeholder="Введите пароль"
                    :disabled="loading"
                    autocomplete="current-password"
                />
            </n-form-item>

            <n-alert v-if="errorMsg" type="error" style="margin-bottom: 16px">
                {{ errorMsg }}
            </n-alert>

            <n-button
                type="primary"
                block
                :loading="loading"
                @click="submit"
            >
                Войти
            </n-button>
        </n-form>
    </n-modal>
</template>

<script setup>
import { ref } from 'vue'
import { NModal, NForm, NFormItem, NInput, NButton, NAlert } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const props = defineProps({ show: Boolean })
const emit = defineEmits(['success', 'close'])

const auth = useAuthStore()
const formRef = ref(null)
const loading = ref(false)
const errorMsg = ref('')

const form = ref({ login: '', password: '' })

const rules = {
    login:    [{ required: true, message: 'Введите логин',  trigger: 'blur' }],
    password: [{ required: true, message: 'Введите пароль', trigger: 'blur' }],
}

async function submit() {
    try { await formRef.value?.validate() } catch { return }

    loading.value = true
    errorMsg.value = ''
    try {
        await auth.login(form.value.login, form.value.password)
        form.value = { login: '', password: '' }
        emit('success')
    } catch (e) {
        errorMsg.value = e.message
    } finally {
        loading.value = false
    }
}
</script>
