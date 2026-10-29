import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react'

interface DialogProps {
  title: string
  /** Wider panel for forms. */
  wide?: boolean
  /** Called on Escape (and on a backdrop click, if `closeOnBackdrop`). Without it the dialog can only be closed from inside. */
  onClose?: () => void
  /** A click on the dimmed backdrop closes it too. Off by default so a stray tap cannot lose a half-filled form. */
  closeOnBackdrop?: boolean
  /** `alertdialog` for a question that needs an answer before anything else (a confirmation). */
  role?: 'dialog' | 'alertdialog'
  /** Describes the dialog to assistive technology (the id of a paragraph inside it). */
  describedBy?: string
  children: ReactNode
}

/** Open dialogs, the newest last: only the top one answers the keyboard when focus has fallen out of every dialog. */
const open: HTMLElement[] = []

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Centred modal panel over a dimmed backdrop. Focus moves into it when it opens (to the element
 * marked `data-autofocus`, if any), Tab stays inside it, Escape calls `onClose`, and focus returns to
 * what had it before when it closes.
 */
export function Dialog({ title, wide = false, onClose, closeOnBackdrop = false, role = 'dialog', describedBy, children }: DialogProps) {
  const panel = useRef<HTMLElement>(null)
  const titleId = useId()
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    const node = panel.current
    if (!node) return
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null
    // An input that took focus itself (autoFocus) keeps it.
    if (!node.contains(document.activeElement)) {
      (node.querySelector<HTMLElement>('[data-autofocus]') ?? node).focus()
    }
    open.push(node)
    // Content that replaces the focused control (a step of a flow) drops focus to the page: Escape and Tab still reach this dialog.
    function onPageKeyDown(event: globalThis.KeyboardEvent) {
      if (event.target !== document.body || open[open.length - 1] !== node) return
      if (event.key === 'Escape' && onCloseRef.current) {
        onCloseRef.current()
      } else if (event.key === 'Tab') {
        event.preventDefault()
        ;(node.querySelector<HTMLElement>(FOCUSABLE) ?? node).focus()
      }
    }
    document.addEventListener('keydown', onPageKeyDown)
    return () => {
      document.removeEventListener('keydown', onPageKeyDown)
      open.splice(open.indexOf(node), 1)
      if (before && document.contains(before)) before.focus()
    }
  }, [])

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape' && onClose) {
      event.stopPropagation()
      onClose()
    } else if (event.key === 'Tab' && panel.current) {
      const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusable.length === 0) {
        event.preventDefault()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
  }

  return (
    <div
      className="fixed inset-0 z-30 grid animate-fade-in place-items-center bg-zinc-950/50 p-4 backdrop-blur-sm"
      onMouseDown={(event) => {
        if (closeOnBackdrop && onClose && event.target === event.currentTarget) onClose()
      }}
    >
      <section
        ref={panel}
        tabIndex={-1}
        className={`max-h-full animate-pop-in overflow-y-auto rounded-3xl bg-white p-6 shadow-2xl ring-1 ring-zinc-900/10 outline-none max-md:p-5 ${wide ? 'w-[min(44rem,100%)]' : 'w-[min(32rem,100%)]'}`}
        role={role}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={describedBy}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId} className="mb-5 text-lg font-bold tracking-tight text-zinc-900">{title}</h2>
        {children}
      </section>
    </div>
  )
}
