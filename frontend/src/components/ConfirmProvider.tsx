import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { ConfirmContext, type Confirm, type ConfirmOptions } from './confirmContext'
import { Dialog } from './Dialog'

interface Pending {
  options: ConfirmOptions
  answer: (confirmed: boolean) => void
}

/**
 * Provides `useConfirm()`: confirmations in the app's own dialog instead of the browser's. Cancel is
 * focused first, so Enter never confirms by accident; Escape and a click on the backdrop cancel.
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null)
  const current = useRef<Pending | null>(null)
  const messageId = useId()

  useEffect(() => () => current.current?.answer(false), [])

  const confirm = useCallback<Confirm>((options) => new Promise<boolean>((resolve) => {
    // A question asked while another is open replaces it: the earlier one is answered "no".
    current.current?.answer(false)
    const next: Pending = {
      options,
      answer: (confirmed) => {
        if (current.current === next) current.current = null
        setPending((open) => (open === next ? null : open))
        resolve(confirmed)
      },
    }
    current.current = next
    setPending(next)
  }), [])

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {pending && (
        <Dialog
          title={pending.options.title}
          role="alertdialog"
          describedBy={pending.options.message ? messageId : undefined}
          onClose={() => pending.answer(false)}
          closeOnBackdrop
        >
          {pending.options.message && <p id={messageId} className="mb-6 text-sm text-zinc-600">{pending.options.message}</p>}
          <div className="flex justify-end gap-2 max-md:flex-col-reverse max-md:[&>button]:w-full">
            <button type="button" className="btn" data-autofocus onClick={() => pending.answer(false)}>
              Cancel
            </button>
            <button type="button" className={`btn ${pending.options.tone === 'primary' ? 'btn-primary' : 'btn-danger'}`} onClick={() => pending.answer(true)}>
              {pending.options.confirmLabel}
            </button>
          </div>
        </Dialog>
      )}
    </ConfirmContext.Provider>
  )
}
