// Per-device autocomplete history для свободно-вводимых текстовых полей
// (Источник / Назначение / Название). Хранится в localStorage — это
// per-device-ergonomics-кэш (как recently-used категории), без бэкенда:
// нет смысла платить миграцией схемы за не-критичный UX, и история
// браузера у каждого пользователя своя по определению.
//
// Сравнение с native browser autocomplete: SPA-формы без реального
// `<form>` submit обычно не «учатся» (Chrome/Firefox смотрят на сабмит,
// а не на input-события). NAutoComplete + наш `<datalist>`-эквивалент
// через localStorage даёт предсказуемый result независимо от браузера.

const MAX_ENTRIES = 20

// Все ключи живут под одним префиксом, чтобы было легко найти и почистить
// (DevTools → Storage → отфильтровать `budget-history-`).
const PREFIX = 'budget-history-'

function safeParse(raw) {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((v) => typeof v === 'string') : []
  } catch {
    return []
  }
}

export function loadHistory(key) {
  if (typeof localStorage === 'undefined') return []
  return safeParse(localStorage.getItem(PREFIX + key))
}

// Push «недавно введённое» в начало — точное совпадение перемещается
// (а не дублируется), пустые/whitespace-only значения игнорируются,
// общий список капается до MAX_ENTRIES (старейшее уезжает с хвоста).
export function pushHistory(key, value) {
  if (typeof localStorage === 'undefined') return
  const trimmed = (value || '').trim()
  if (!trimmed) return
  const cur = loadHistory(key)
  const next = [trimmed, ...cur.filter((v) => v !== trimmed)].slice(0, MAX_ENTRIES)
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(next))
  } catch {
    // QuotaExceeded или Safari в private-mode — soft-fail, autocomplete
    // просто не запомнит. Без снек'а — не блокирующая фича.
  }
}

// Filtered options для NAutoComplete: возвращаем массив строк
// (компонент сам отрендерит). NAutoComplete умеет фильтровать по
// частичному совпадению через `get-show` / встроенный prefix-match,
// но мы и так держим список коротким (≤20), так что отдаём весь.
export function historyOptions(key) {
  return loadHistory(key)
}
