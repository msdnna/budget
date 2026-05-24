import { CardOutline, CashOutline } from '@vicons/ionicons5'

export const DEPOSIT_BANK = 'bank'
export const DEPOSIT_CASH = 'cash'
export const DEPOSIT_DEFAULT = DEPOSIT_BANK

export const DEPOSITS = [
  { value: DEPOSIT_BANK, label: 'Банковская карта', shortLabel: 'Карта', icon: CardOutline },
  { value: DEPOSIT_CASH, label: 'Наличные', shortLabel: 'Нал', icon: CashOutline },
]

export function depositMeta(value) {
  return DEPOSITS.find((d) => d.value === value) ?? DEPOSITS[0]
}

export function normalizeDeposit(value) {
  return value === DEPOSIT_CASH ? DEPOSIT_CASH : DEPOSIT_BANK
}
