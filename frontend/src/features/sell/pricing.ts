/** A discount as offered by the API (mirrors DiscountService.DiscountResponse). */
export interface DiscountRule {
  id: number
  name: string
  kind: 'PERCENT' | 'AMOUNT'
  value: number
  /** A manager approves it at the till (the department comps). */
  requiresApproval?: boolean
  /** Can't be given on a member's sale (the staff discount). */
  notForMembers?: boolean
  /** Counts as a voucher (the till's RM10 Voucher): the sale gives no Buy 3 Free 1, seasonal or birthday free items. */
  countsAsVoucher?: boolean
  /** A physical voucher keyed in at the till: sent as its code, not as a discount id. */
  voucherCode?: string
}

/**
 * Mirrors the server's DiscountCalculator exactly, in integer sen:
 * each discount is worked out on the undiscounted base (percentages rounded half-up to the sen)
 * and, in the order given, together they never exceed the base.
 */
export function discountAmountsSen(baseSen: number, discounts: DiscountRule[]): number[] {
  let remaining = baseSen
  return discounts.map((discount) => {
    const amount = Math.min(rawAmountSen(baseSen, discount), remaining)
    remaining -= amount
    return amount
  })
}

function rawAmountSen(baseSen: number, discount: DiscountRule): number {
  const hundredths = Math.round(discount.value * 100) // 12.5% → 1250; RM10 → 1000 sen
  if (discount.kind === 'AMOUNT') return hundredths
  // baseSen × percent/100, half-up: exact because the numerator is an integer.
  return Math.floor((baseSen * hundredths + 5000) / 10000)
}

export function sumSen(amounts: number[]): number {
  return amounts.reduce((total, amount) => total + amount, 0)
}

export function formatDiscount(discount: DiscountRule): string {
  return discount.kind === 'PERCENT' ? `${discount.value}%` : `RM${discount.value.toFixed(2)}`
}
