import { describe, expect, it } from 'vitest'
import { parseRM } from './money'

describe('parseRM', () => {
  it.each([
    ['12', 1200],
    ['12.5', 1250],
    ['12.50', 1250],
    [' 0.05 ', 5],
  ])('parses %j as %i sen', (input, sen) => {
    expect(parseRM(input)).toBe(sen)
  })

  it.each(['', 'abc', '12.505', '-1', '1,000', '.5'])('rejects %j', (input) => {
    expect(parseRM(input)).toBeNull()
  })
})
