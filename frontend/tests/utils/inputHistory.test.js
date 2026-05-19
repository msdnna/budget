import { describe, it, expect } from 'vitest'
import { loadHistory, pushHistory, historyOptions } from '@/utils/inputHistory'

describe('inputHistory', () => {
  it('returns empty array for unknown key', () => {
    expect(loadHistory('nope')).toEqual([])
    expect(historyOptions('nope')).toEqual([])
  })

  it('pushes a value to the front and persists', () => {
    pushHistory('test-key', 'Магнит')
    pushHistory('test-key', 'Пятёрочка')
    expect(loadHistory('test-key')).toEqual(['Пятёрочка', 'Магнит'])
  })

  it('moves duplicate to front instead of adding a copy', () => {
    pushHistory('test-key', 'A')
    pushHistory('test-key', 'B')
    pushHistory('test-key', 'A')
    expect(loadHistory('test-key')).toEqual(['A', 'B'])
  })

  it('trims whitespace and skips empty values', () => {
    pushHistory('test-key', '  ')
    pushHistory('test-key', '')
    pushHistory('test-key', '  Хлеб  ')
    expect(loadHistory('test-key')).toEqual(['Хлеб'])
  })

  it('caps history at 20 entries (oldest dropped)', () => {
    for (let i = 0; i < 25; i++) pushHistory('test-key', `value-${i}`)
    const list = loadHistory('test-key')
    expect(list).toHaveLength(20)
    // Newest first: value-24, value-23, ... value-5.
    expect(list[0]).toBe('value-24')
    expect(list[list.length - 1]).toBe('value-5')
  })

  it('survives corrupted localStorage payload (non-JSON)', () => {
    localStorage.setItem('budget-history-test-key', 'not-json{{{')
    expect(loadHistory('test-key')).toEqual([])
    // pushHistory should overwrite the bad payload cleanly.
    pushHistory('test-key', 'fresh')
    expect(loadHistory('test-key')).toEqual(['fresh'])
  })

  it('filters non-string entries from a malformed array', () => {
    localStorage.setItem('budget-history-test-key', JSON.stringify(['ok', 42, null, 'good']))
    expect(loadHistory('test-key')).toEqual(['ok', 'good'])
  })
})
