<template>
  <div>
    <n-card title="Экспорт данных" style="max-width: 640px; margin: 0 auto;">
      <n-form label-placement="top">
        <n-form-item label="Период">
          <n-date-picker v-model:value="dateRange" type="daterange" clearable style="width:100%" />
        </n-form-item>
        <n-form-item label="Тип транзакций">
          <n-radio-group v-model:value="txType">
            <n-space>
              <n-radio value="">Все</n-radio>
              <n-radio value="income">Только доходы</n-radio>
              <n-radio value="expense">Только расходы</n-radio>
            </n-space>
          </n-radio-group>
        </n-form-item>
      </n-form>

      <n-divider />

      <n-space vertical size="large">
        <n-card embedded style="border-radius: 8px;">
          <n-space align="center" justify="space-between">
            <div>
              <n-text strong style="font-size:15px;">Excel (.xlsx)</n-text>
              <n-text depth="3" display="block" style="font-size:13px;margin-top:4px;">
                4 листа: транзакции, доходы по категориям, расходы по категориям, месячная сводка
              </n-text>
            </div>
            <n-button type="primary" :loading="loadingExcel" @click="downloadExcel" style="min-width:120px">
              <template #icon><n-icon><GridOutline /></n-icon></template>
              Скачать Excel
            </n-button>
          </n-space>
        </n-card>

        <n-card embedded style="border-radius: 8px;">
          <n-space align="center" justify="space-between">
            <div>
              <n-text strong style="font-size:15px;">PDF (.pdf)</n-text>
              <n-text depth="3" display="block" style="font-size:13px;margin-top:4px;">
                Отчёт с таблицей транзакций и финансовой сводкой
              </n-text>
            </div>
            <n-button :loading="loadingPdf" @click="downloadPdf" style="min-width:120px">
              <template #icon><n-icon><DocumentTextOutline /></n-icon></template>
              Скачать PDF
            </n-button>
          </n-space>
        </n-card>
      </n-space>

      <n-alert v-if="!dateRange" type="info" style="margin-top:16px;" :bordered="false">
        Если период не выбран — будут экспортированы все записи
      </n-alert>
    </n-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useMessage } from 'naive-ui'
import {
  NCard, NForm, NFormItem, NDatePicker, NRadioGroup, NRadio, NSpace,
  NButton, NText, NDivider, NAlert, NIcon
} from 'naive-ui'
import { GridOutline, DocumentTextOutline } from '@vicons/ionicons5'
import { exportApi, downloadBlob } from '@/api'
import { storeToRefs } from 'pinia'
import { useThemeStore } from '@/stores/theme'

const message = useMessage()
const dateRange = ref(null)
const txType = ref('')
const loadingExcel = ref(false)
const loadingPdf = ref(false)

const { activeTheme } = storeToRefs(useThemeStore())

function buildParams() {
  const p = {}
  if (txType.value) p.type = txType.value
  if (dateRange.value) {
    p.from = new Date(dateRange.value[0]).toISOString().split('T')[0]
    p.to = new Date(dateRange.value[1]).toISOString().split('T')[0]
  }
  return p
}

function buildPdfParams() {
  return { ...buildParams(), theme: activeTheme.value.key }
}

async function downloadExcel() {
  loadingExcel.value = true
  try {
    const { data } = await exportApi.excel(buildParams())
    downloadBlob(data, `budget_${new Date().toISOString().split('T')[0]}.xlsx`)
    message.success('Excel-файл загружен')
  } catch (e) {
    message.error(e.message)
  } finally {
    loadingExcel.value = false
  }
}

async function downloadPdf() {
  loadingPdf.value = true
  try {
    const { data } = await exportApi.pdf(buildPdfParams())
    downloadBlob(data, `budget_${new Date().toISOString().split('T')[0]}.pdf`)
    message.success('PDF-файл загружен')
  } catch (e) {
    message.error(e.message)
  } finally {
    loadingPdf.value = false
  }
}
</script>
