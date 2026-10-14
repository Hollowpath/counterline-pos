import { useId, useRef } from 'react'
import { CountryPicker } from './CountryPicker'
import { HOME_COUNTRY, usePhoneNumber } from './phone'

interface PhoneInputProps {
  /** The number, ideally international ("+60123456789"); '' for none. */
  value: string
  onChange: (international: string) => void
  label?: string
  required?: boolean
  disabled?: boolean
  autoFocus?: boolean
  placeholder?: string
  /** On the customer display's sign-up form: its label as large as the form's. */
  onDisplay?: boolean
}

/**
 * A phone number field with its country: [flag +60 ▾][12-345 6789]. The country is picked from the
 * list, or detected: numbers are Malaysian unless they start with "+", and typing "+65" switches
 * to Singapore and moves the code into the prefix. Clearing the field goes back to Malaysia.
 */
export function PhoneInput({
  value, onChange, label = 'Phone number', required, disabled, autoFocus, placeholder, onDisplay = false,
}: PhoneInputProps) {
  const phone = usePhoneNumber(value, onChange)
  const inputId = useId()
  const input = useRef<HTMLInputElement>(null)

  return (
    <div className={`grid grid-cols-[minmax(0,1fr)] gap-[0.2rem] ${onDisplay ? 'text-sm font-medium text-inherit' : 'text-sm font-medium text-zinc-700'}`}>
      <label htmlFor={inputId}>
        {label}
        {required && ' *'}
      </label>
      <span className={`flex items-stretch rounded-xl bg-white shadow-xs ring-1 ring-zinc-200 transition ring-inset focus-within:ring-2 focus-within:ring-indigo-500 ${disabled ? 'opacity-60' : ''}`}>
        <CountryPicker
          country={phone.country}
          callingCode={phone.callingCode}
          disabled={disabled}
          onChange={(country) => {
            phone.setCountry(country)
            input.current?.focus()
          }}
        />
        <input
          className={`min-w-0 flex-1 bg-transparent text-base font-normal text-zinc-900 outline-none placeholder:text-zinc-400 ${
            onDisplay ? 'px-3 py-2.5' : 'px-3.5 py-2.5'}`}
          id={inputId}
          ref={input}
          type="tel"
          inputMode="tel"
          autoComplete="tel"
          value={phone.text}
          required={required}
          disabled={disabled}
          autoFocus={autoFocus}
          maxLength={30}
          placeholder={placeholder ?? (phone.country === HOME_COUNTRY ? '012-345 6789' : undefined)}
          aria-invalid={phone.invalid || undefined}
          onChange={(event) => phone.type(event.target.value)}
        />
      </span>
      {phone.invalid && <span className="text-xs text-orange-700">Check the number: it isn’t a valid {phone.country} number.</span>}
    </div>
  )
}
