import { describe, expect, it } from 'vitest'
import { addDays, daysBetween } from './dates'

describe('date helpers', () => {
  it('adds days across month and year ends', () => {
    expect(addDays('2026-10-31', 1)).toBe('2026-11-01')
    expect(addDays('2027-01-01', -1)).toBe('2026-12-31')
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29')
  })

  it('counts days inclusively', () => {
    expect(daysBetween('2026-10-12', '2026-10-12')).toBe(1)
    expect(daysBetween('2026-10-01', '2026-10-31')).toBe(31)
  })
})
