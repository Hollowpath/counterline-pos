import {
  AsYouType,
  getCountries,
  getCountryCallingCode,
  parsePhoneNumberFromString,
  type CountryCode,
} from 'libphonenumber-js'
import { useState } from 'react'

/** The outlets' country: numbers typed without a "+" are Malaysian. */
export const HOME_COUNTRY: CountryCode = 'MY'

/**
 * Where customers most often come from, listed first in the country list (and preferred when a
 * calling code is shared, e.g. +1 is the US).
 */
const COMMON_COUNTRIES: CountryCode[] = [
  'MY', 'SG', 'BN', 'ID', 'TH', 'PH', 'VN', 'CN', 'HK', 'MO', 'TW', 'JP', 'KR', 'IN', 'AU', 'NZ', 'GB', 'US', 'CA',
]

// Flags as image files, fetched only when shown (Windows draws emoji flags as letters, and every
// flag bundled into the app would weigh about a megabyte).
const FLAG_FILES = import.meta.glob<string>('../../node_modules/country-flag-icons/3x2/*.svg', {
  query: '?no-inline',
  import: 'default',
  eager: true,
})

export function flagUrl(country: string): string | undefined {
  return FLAG_FILES[`../../node_modules/country-flag-icons/3x2/${country}.svg`]
}

const REGION_NAMES = new Intl.DisplayNames(['en'], { type: 'region' })

function countryName(country: CountryCode): string {
  try {
    return REGION_NAMES.of(country) ?? country
  } catch {
    return country
  }
}

export interface CountryOption {
  country: CountryCode
  name: string
  callingCode: string
  common: boolean
}

/** Every country, the common ones first, then the rest by name. */
export const COUNTRY_OPTIONS: CountryOption[] = [
  ...COMMON_COUNTRIES.map((country) => ({ country, name: countryName(country), callingCode: getCountryCallingCode(country), common: true })),
  ...getCountries()
    .filter((country) => !COMMON_COUNTRIES.includes(country))
    .map((country) => ({ country, name: countryName(country), callingCode: getCountryCallingCode(country), common: false }))
    .sort((a, b) => a.name.localeCompare(b.name)),
]

/** The country to show for a calling code: a common one first, then any. */
function countryForCallingCode(code: string): CountryCode | undefined {
  return COUNTRY_OPTIONS.find((option) => option.callingCode === code)?.country
}

export interface PhoneParts {
  country: CountryCode
  /** The number within the country, as typed (e.g. "012-345 6789"). */
  national: string
}

function fromValue(value: string): PhoneParts {
  if (value.trim() === '') return { country: HOME_COUNTRY, national: '' }
  const parsed = parsePhoneNumberFromString(value, HOME_COUNTRY)
  return parsed?.country
    ? { country: parsed.country, national: parsed.formatNational() }
    : { country: HOME_COUNTRY, national: value }
}

/**
 * The full number for the server, "+60123456789", or '' when nothing is typed. A number that can't
 * be read is passed on as typed, with its country code, so the server says it isn't valid.
 */
export function toInternational({ country, national }: PhoneParts): string {
  const digits = national.replace(/\D/g, '')
  if (digits === '') return ''
  const parsed = parsePhoneNumberFromString(national, country)
  return parsed ? parsed.number : `+${getCountryCallingCode(country)}${digits.replace(/^0/, '')}`
}

/**
 * A phone number being typed, with its country: chosen from the list, or detected when the number
 * starts with "+" ("+65" switches to Singapore and the code moves out of the number). Numbers are
 * Malaysian otherwise; clearing the number goes back to Malaysia. `onChange` gets the full
 * international number.
 */
export function usePhoneNumber(value: string, onChange: (international: string) => void) {
  const [parts, setParts] = useState<PhoneParts>(() => fromValue(value))
  // A number typed with "+", until its country code is complete ("+6" of "+65").
  const [pending, setPending] = useState<string | null>(null)

  function update(next: PhoneParts) {
    setParts(next)
    setPending(null)
    onChange(toInternational(next))
  }

  function type(text: string) {
    if (text.trim() === '') {
      update({ country: HOME_COUNTRY, national: '' })
      return
    }
    if (text.trim().startsWith('+')) {
      const typer = new AsYouType()
      typer.input(text)
      const code = typer.getCallingCode()
      const country = typer.getCountry() ?? (code ? countryForCallingCode(code) : undefined)
      if (country) {
        const parsed = parsePhoneNumberFromString(text)
        update({ country, national: parsed?.isPossible() ? parsed.formatNational() : typer.getNationalNumber() })
        return
      }
      setPending(text)
      onChange(text)
      return
    }
    update({ ...parts, national: text })
  }

  const parsed = parsePhoneNumberFromString(parts.national, parts.country)
  return {
    country: parts.country,
    callingCode: getCountryCallingCode(parts.country),
    /** What the number field shows. */
    text: pending ?? parts.national,
    /** Long enough to judge, and not a valid number for its country. */
    invalid: pending === null && parts.national.replace(/\D/g, '').length >= 8 && !(parsed?.isValid() ?? false),
    type,
    setCountry: (country: CountryCode) => update({ country, national: pending === null ? parts.national : '' }),
  }
}
