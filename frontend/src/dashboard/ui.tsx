import type { ReactNode } from 'react'
import { pill } from '../styles/ui'
import { sectionTitle } from './styles'

/**
 * A dashboard page: a white header strip (title, what the page is for, its controls), then the
 * page on grey. `flush` for a list-and-detail page that fills the rest of the screen.
 */
export function Page({ label, title, description, actions, flush = false, children }: {
  /** The page's accessible name, when it isn't the title. */
  label?: string
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  flush?: boolean
  children: ReactNode
}) {
  return (
    // A flush page is exactly the screen's height, so its list and detail scroll on their own while
    // the header and the rest stay put; any other page grows and the main area scrolls it.
    <section aria-label={label ?? (typeof title === 'string' ? title : undefined)} className={`flex flex-col ${flush ? 'h-full print:h-auto' : 'min-h-full'}`}>
      <header className="sticky top-0 z-[5] flex flex-wrap items-center justify-between gap-x-6 gap-y-3 border-b border-zinc-200/80 bg-white/85 px-8 py-5 backdrop-blur max-md:static max-md:px-4 max-md:py-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold tracking-tight text-zinc-900 max-md:text-xl">{title}</h1>
          {description && <p className="mt-1 max-w-[62rem] text-sm text-zinc-500">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2 max-md:w-full">{actions}</div>}
      </header>
      <div className={flush ? 'flex min-h-0 flex-1 flex-col' : 'flex flex-1 flex-col gap-5 p-8 max-md:gap-4 max-md:p-4'}>{children}</div>
    </section>
  )
}

/** A white card with a small-caps title. */
export function Card({ title, className = '', children }: { title: string; className?: string; children: ReactNode }) {
  return (
    <section aria-label={title} className={`min-w-0 rounded-2xl bg-white p-5 shadow-sm ring-1 ring-zinc-900/5 ${className}`}>
      <h2 className={sectionTitle}>{title}</h2>
      {children}
    </section>
  )
}

/** The till's segmented tabs (Past Orders: Open, Closed, Voided). */
export function Segmented<T extends string>({ label, options, value, onChange }: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <div role="tablist" aria-label={label} className="flex rounded-xl bg-zinc-100 p-1 ring-1 ring-zinc-200/70 ring-inset max-md:w-full">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="tab"
          aria-selected={option.value === value}
          className={[
            'flex-1 rounded-lg px-3 py-1.5 text-sm font-medium whitespace-nowrap text-zinc-500 transition hover:text-zinc-900',
            'aria-selected:bg-white aria-selected:font-semibold aria-selected:text-zinc-900 aria-selected:shadow-sm aria-selected:ring-1 aria-selected:ring-zinc-900/5',
          ].join(' ')}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

/** A headline figure, on a card with the item tiles' indigo glow along the top. */
export function Tile({ label, value, children }: { label: string; value: string; children?: ReactNode }) {
  return (
    <div className="relative flex min-w-0 flex-col gap-1 overflow-hidden rounded-2xl bg-white px-5 pt-5 pb-4 shadow-sm ring-1 ring-zinc-900/5 before:absolute before:inset-x-0 before:top-0 before:h-1 before:bg-linear-to-r before:from-indigo-500 before:to-fuchsia-500 before:content-['']">
      <dt className="text-xs font-semibold tracking-wider text-zinc-500 uppercase">{label}</dt>
      <dd className="text-[1.75rem] leading-tight font-bold tracking-tight text-zinc-900 max-md:text-[1.35rem]">{value}</dd>
      {children}
    </div>
  )
}

/** "Saved", or why it wasn't. */
export function Status({ status }: { status: { tone: 'ok' | 'error'; text: string } | null }) {
  if (!status) return null
  return (
    <span className={`ml-2 ${pill.base} ${status.tone === 'ok' ? pill.green : pill.red}`} role="status">
      {status.text}
    </span>
  )
}

/** A notice across the page, e.g. figures that couldn't refresh. */
export function Warning({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-xl bg-orange-50 px-4 py-2.5 text-sm font-medium text-orange-800 ring-1 ring-orange-200 ring-inset" role="alert">
      {children}
    </p>
  )
}
