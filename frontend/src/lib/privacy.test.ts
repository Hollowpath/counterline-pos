import { describe, expect, it } from 'vitest'
import { maskEmail, maskPhone } from './privacy'

describe('maskPhone', () => {
  it('shows the country code, the first 2 digits and the last 4', () => {
    expect(maskPhone('+60123456789')).toBe('+60 12•••6789')
    expect(maskPhone('+601123456789')).toBe('+60 11••••6789')
    expect(maskPhone('+6591234567')).toBe('+65 91••4567')
  })

  it('hides more of a short number', () => {
    expect(maskPhone('+6531234')).toBe('+65 •1234')
    expect(maskPhone('1234')).toBe('••••')
  })
})

describe('maskEmail', () => {
  it('hides the name but its first letters, and keeps the domain', () => {
    expect(maskEmail('aisyah.rahman@gmail.com')).toBe('ai•••@gmail.com')
    expect(maskEmail('jo@example.my')).toBe('j•••@example.my')
    expect(maskEmail('not-an-email')).toBe('•••')
  })
})
