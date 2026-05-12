import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  define: {
    __APP_VERSION__: JSON.stringify('test'),
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./tests/setup.js'],
    include: ['tests/**/*.test.js', 'src/**/__tests__/**/*.test.js'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      include: ['src/**/*.{js,vue}'],
      exclude: [
        'src/main.js',
        'src/App.vue',
        'src/router/**',
        'src/styles/**',
        'src/views/**',
        'src/components/Mb*.vue',
        'src/components/*Modal.vue',
        'src/components/AuthGate.vue',
        'src/components/UserAvatar.vue',
        'src/components/SplitPane.vue',
        'src/components/DetailRequestBell*.vue',
      ],
    },
  },
})
