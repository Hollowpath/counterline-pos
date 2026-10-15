import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** Where the wheels start while no date is set. */
const DEFAULT_DATE = { day: 1, month: 1, year: 2000 }

/** Roughly the drop-down's size: below the field if it fits there, else above; and lined up
 * with the field's right edge when it would run off the screen on the right. */
const POPOVER_HEIGHT = 260
const POPOVER_WIDTH = 250

interface Parts {
  day: number
  month: number
  year: number
}

function parse(value: string): Parts | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  return match ? { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) } : null
}

const daysIn = (month: number, year: number) => new Date(Date.UTC(year, month, 0)).getUTCDate()
const pad = (value: number) => String(value).padStart(2, '0')
const iso = ({ day, month, year }: Parts) => `${year}-${pad(month)}-${pad(day)}`

/**
 * A date field for a touchscreen: it looks like any other field, and tapping it drops down three
 * wheels (day, month, year) turned like the dials of a combination lock, as on a phone's date
 * picker. Turning a wheel sets the date at once; Done closes it, Clear empties it. {@code value}
 * is "YYYY-MM-DD" or "" (not set).
 */
export function DateField({ value, onChange, label = 'Date of birth', disabled = false, years = 100 }: {
  value: string
  onChange: (value: string) => void
  label?: string
  disabled?: boolean
  /** How many years back from this one the year wheel goes. */
  years?: number
}) {
  const [open, setOpen] = useState(false)
  const [place, setPlace] = useState({ up: false, end: false })
  const root = useRef<HTMLDivElement>(null)
  const field = useRef<HTMLButtonElement>(null)
  const parts = parse(value)
  const shown = parts ?? DEFAULT_DATE
  const thisYear = new Date().getFullYear()
  const yearOptions = Array.from({ length: years + 1 }, (_, index) => thisYear - index)

  // A tap anywhere else closes it.
  useEffect(() => {
    if (!open) return undefined
    const away = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', away)
    return () => document.removeEventListener('pointerdown', away)
  }, [open])

  function toggle() {
    if (!open && field.current) {
      const box = field.current.getBoundingClientRect()
      setPlace({
        up: window.innerHeight - box.bottom < POPOVER_HEIGHT && box.top > POPOVER_HEIGHT,
        end: box.left + POPOVER_WIDTH > window.innerWidth,
      })
    }
    setOpen(!open)
  }

  function close() {
    setOpen(false)
    field.current?.focus()
  }

  function pick(changed: Partial<Parts>) {
    const next = { ...shown, ...changed }
    // 31 Apr becomes 30 Apr, 29 Feb 2001 becomes 28 Feb.
    next.day = Math.min(next.day, daysIn(next.month, next.year))
    onChange(iso(next))
  }

  const text = parts ? `${parts.day} ${MONTHS[parts.month - 1]} ${parts.year}` : ''

  return (
    <div className="relative text-[1rem] [--date-field-row:2em]" ref={root} onKeyDown={(event) => event.key === 'Escape' && open && close()}>
      <button
        ref={field}
        type="button"
        className={[
          'flex w-full cursor-pointer items-center justify-between gap-2 rounded-xl bg-white px-3.5 py-2.5 text-left text-base text-zinc-900 shadow-xs',
          'ring-inset transition focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:outline-none',
          'disabled:cursor-default disabled:bg-zinc-50 disabled:opacity-60',
          open ? 'ring-2 ring-indigo-500' : 'ring-1 ring-zinc-200',
        ].join(' ')}
        aria-label={`${label}: ${text || 'not set'}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={toggle}
      >
        <span className={text ? undefined : 'text-zinc-400'}>{text || 'DD / MM / YYYY'}</span>
        <span className="text-xs text-zinc-400" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div
          className={[
            'absolute z-30 w-full min-w-60 animate-pop-in rounded-2xl bg-white p-2.5 text-zinc-900 shadow-2xl ring-1 ring-zinc-900/10',
            place.up ? 'bottom-[calc(100%+0.3rem)]' : 'top-[calc(100%+0.3rem)]',
            place.end ? 'right-0' : 'left-0',
          ].join(' ')}
          role="dialog" aria-label={label}>
          <div
            className={[
              'relative grid grid-cols-[1fr_1.2fr_1.4fr]',
              // The band the picked row sits in: the middle of five.
              "before:pointer-events-none before:absolute before:inset-x-0 before:top-[calc(var(--date-field-row)*2)] before:content-['']",
              'before:h-(--date-field-row) before:rounded-lg before:bg-indigo-50 before:ring-1 before:ring-indigo-100',
            ].join(' ')}
          >
            <Wheel
              label="Day"
              options={Array.from({ length: daysIn(shown.month, shown.year) }, (_, index) => String(index + 1))}
              selected={shown.day - 1}
              onSelect={(index) => pick({ day: index + 1 })}
            />
            <Wheel label="Month" options={MONTHS} selected={shown.month - 1} onSelect={(index) => pick({ month: index + 1 })} />
            <Wheel
              label="Year"
              options={yearOptions.map(String)}
              selected={Math.max(0, yearOptions.indexOf(shown.year))}
              onSelect={(index) => pick({ year: yearOptions[index] })}
            />
          </div>
          <div className="mt-[0.4rem] flex items-center justify-between">
            <button
              type="button"
              className="rounded-lg px-3 py-1.5 text-sm font-semibold text-zinc-500 transition hover:bg-zinc-100 hover:text-zinc-800"
              onClick={() => {
                onChange('')
                close()
              }}
            >
              Clear
            </button>
            <button type="button" className="rounded-lg bg-indigo-600 px-3.5 py-1.5 text-sm font-semibold text-white shadow-sm transition hover:bg-indigo-500" onClick={close}>
              Done
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/** One dial: its rows snap to the middle, and the row in the middle is the one picked. */
function Wheel({ label, options, selected, onSelect }: {
  label: string
  options: string[]
  selected: number
  onSelect: (index: number) => void
}) {
  const list = useRef<HTMLDivElement>(null)
  const settling = useRef<number | undefined>(undefined)
  const rowHeight = () => list.current?.querySelector<HTMLElement>('[role="option"]')?.offsetHeight ?? 0

  // The picked row in the middle: on opening, and when the date changes another way.
  useLayoutEffect(() => {
    const element = list.current
    const row = rowHeight()
    if (element && row > 0 && Math.abs(element.scrollTop - selected * row) > 2) element.scrollTop = selected * row
  }, [selected, options.length])
  useEffect(() => () => window.clearTimeout(settling.current), [])

  // Picks the row in the middle once the dial stops turning.
  function onScroll() {
    window.clearTimeout(settling.current)
    settling.current = window.setTimeout(() => {
      const element = list.current
      const row = rowHeight()
      if (!element || row === 0) return
      const index = Math.min(options.length - 1, Math.max(0, Math.round(element.scrollTop / row)))
      if (index !== selected) onSelect(index)
    }, 120)
  }

  function onKeyDown(event: KeyboardEvent) {
    const moves: Record<string, number> = {
      ArrowUp: selected - 1, ArrowDown: selected + 1, Home: 0, End: options.length - 1,
      PageUp: selected - 5, PageDown: selected + 5,
    }
    if (!(event.key in moves)) return
    event.preventDefault()
    onSelect(Math.min(options.length - 1, Math.max(0, moves[event.key])))
  }

  return (
    <div
      ref={list}
      className={[
        'h-[calc(var(--date-field-row)*5)] snap-y snap-mandatory overflow-y-auto overscroll-contain py-[calc(var(--date-field-row)*2)]',
        'scrollbar-none touch-pan-y [-webkit-mask-image:linear-gradient(transparent,#000_35%,#000_65%,transparent)]',
        '[mask-image:linear-gradient(transparent,#000_35%,#000_65%,transparent)]',
        'focus-visible:rounded-lg focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-indigo-500',
      ].join(' ')}
      role="listbox" aria-label={label} tabIndex={0} onScroll={onScroll} onKeyDown={onKeyDown}>
      {options.map((option, index) => (
        <div
          key={option}
          role="option"
          aria-selected={index === selected}
          className="relative flex h-(--date-field-row) cursor-pointer snap-center items-center justify-center text-zinc-500 tabular-nums select-none aria-selected:font-semibold aria-selected:text-indigo-700"
          onClick={() => onSelect(index)}
        >
          {option}
        </div>
      ))}
    </div>
  )
}
