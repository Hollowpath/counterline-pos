import { parsePhoneNumberFromString } from 'libphonenumber-js'

const HIDDEN = '•'

/**
 * A phone number with its middle hidden, as on a bank's app: the country code, the first 2 digits
 * and the last 4 ("+60 12•••6789"). Enough for staff to check it is the customer's, without showing
 * it to everyone who can see the till.
 */
export function maskPhone(phone: string): string {
  const parsed = parsePhoneNumberFromString(phone)
  const code = parsed ? `+${parsed.countryCallingCode} ` : ''
  const digits = parsed ? String(parsed.nationalNumber) : phone.replace(/\D/g, '')
  if (digits.length <= 4) return `${code}${HIDDEN.repeat(digits.length)}`
  const shown = digits.length >= 8 ? 2 : 0
  return `${code}${digits.slice(0, shown)}${HIDDEN.repeat(digits.length - shown - 4)}${digits.slice(-4)}`
}

/** An email address with its name hidden but the first letters ("ai•••@gmail.com"). */
export function maskEmail(email: string): string {
  const at = email.lastIndexOf('@')
  if (at <= 0) return HIDDEN.repeat(3)
  const name = email.slice(0, at)
  return `${name.slice(0, name.length > 2 ? 2 : 1)}${HIDDEN.repeat(3)}${email.slice(at)}`
}

/** How to reach a customer, shown in a list: their phone, else their email, hidden as above. */
export function maskContact(phone: string | null | undefined, email: string | null | undefined): string {
  if (phone) return maskPhone(phone)
  return email ? maskEmail(email) : ''
}
