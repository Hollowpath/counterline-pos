import type { CountryCode } from 'libphonenumber-js'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { COUNTRY_OPTIONS, flagUrl } from './phone'

/** A country's flag. `lazy`: in a long list, fetched only when scrolled into view. */
export function Flag({ country, lazy = false }: { country: string; lazy?: boolean }) {
  const url = flagUrl(country)
  return url ? (
    <img className="h-4 w-6 flex-none rounded-[3px] object-cover shadow-[0_0_0_1px_var(--color-zinc-200)]" src={url} alt="" loading={lazy ? 'lazy' : undefined} />
  ) : (
    <span className="w-6 text-center text-[0.7rem] font-bold">{country}</span>
  )
}

interface CountryPickerProps {
  country: CountryCode
  callingCode: string
  onChange: (country: CountryCode) => void
  disabled?: boolean
  /** On the customer display's keypad: larger, its top corners rounded. */
  keypad?: boolean
}

/**
 * The country of a phone number: [flag +60 ▾]. Opens a list of every country (flag, code and name,
 * the common ones first) with a search box, so the staff or the customer can pick one.
 */
export function CountryPicker({ country, callingCode, onChange, disabled, keypad = false }: CountryPickerProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const wrapper = useRef<HTMLDivElement>(null)
  const listId = useId()
  const current = COUNTRY_OPTIONS.find((option) => option.country === country)

  useEffect(() => {
    if (!open) return
    function outside(event: PointerEvent) {
      if (!wrapper.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open])

  const options = useMemo(() => {
    const query = search.trim().toLowerCase().replace(/^\+/, '')
    if (query === '') return COUNTRY_OPTIONS
    return COUNTRY_OPTIONS.filter((option) => option.name.toLowerCase().includes(query)
      || option.callingCode.startsWith(query) || option.country.toLowerCase() === query)
  }, [search])

  function choose(next: CountryCode) {
    onChange(next)
    setOpen(false)
    setSearch('')
  }

  return (
    <div className="relative flex-none" ref={wrapper}>
      <button
        type="button"
        className={[
          'flex h-full cursor-pointer items-center gap-1.5 border-r border-zinc-200 bg-zinc-50 px-3 py-2 font-medium text-zinc-900 transition hover:bg-zinc-100',
          keypad ? 'rounded-t-2xl text-lg' : 'rounded-l-xl text-base',
        ].join(' ')}
        data-testid="phone-country"
        title={country}
        aria-label={`Country code: ${current?.name ?? country} +${callingCode}`}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        disabled={disabled}
        onClick={() => setOpen(!open)}
      >
        <Flag country={country} />
        <span>+{callingCode}</span>
        <span className="text-xs text-zinc-400" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="absolute top-[calc(100%+6px)] left-0 z-20 w-[min(20rem,85vw)] overflow-hidden rounded-2xl bg-white shadow-2xl ring-1 ring-zinc-900/10" onKeyDown={(event) => event.key === 'Escape' && setOpen(false)}>
          <input
            className="search-icon w-full border-b border-zinc-100 py-3 pr-3 pl-10 text-sm text-zinc-900 outline-none placeholder:text-zinc-400"
            placeholder="Search country or code"
            aria-label="Search country or code"
            value={search}
            autoFocus
            onChange={(event) => setSearch(event.target.value)}
          />
          <ul className="max-h-60 list-none overflow-y-auto p-1.5" id={listId} role="listbox" aria-label="Countries">
            {options.map((option, index) => (
              <li
                key={option.country}
                role="option"
                aria-selected={option.country === country}
                tabIndex={0}
                onKeyDown={(event) => (event.key === 'Enter' || event.key === ' ') && choose(option.country)}
                className={`flex cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-zinc-900 hover:bg-zinc-100 focus:bg-zinc-100 focus:outline-none aria-selected:bg-indigo-50 aria-selected:font-semibold aria-selected:text-indigo-700 ${
                  !option.common && options[index - 1]?.common ? 'mt-1.5 rounded-t-none border-t border-zinc-100 pt-2.5' : ''}`}
                onClick={() => choose(option.country)}
              >
                <Flag country={option.country} lazy />
                <span className="min-w-13 tabular-nums">+{option.callingCode}</span>
                <span className="truncate text-zinc-500">{option.name}</span>
              </li>
            ))}
            {options.length === 0 && <li className="p-3 text-sm text-zinc-500">No country matches.</li>}
          </ul>
        </div>
      )}
    </div>
  )
}
