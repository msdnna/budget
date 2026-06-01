import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { initSentry } from './sentry'
import './styles/theme.css'

async function bootstrap() {
  const app = createApp(App)
  // Init telemetry before mount so the initial pageload + early errors are
  // captured. No-op when the backend reports no DSN; never blocks startup.
  await initSentry(app, router)
  app.use(createPinia())
  app.use(router)
  app.mount('#app')
}

bootstrap()
