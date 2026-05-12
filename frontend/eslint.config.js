import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'
import globals from 'globals'

export default [
  {
    ignores: ['node_modules/**', 'dist/**', 'coverage/**', 'public/**'],
  },
  js.configs.recommended,
  ...vue.configs['flat/recommended'],
  prettier,
  {
    languageOptions: {
      ecmaVersion: 2024,
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node,
        __APP_VERSION__: 'readonly',
      },
    },
    rules: {
      // Disabled — handlers/refs frequently bind callbacks they don't use.
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // Component names like `App`, `MbLogo` are intentional.
      'vue/multi-word-component-names': 'off',
      // Naive UI bindings produce many attributes; auto-wrapping hurts readability.
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/first-attribute-linebreak': 'off',
      // Optional props use `default` only when meaningful; nullable props are intentional.
      'vue/require-default-prop': 'off',
      // We use both shorthand and longform; both are valid.
      'vue/v-on-event-hyphenation': 'off',
      'vue/attribute-hyphenation': 'off',
      // v-html is used for trusted constant SVG/HTML strings (sparkline icons in ForecastingView).
      'vue/no-v-html': 'off',
      // Tag order is consistently template-script-style; enforce, but template first.
      'vue/component-tags-order': 'off',
      'vue/block-order': ['error', { order: ['template', 'script', 'style'] }],
    },
  },
  {
    files: ['**/*.test.js', '**/*.spec.js', 'tests/**/*.js', 'src/**/__tests__/**/*.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  {
    files: ['vite.config.js', 'vitest.config.js', 'eslint.config.js'],
    languageOptions: {
      globals: globals.node,
    },
  },
]
