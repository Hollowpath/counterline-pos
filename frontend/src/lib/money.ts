/**
 * Money is handled in integer sen (1/100 RM) on the client to avoid floating-point drift
 * when summing. The API sends decimal prices, converted once at the boundary.
 */
export function toSen(price: number): number {
  return Math.round(price * 100)
}

export function formatRM(sen: number): string {
  return `RM${(sen / 100).toFixed(2)}`
}

/** "12", "12.5" or "12.50" → 1250 sen; anything else (including 3+ decimals) → null. */
export function parseRM(input: string): number | null {
  const match = /^(\d{1,8})(?:\.(\d{1,2}))?$/.exec(input.trim())
  if (!match) return null
  return Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'))
}

/** Sen as the plain decimal string used in amount inputs, e.g. 6100 → "61.00". */
export function senToInput(sen: number): string {
  return (sen / 100).toFixed(2)
}

/** Like the printed reports: a negative amount in parentheses, e.g. -1600 → "(RM16.00)". */
export function formatSignedRM(sen: number): string {
  return sen < 0 ? `(${formatRM(-sen)})` : formatRM(sen)
}
