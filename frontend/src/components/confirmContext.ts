import { createContext, useContext } from 'react'

export interface ConfirmOptions {
  /** The question, e.g. "Delete the Gifts tab?" */
  title: string
  /** What happens, and what is kept. */
  message?: string
  /** The action by name, e.g. "Delete tab": never just "OK". */
  confirmLabel: string
  /** `danger` (the default) for anything that removes, deactivates or cannot be undone. */
  tone?: 'danger' | 'primary'
}

/** Asks the question in an in-app dialog; resolves true only if the person confirms (Cancel, Escape and the backdrop all resolve false). */
export type Confirm = (options: ConfirmOptions) => Promise<boolean>

export const ConfirmContext = createContext<Confirm | null>(null)

export function useConfirm(): Confirm {
  const confirm = useContext(ConfirmContext)
  if (!confirm) throw new Error('useConfirm needs a ConfirmProvider above it')
  return confirm
}
