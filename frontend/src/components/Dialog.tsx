import type { ReactNode } from 'react'

interface DialogProps {
  title: string
  /** Wider panel for forms. */
  wide?: boolean
  children: ReactNode
}

/** Centred modal panel over a dimmed backdrop. */
export function Dialog({ title, wide = false, children }: DialogProps) {
  return (
    <div className="fixed inset-0 z-30 grid place-items-center bg-zinc-950/50 p-4 backdrop-blur-sm">
      <section
        className={`max-h-full overflow-y-auto rounded-3xl bg-white p-6 shadow-2xl ring-1 ring-zinc-900/10 max-md:p-5 ${wide ? 'w-[min(44rem,100%)]' : 'w-[min(32rem,100%)]'}`}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <h2 className="mb-5 text-lg font-bold tracking-tight text-zinc-900">{title}</h2>
        {children}
      </section>
    </div>
  )
}
