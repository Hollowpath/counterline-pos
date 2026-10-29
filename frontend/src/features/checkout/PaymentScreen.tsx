import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../api/http'
import { useApi } from '../../api/useApi'
import { Dialog } from '../../components/Dialog'
import { LoadState } from '../../components/LoadState'
import { formatRM, parseRM, senToInput, toSen } from '../../lib/money'
import { newRequestId } from '../../lib/requestId'
import { useCountdown } from '../../lib/useCountdown'
import { PrintReceiptButton } from '../receipts/PrintReceiptButton'
import { printReceipt } from '../receipts/printReceipt'
import { checkoutPaths, type Order, type PaymentInput, type PaymentMethod } from './types'
import { dialogActions } from '../../styles/ui'

interface PendingPayment {
  method: PaymentMethod
  /** Applied to the sale. */
  amountSen: number
  /** Cashless only: the reference printed by the card or QR terminal. */
  reference: string
  /** Cash only: what the customer handed over. */
  tenderedSen: number | null
}

type Phase =
  | { name: 'collecting' }
  | { name: 'submitting' }
  /** `retryable`: the outcome is unknown (network/server error), so resending the same request is safe. */
  | { name: 'failed'; message: string; retryable: boolean }
  | { name: 'done'; order: Order }

interface PaymentScreenProps {
  amountDueSen: number
  /** Label of the total tile, e.g. "Total (3 items)". */
  totalLabel: string
  /** Records the sale. A retry passes the same request id, so the server records it once. */
  onSubmit: (payments: PaymentInput[], requestId: string) => Promise<Order>
  /** Close without recording anything. */
  onCancel: () => void
  /** The sale was recorded and the cashier acknowledged it. */
  onDone: (order: Order) => void
  doneLabel: string
}

/**
 * Choose a method and confirm. Card and QR payments need the terminal's reference. A sale takes
 * one cashless method and/or cash, so a customer can pay part by card and the rest in cash; for
 * cash, the cashier enters what was handed over and the change is shown.
 */
/** The dialog's main action (Confirm payment, Done). */
const primaryButton = 'btn btn-primary px-6'
const fieldInput = 'rounded-xl bg-white px-3.5 py-3 text-lg font-semibold text-zinc-900 tabular-nums shadow-xs ring-1 ring-zinc-200 transition ring-inset placeholder:text-sm placeholder:font-normal placeholder:text-zinc-400 focus:ring-2 focus:ring-indigo-500 focus:outline-none aria-invalid:ring-2 aria-invalid:ring-red-500'

export function PaymentScreen({ amountDueSen, totalLabel, onSubmit, onCancel, onDone, doneLabel }: PaymentScreenProps) {
  const methods = useApi<PaymentMethod[]>(checkoutPaths.paymentMethods)
  const [requestId] = useState(newRequestId)
  const [payments, setPayments] = useState<PendingPayment[]>([])
  const [phase, setPhase] = useState<Phase>({ name: 'collecting' })

  const paidSen = payments.reduce((sum, payment) => sum + payment.amountSen, 0)
  const remainingSen = amountDueSen - paidSen

  const [methodId, setMethodId] = useState<number | null>(null)
  const [amountInput, setAmountInput] = useState(senToInput(amountDueSen))
  const [reference, setReference] = useState('')
  const [fieldError, setFieldError] = useState<string | null>(null)

  const method = methods.status === 'success' ? methods.data.find((m) => m.id === methodId) : undefined
  const isCash = method?.kind === 'CASH'
  const changeSen = payments.reduce((sum, payment) => sum + (payment.tenderedSen ?? payment.amountSen) - payment.amountSen, 0)

  /** One cash and one cashless payment at most. */
  const kindTaken = (kind: PaymentMethod['kind']) => payments.some((payment) => payment.method.kind === kind)

  async function submit(allPayments: PendingPayment[]) {
    setPhase({ name: 'submitting' })
    try {
      const order = await onSubmit(
        allPayments.map((payment) => ({
          paymentMethodId: payment.method.id,
          amount: payment.amountSen / 100,
          reference: payment.method.kind === 'CASH' ? null : payment.reference.trim(),
          cashTendered: payment.tenderedSen === null ? null : payment.tenderedSen / 100,
        })),
        requestId,
      )
      setPhase({ name: 'done', order })
    } catch (error) {
      const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500
      setPhase({
        name: 'failed',
        message: rejected ? error.message : 'Not confirmed. Retry.',
        retryable: !rejected,
      })
    }
  }

  function chooseMethod(next: PaymentMethod) {
    setMethodId(next.id)
    setFieldError(null)
    setAmountInput(senToInput(remainingSen))
  }

  function confirmPayment(event: FormEvent) {
    event.preventDefault()
    if (!method) return
    const enteredSen = parseRM(amountInput)
    if (enteredSen === null || enteredSen <= 0) {
      setFieldError('Enter an amount such as 12.50')
      return
    }
    let payment: PendingPayment
    if (method.kind === 'CASH') {
      // What the customer handed over: covers up to the remaining amount, the rest is change.
      payment = { method, amountSen: Math.min(enteredSen, remainingSen), reference: '', tenderedSen: enteredSen }
    } else {
      if (enteredSen > remainingSen) {
        setFieldError(`Amount cannot exceed the remaining ${formatRM(remainingSen)}`)
        return
      }
      if (reference.trim() === '') {
        setFieldError(`Enter the ${method.name} reference from the terminal`)
        return
      }
      payment = { method, amountSen: enteredSen, reference: reference.trim(), tenderedSen: null }
    }
    const next = [...payments, payment]
    setPayments(next)
    setFieldError(null)
    setReference('')
    setMethodId(null)
    setAmountInput(senToInput(remainingSen - payment.amountSen))
    if (payment.amountSen === remainingSen) void submit(next)
  }

  function removePayment(index: number) {
    const next = payments.filter((_, i) => i !== index)
    setPayments(next)
    setAmountInput(senToInput(amountDueSen - next.reduce((sum, payment) => sum + payment.amountSen, 0)))
  }

  if (phase.name === 'done') {
    return <SaleComplete order={phase.order} changeSen={changeSen} onDone={onDone} doneLabel={doneLabel} />
  }

  const busy = phase.name === 'submitting'

  return (
    <Dialog title="Payment" onClose={busy ? undefined : onCancel}>
      <dl className="mb-5 grid grid-cols-2 gap-3 [&_dd]:mt-1 [&_dd]:text-[1.6rem] [&_dd]:font-bold [&_dd]:tracking-tight [&_dd]:tabular-nums [&_div]:rounded-2xl [&_div]:p-4 [&_dt]:text-xs [&_dt]:font-semibold [&_dt]:tracking-wider [&_dt]:uppercase">
        {/* The total on grey, what is left to pay lit in indigo. */}
        <div className="bg-zinc-100 ring-1 ring-zinc-200/70 ring-inset [&_dt]:text-zinc-500">
          <dt>{totalLabel}</dt>
          <dd>{formatRM(amountDueSen)}</dd>
        </div>
        <div className="bg-linear-to-br from-indigo-600 to-violet-600 text-white shadow-lg shadow-indigo-600/25 [&_dt]:text-indigo-100">
          <dt>Remaining</dt>
          <dd data-testid="remaining">{formatRM(remainingSen)}</dd>
        </div>
      </dl>

      {payments.length > 0 && (
        <ul
          className={[
            'mb-5 list-none divide-y divide-zinc-100 overflow-hidden rounded-2xl bg-white ring-1 ring-zinc-200 [&_button]:grid [&_button]:size-7 [&_button]:place-items-center',
            '[&_button]:rounded-full [&_button]:text-xs [&_button]:text-red-500 [&_button]:transition [&_button:hover]:bg-red-50',
            '[&_li]:grid [&_li]:grid-cols-[auto_1fr_auto_auto] [&_li]:items-center [&_li]:gap-3 [&_li]:px-4 [&_li]:py-2.5 [&_li]:text-sm',
          ].join(' ')}
          aria-label="Payments">
          {payments.map((payment, index) => (
            <li key={index}>
              <span className="font-semibold text-zinc-900">{payment.method.name}</span>
              <span className="text-xs text-zinc-500">
                {payment.tenderedSen !== null && payment.tenderedSen > payment.amountSen
                  ? `Received ${formatRM(payment.tenderedSen)}, change ${formatRM(payment.tenderedSen - payment.amountSen)}`
                  : payment.reference}
              </span>
              <span className="font-semibold tabular-nums">{formatRM(payment.amountSen)}</span>
              {phase.name === 'collecting' && (
                <button type="button" aria-label={`Remove ${payment.method.name} payment`} onClick={() => removePayment(index)}>
                  ✕
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {phase.name === 'failed' ? (
        <div role="alert">
          <p className="rounded-xl bg-red-50 px-3 py-2.5 text-sm font-medium text-red-700 ring-1 ring-red-200 ring-inset">{phase.message}</p>
          <div className={dialogActions}>
            <button type="button" className="btn" onClick={onCancel}>
              Back
            </button>
            {phase.retryable && (
              <button type="button" className={primaryButton} onClick={() => void submit(payments)}>
                Retry
              </button>
            )}
          </div>
        </div>
      ) : amountDueSen === 0 ? (
        <div className={dialogActions}>
          <p className="flex-1 font-semibold text-green-700">No payment required</p>
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Back
          </button>
          <button type="button" className={primaryButton} onClick={() => void submit([])} disabled={busy}>
            {busy ? 'Completing…' : 'Complete sale'}
          </button>
        </div>
      ) : methods.status !== 'success' ? (
        <LoadState status={methods.status} message={methods.status === 'error' ? methods.message : undefined} onRetry={methods.reload} />
      ) : (
        <form className="grid gap-3" onSubmit={confirmPayment}>
          <fieldset
            className="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-2 [&_legend]:mb-2.5 [&_legend]:text-xs [&_legend]:font-semibold [&_legend]:tracking-wider [&_legend]:text-zinc-500 [&_legend]:uppercase"
            disabled={busy || remainingSen === 0}>
            <legend>Payment method</legend>
            {methods.data.map((option) => (
              <button
                key={option.id}
                type="button"
                className="rounded-2xl bg-white px-2 py-4 text-sm font-semibold text-zinc-700 shadow-xs ring-1 ring-zinc-200 transition ring-inset enabled:hover:-translate-y-0.5 enabled:hover:shadow-md enabled:hover:ring-indigo-300 disabled:opacity-35 aria-pressed:bg-indigo-50 aria-pressed:text-indigo-700 aria-pressed:ring-2 aria-pressed:ring-indigo-500"
                aria-pressed={option.id === methodId}
                disabled={kindTaken(option.kind)}
                onClick={() => chooseMethod(option)}
              >
                {option.name}
              </button>
            ))}
          </fieldset>

          {method && (
            <>
              <label className="grid gap-1.5 text-sm font-medium text-zinc-700">
                {isCash ? 'Cash received (RM)' : 'Amount (RM)'}
                <input
                  className={fieldInput}
                  inputMode="decimal"
                  value={amountInput}
                  disabled={busy}
                  aria-invalid={fieldError !== null}
                  onChange={(event) => setAmountInput(event.target.value)}
                />
              </label>
              {isCash ? (
                <CashChange enteredInput={amountInput} remainingSen={remainingSen} />
              ) : (
                <label className="grid gap-1.5 text-sm font-medium text-zinc-700">
                  Reference
                  <input
                    className={fieldInput}
                    value={reference}
                    maxLength={60}
                    disabled={busy}
                    placeholder="From the terminal, e.g. 4432-0330"
                    onChange={(event) => setReference(event.target.value)}
                  />
                </label>
              )}
            </>
          )}
          {fieldError && <p className="text-sm font-medium text-red-600">{fieldError}</p>}

          <div className={dialogActions}>
            <button type="button" className="btn" onClick={onCancel} disabled={busy}>
              Back
            </button>
            <button type="submit" className={primaryButton} disabled={busy || !method}>
              {busy ? 'Completing…' : 'Confirm payment'}
            </button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

/** How long "Sale complete" stays up before it closes itself. */
const SALE_COMPLETE_SECONDS = 5

function SaleComplete({ order, changeSen, onDone, doneLabel }: {
  order: Order; changeSen: number; onDone: (order: Order) => void; doneLabel: string
}) {
  // Each print gives the dialog its full time again: when the receipt is asked for, and once it has printed.
  const [prints, setPrints] = useState(0)
  const secondsLeft = useCountdown(SALE_COMPLETE_SECONDS, true, () => onDone(order), prints)
  const restart = () => setPrints((count) => count + 1)

  // The receipt prints by itself; Print Receipt is another copy, for a customer who wants one.
  const printed = useRef(false)
  useEffect(() => {
    if (printed.current) return
    printed.current = true
    void printReceipt(order).catch(() => {})
  }, [order])
  useEffect(() => {
    const afterPrint = () => setPrints((count) => count + 1)
    window.addEventListener('afterprint', afterPrint)
    return () => window.removeEventListener('afterprint', afterPrint)
  }, [])
  return (
    <Dialog title="Sale complete">
      <div className="mb-5 flex flex-col items-center gap-1 text-center">
        <span className="mb-2 grid size-16 animate-check-pop place-items-center rounded-full bg-green-100 text-3xl text-green-600 ring-8 ring-green-50" aria-hidden="true">✓</span>
        <p className="text-[2.2rem] leading-tight font-bold tracking-tight">{order.orderNumber}</p>
        <p className="text-sm font-medium text-zinc-600">Receipt {order.receiptCode}</p>
        <p className="text-sm text-zinc-500">{formatRM(toSen(order.total))} paid</p>
        {changeSen > 0 && (
          <p className="mt-4 w-full rounded-2xl bg-linear-to-br from-green-500 to-green-700 p-4 text-[1.6rem] font-extrabold tracking-tight text-white shadow-lg shadow-green-600/25 tabular-nums" role="status">
            Change {formatRM(changeSen)}
          </p>
        )}
      </div>
      <p className="mb-3 text-center text-xs font-medium text-zinc-400" aria-live="polite">
        Closing in {secondsLeft}s
      </p>
      <div className="grid grid-cols-2 gap-2">
        <PrintReceiptButton orderId={order.id} className="btn py-3.5" onPrint={restart} />
        <button type="button" className={`${primaryButton} py-3.5`} onClick={() => onDone(order)} autoFocus>
          {doneLabel}
        </button>
      </div>
    </Dialog>
  )
}

function CashChange({ enteredInput, remainingSen }: { enteredInput: string; remainingSen: number }) {
  const enteredSen = parseRM(enteredInput)
  if (enteredSen === null) return null
  return enteredSen >= remainingSen ? (
    <p className="rounded-xl bg-green-50 px-3 py-2 text-sm font-semibold text-green-700 tabular-nums ring-1 ring-green-200 ring-inset" data-testid="change">
      Change {formatRM(enteredSen - remainingSen)}
    </p>
  ) : (
    <p className="rounded-xl bg-orange-50 px-3 py-2 text-sm font-medium text-orange-800 ring-1 ring-orange-200 ring-inset">{formatRM(remainingSen - enteredSen)} left</p>
  )
}
