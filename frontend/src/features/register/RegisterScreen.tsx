import { useState, type FormEvent } from 'react'
import { ApiError, postJson } from '../../api/http'
import { useApi } from '../../api/useApi'
import { AppBar } from '../../app/AppBar'
import { Dialog } from '../../components/Dialog'
import { LoadState } from '../../components/LoadState'
import { formatTime } from '../../lib/dates'
import { formatRM, formatSignedRM, parseRM, toSen } from '../../lib/money'
import { printScreen } from '../receipts/printReceipt'
import { SalesSummaryView } from '../reports/SalesSummaryView'
import { registerPath as PATH, useRegister, type RegisterStatus, type Session } from './registerApi'
import { appBarTitle, detail, dialogActions, input, segmented } from '../../styles/ui'
/** The banner's card: frosted glass over the colour. */
const card = 'relative w-full max-w-md rounded-[2rem] bg-white/10 px-8 py-10 shadow-2xl shadow-black/15 ring-1 ring-white/25 backdrop-blur-md max-md:px-5 max-md:py-8'
/** A soft light behind the card. */
const glow = "pointer-events-none absolute -top-40 left-1/2 size-[36rem] -translate-x-1/2 rounded-full bg-white/15 blur-3xl content-['']"

/** e.g. "Oct 12, 2026 at 9:55:48 AM". */
function formatStamp(iso: string): string {
  const at = new Date(iso)
  const day = at.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  return `${day} at ${at.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' })}`
}

/**
 * Manage Register: the whole screen green (open) or red (closed), with
 * when it was opened or closed and by whom, Open / Close Register and Pay In / Out. The session's
 * figures are one tap away (the print button), ready to print.
 */
/** The whole screen green or red, with little else on it. */
const banner = 'relative flex flex-1 flex-col items-center justify-center overflow-hidden px-4 py-8 text-center text-white'
/** White outlined buttons on the banner. */
const bannerButton = [
  'min-h-12 rounded-2xl bg-white/15 px-5 py-2.5 text-base font-semibold text-white shadow-lg shadow-black/10 ring-1 ring-white/40 backdrop-blur transition ring-inset',
  'enabled:hover:bg-white enabled:hover:text-zinc-900 enabled:active:scale-[0.97] disabled:opacity-50',
].join(' ')

export function RegisterScreen({ onOpenMenu }: { onOpenMenu: () => void }) {
  const status = useApi<RegisterStatus>(PATH)
  const [dialog, setDialog] = useState<'open' | 'close' | 'movement' | 'summary' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const register = useRegister()

  async function act(action: () => Promise<unknown>) {
    setError(null)
    try {
      await action()
      setDialog(null)
      status.reload()
      register.refresh()
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Offline')
      throw caught
    }
  }

  let content
  if (status.status !== 'success') {
    content = <LoadState status={status.status} message={status.status === 'error' ? status.message : undefined} onRetry={status.reload} />
  } else if (status.data.open && status.data.session) {
    const session = status.data.session
    const { openOrders } = status.data
    // Closing waits for placed orders to be paid or voided, and for the sale in the cart to be cleared.
    const blockers = [
      ...(openOrders > 0 ? [`${openOrders} open ${openOrders === 1 ? 'order' : 'orders'}`] : []),
      ...(register.saleOpen ? ['Sale open'] : []),
    ]
    content = (
      <div className={`${banner} bg-linear-to-br from-green-400 via-green-500 to-green-600`}>
        <span className={glow} aria-hidden="true" />
        <div className={card}>
        <span className="mb-4 inline-flex items-center gap-2 rounded-full bg-white/20 px-3 py-1 text-xs font-semibold tracking-wider uppercase ring-1 ring-white/30">
          <span className="size-2 animate-pulse rounded-full bg-white" aria-hidden="true" />
          Register open
        </span>
        <p className="text-xl font-semibold text-green-50">Yes, we&rsquo;re</p>
        <p className="text-[4rem] leading-none font-black tracking-tight drop-shadow-sm max-md:text-[3.2rem]">OPEN</p>
        <p className="mt-6 text-sm leading-relaxed font-medium text-green-50">
          Opened at
          <br />
          {formatStamp(session.openedAt)}
          <br />
          by {session.openedBy}
        </p>
        <p className="mt-4 text-sm leading-relaxed font-medium text-green-50 tabular-nums">
          Opening float: {formatRM(toSen(session.openingFloat))}
          <br />
          Expected cash: {formatSignedRM(toSen(session.expectedCash))}
          {session.expectedCash < 0 && (
            <>
              <br />
              Below zero
            </>
          )}
        </p>
        <div className="mt-8 grid grid-cols-[auto_auto] justify-center gap-3">
          <button type="button" className={bannerButton} disabled={blockers.length > 0} onClick={() => setDialog('close')}>
            Close Register
          </button>
          <PrintButton onClick={() => setDialog('summary')} />
          <button type="button" className={`${bannerButton} col-span-full`} onClick={() => setDialog('movement')}>
            Pay In / Out
          </button>
        </div>
        {session.movements.length > 0 && (
          <ul className="mt-5 flex list-none flex-col gap-1 text-sm font-semibold tabular-nums" aria-label="Pay In / Out this session">
            {session.movements.map((movement) => (
              <li key={movement.id}>
                {movement.reason}: {formatRM(toSen(movement.amount))}
              </li>
            ))}
          </ul>
        )}
        {blockers.length > 0 && (
          // What still has to be settled before closing: it must stand out against the green.
          <p className="mt-6 flex animate-rise-in items-center justify-center gap-2 rounded-2xl bg-red-600 px-4 py-3 text-sm font-bold text-white shadow-lg shadow-red-900/30 ring-2 ring-white/70" role="status">
            <svg viewBox="0 0 20 20" fill="currentColor" className="size-5 shrink-0" aria-hidden="true"><path fillRule="evenodd" d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495ZM10 5a.75.75 0 0 1 .75.75v3.5a.75.75 0 0 1-1.5 0v-3.5A.75.75 0 0 1 10 5Zm0 9a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" clipRule="evenodd" /></svg>
            {blockers.join(' · ')}
          </p>
        )}
        </div>
      </div>
    )
  } else {
    const last = status.data.lastClosed
    content = (
      <div className={`${banner} bg-linear-to-br from-red-500 via-red-600 to-red-800`}>
        <span className={glow} aria-hidden="true" />
        <div className={card}>
        <span className="mb-4 inline-flex items-center gap-2 rounded-full bg-white/20 px-3 py-1 text-xs font-semibold tracking-wider uppercase ring-1 ring-white/30">
          <span className="size-2 rounded-full bg-white/70" aria-hidden="true" />
          Register closed
        </span>
        <p className="text-xl font-semibold text-red-50">Sorry, we&rsquo;re</p>
        <p className="text-[4rem] leading-none font-black tracking-tight drop-shadow-sm max-md:text-[3.2rem]">CLOSED</p>
        {last?.closedAt && (
          <p className="mt-6 text-sm leading-relaxed font-medium text-red-50">
            Closed at
            <br />
            {formatStamp(last.closedAt)}
            <br />
            by {last.closedBy}
          </p>
        )}
        <div className="mt-8 grid grid-cols-[auto_auto] justify-center gap-3">
          <button
            type="button"
            className={bannerButton}
            onClick={() => setDialog('open')}
          >
            Open Register
          </button>
          {last && <PrintButton onClick={() => setDialog('summary')} />}
        </div>
        </div>
      </div>
    )
  }

  const session = status.status === 'success' ? status.data.session : null
  const summarised = status.status === 'success' ? (status.data.session ?? status.data.lastClosed) : null

  return (
    <div className="grid h-dvh grid-rows-[minmax(0,1fr)_auto]">
      <main className="relative flex overflow-y-auto bg-zinc-100">
        {error && dialog === null && (
          <p className="absolute top-4 left-1/2 z-[1] -translate-x-1/2 rounded-xl bg-white px-4 py-2.5 text-sm font-medium text-red-600 shadow-lg ring-1 ring-red-200" role="alert">
            {error}
          </p>
        )}
        {content}
      </main>

      {dialog === 'summary' && summarised && (
        <Dialog title="Register summary" wide onClose={() => setDialog(null)} closeOnBackdrop>
          <div className="max-h-[60vh] overflow-y-auto">
            <p className="text-sm text-zinc-500">
              Opened {formatStamp(summarised.openedAt)} by {summarised.openedBy}
              {summarised.closedAt && `, closed ${formatStamp(summarised.closedAt)} by ${summarised.closedBy}`}
            </p>
            <SessionDetails session={summarised} />
          </div>
          <div className={dialogActions}>
            <button type="button" className="btn" onClick={printScreen}>
              Print
            </button>
            <button type="button" className="btn btn-primary" onClick={() => setDialog(null)}>
              Done
            </button>
          </div>
        </Dialog>
      )}

      {dialog === 'open' && (
        <OpenDialog
          error={error}
          onConfirm={(openingFloat) => act(() => postJson(`${PATH}/open`, { openingFloat }))}
          onCancel={() => {
            setDialog(null)
            setError(null)
          }}
        />
      )}
      {dialog === 'close' && session && (
        <CloseDialog
          error={error}
          onConfirm={() => act(() => postJson(`${PATH}/close`, { version: session.version, note: null }))}
          onCancel={() => {
            setDialog(null)
            setError(null)
          }}
        />
      )}
      {dialog === 'movement' && (
        <MovementDialog
          error={error}
          onConfirm={(movement) => act(() => postJson(`${PATH}/movements`, movement))}
          onCancel={() => {
            setDialog(null)
            setError(null)
          }}
        />
      )}

      <AppBar label="Manage register" onOpenMenu={onOpenMenu}>
        <p className={appBarTitle}>Manage Register</p>
      </AppBar>
    </div>
  )
}

/** The printer button beside Open / Close Register: this session's figures, to print. */
function PrintButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className={`${bannerButton} inline-flex items-center justify-center px-3!`} aria-label="Register summary" title="Register summary" onClick={onClick}>
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        <path
          fill="currentColor"
          d="M7 3h10v4H7V3Zm-2 5h14a3 3 0 0 1 3 3v6h-4v4H6v-4H2v-6a3 3 0 0 1 3-3Zm3 8v3h8v-3H8Zm10-5.5a1 1 0 1 0 0 2 1 1 0 0 0 0-2Z"
        />
      </svg>
    </button>
  )
}

function SessionDetails({ session }: { session: Session }) {
  return (
    <section aria-label="Register session">
      <h3 className={detail.section}>Sales this session</h3>
      <SalesSummaryView summary={session.sales} />

      <h3 className={detail.section}>Cash drawer</h3>
      <ul className={detail.rows} aria-label="Cash drawer">
        <li>
          <span>Opening float</span>
          <span>{formatRM(toSen(session.openingFloat))}</span>
        </li>
        <li>
          <span>{session.closedAt ? 'Expected cash at closing' : 'Expected cash'}</span>
          <span>{formatSignedRM(toSen(session.expectedCash))}</span>
        </li>
      </ul>

      <h3 className={detail.section}>Pay In / Out</h3>
      {session.movements.length === 0 ? (
        <p className="rounded-2xl bg-zinc-50 p-6 text-center text-sm text-zinc-500 ring-1 ring-zinc-200/70 ring-inset">None.</p>
      ) : (
        <ul className={detail.rows}>
          {session.movements.map((movement) => (
            <li key={movement.id}>
              <span>
                {movement.reason}
                <span className={detail.sub}>
                  {formatTime(movement.createdAt)} · {movement.staffName}
                </span>
              </span>
              <span>
                {movement.direction === 'OUT' ? '−' : '+'}
                {formatRM(toSen(movement.amount))}
              </span>
            </li>
          ))}
        </ul>
      )}
      {session.closingNote && <p className="mt-3 text-sm text-zinc-500">Note: {session.closingNote}</p>}
    </section>
  )
}

/** The cash the drawer starts with, declared once: RM0.00 unless said otherwise. */
function OpenDialog({ error, onConfirm, onCancel }: {
  error: string | null
  onConfirm: (openingFloat: number) => Promise<void>
  onCancel: () => void
}) {
  const [openingFloat, setOpeningFloat] = useState('0.00')
  const [busy, setBusy] = useState(false)
  const floatSen = parseRM(openingFloat)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (floatSen === null || busy) return
    setBusy(true)
    await onConfirm(floatSen / 100).catch(() => setBusy(false))
  }

  return (
    <Dialog title="Open Register" onClose={busy ? undefined : onCancel}>
      <form onSubmit={submit}>
        <label className="grid gap-1.5 text-sm font-medium text-zinc-700">
          Opening float (RM)
          <input className={input} inputMode="decimal" value={openingFloat} disabled={busy} onChange={(event) => setOpeningFloat(event.target.value)} />
        </label>
        <p className="mt-2 text-xs text-zinc-500">The cash in the drawer as it opens. It can't be changed afterwards: correct it with a Pay In / Out.</p>
        {error && (
          <p className="mt-3 text-sm font-medium text-red-600" role="alert">
            {error}
          </p>
        )}
        <div className={dialogActions}>
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || floatSen === null}>
            Open Register
          </button>
        </div>
      </form>
    </Dialog>
  )
}

/** Just a confirmation: the session's figures are behind the print button. */
function CloseDialog({ error, onConfirm, onCancel }: {
  error: string | null
  onConfirm: () => Promise<void>
  onCancel: () => void
}) {
  const [busy, setBusy] = useState(false)

  async function confirm() {
    setBusy(true)
    await onConfirm().catch(() => setBusy(false))
  }

  return (
    <Dialog title="Close Register" onClose={busy ? undefined : onCancel}>
      <p className="text-sm text-zinc-600">Close the register? Payments can't be taken until it is opened again.</p>
      {error && (
        <p className="mt-3 text-sm font-medium text-red-600" role="alert">
          {error}
        </p>
      )}
      <div className={dialogActions}>
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
        <button type="button" className="btn btn-danger" onClick={() => void confirm()} disabled={busy}>
          Close Register
        </button>
      </div>
    </Dialog>
  )
}

function MovementDialog({ error, onConfirm, onCancel }: {
  error: string | null
  onConfirm: (movement: { direction: 'IN' | 'OUT'; amount: number; reason: string }) => Promise<void>
  onCancel: () => void
}) {
  const [direction, setDirection] = useState<'IN' | 'OUT'>('IN')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const amountSen = parseRM(amount)
  const valid = amountSen !== null && amountSen > 0 && reason.trim() !== ''

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!valid) return
    setBusy(true)
    await onConfirm({ direction, amount: amountSen / 100, reason: reason.trim() }).catch(() => setBusy(false))
  }

  return (
    <Dialog title="Pay In / Out" onClose={busy ? undefined : onCancel}>
      <form onSubmit={submit}>
        <div className={segmented} role="tablist" aria-label="Direction">
          {(['IN', 'OUT'] as const).map((option) => (
            <button key={option} type="button" role="tab" aria-selected={direction === option} onClick={() => setDirection(option)}>
              {option === 'IN' ? 'Pay In' : 'Pay Out'}
            </button>
          ))}
        </div>
        <label className="mt-4 grid gap-1.5 text-sm font-medium text-zinc-700">
          Amount (RM)
          <input className={input} inputMode="decimal" value={amount} disabled={busy} onChange={(event) => setAmount(event.target.value)} />
        </label>
        <label className="mt-4 grid gap-1.5 text-sm font-medium text-zinc-700">
          Reason
          <input className={input} value={reason} maxLength={200} disabled={busy} onChange={(event) => setReason(event.target.value)} />
        </label>
        {error && (
          <p className="mt-3 text-sm font-medium text-red-600" role="alert">
            {error}
          </p>
        )}
        <div className={dialogActions}>
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || !valid}>
            Save
          </button>
        </div>
      </form>
    </Dialog>
  )
}
