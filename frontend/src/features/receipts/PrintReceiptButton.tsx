import { useState } from 'react'
import { printOrderReceipt } from './printReceipt'

interface PrintReceiptButtonProps {
  orderId: number
  className?: string
  /** Called when the button is pressed, e.g. to give "Sale complete" its full time again. */
  onPrint?: () => void
}

/** Prints the order's customer receipt (paid, unpaid, voided or refunded), as the server has it. */
export function PrintReceiptButton({ orderId, className = 'btn', onPrint }: PrintReceiptButtonProps) {
  const [state, setState] = useState<'idle' | 'loading' | 'error'>('idle')

  async function print() {
    onPrint?.()
    setState('loading')
    try {
      await printOrderReceipt(orderId)
      setState('idle')
    } catch {
      setState('error')
    }
  }

  return (
    <button type="button" className={className} disabled={state === 'loading'} onClick={() => void print()}>
      <svg viewBox="0 0 24 24" className="size-4" fill="currentColor" aria-hidden="true">
        <path d="M7 3h10v4H7V3Zm-2 5h14a3 3 0 0 1 3 3v6h-4v4H6v-4H2v-6a3 3 0 0 1 3-3Zm3 8v3h8v-3H8Zm10-5.5a1 1 0 1 0 0 2 1 1 0 0 0 0-2Z" />
      </svg>
      {state === 'error' ? 'Retry print' : 'Print Receipt'}
    </button>
  )
}
