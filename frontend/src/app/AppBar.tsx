import type { ReactNode } from 'react'

interface AppBarProps {
  /** Accessible name of the bar, e.g. "Product tabs". */
  label: string
  onOpenMenu: () => void
  children: ReactNode
}

/** The dark bottom bar of every screen: menu button on the left, screen-specific content after it. */
export function AppBar({ label, onOpenMenu, children }: AppBarProps) {
  return (
    <nav className="col-span-full flex gap-1 overflow-x-auto bg-zinc-800 px-2 shadow-[0_-1px_0_var(--color-zinc-700)] scrollbar-none print:hidden" aria-label={label}>
      <button
        type="button"
        className="my-1.5 mr-1 flex min-h-11 w-12 flex-none items-center justify-center rounded-xl bg-indigo-500 text-white shadow-lg shadow-indigo-500/30 transition hover:bg-indigo-400 active:scale-95"
        aria-label="Open menu"
        onClick={onOpenMenu}
      >
        <svg viewBox="0 0 20 20" fill="currentColor" className="size-5" aria-hidden="true">
          <path fillRule="evenodd" d="M2 4.75A.75.75 0 0 1 2.75 4h14.5a.75.75 0 0 1 0 1.5H2.75A.75.75 0 0 1 2 4.75Zm0 5.25a.75.75 0 0 1 .75-.75h14.5a.75.75 0 0 1 0 1.5H2.75A.75.75 0 0 1 2 10Zm0 5.25a.75.75 0 0 1 .75-.75h14.5a.75.75 0 0 1 0 1.5H2.75a.75.75 0 0 1-.75-.75Z" clipRule="evenodd" />
        </svg>
      </button>
      {children}
    </nav>
  )
}
