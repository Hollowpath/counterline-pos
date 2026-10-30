import { useState, type FormEvent } from 'react'
import { formatDateTime, formatTime } from '../../lib/dates'
import { detail, input } from '../../styles/ui'
import type { OpenShift, Timecards } from './types'

/** Closes a shift for a staff member: the clock-out they should have made, and why it is being entered. */
export type CloseShift = (staffId: number, outAt: string, reason: string) => Promise<void>

/** A value for a datetime-local input: this minute, in the browser's (the outlet's) time. */
function localNow(): string {
  const now = new Date()
  now.setSeconds(0, 0)
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

/**
 * The Timecards report: whoever is clocked in now (a shift left open is flagged and, on the dashboard,
 * can be closed with a reason), then the period's clock events, a correction marked with who made it and why.
 */
export function TimecardsView({ data, onCloseShift, onClosed }: { data: Timecards; onCloseShift?: CloseShift; onClosed: () => void }) {
  const { entries, openShifts } = data
  return (
    <>
      {openShifts.length > 0 && (
        <section aria-label="Open shifts">
          <h3 className={detail.section}>Clocked in now</h3>
          <ul className={detail.rows}>
            {openShifts.map((shift) => (
              <OpenShiftRow key={shift.staffId} shift={shift} onCloseShift={onCloseShift} onClosed={onClosed} />
            ))}
          </ul>
        </section>
      )}
      <h3 className={detail.section}>Clock events</h3>
      {entries.length === 0 ? <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No clock-ins in this period.</p> : (
        <ul className={detail.rows}>
          {entries.map((entry, index) => (
            <li key={index} className={entry.correctedBy ? 'flex-col' : ''}>
              <span className="flex justify-between gap-4">
                <span>{entry.staffName}</span>
                <span className={`font-semibold ${entry.type === 'IN' ? 'text-green-600' : 'text-red-600'}`}>
                  {entry.type} · {formatTime(entry.at)}
                </span>
              </span>
              {entry.correctedBy && (
                <span className={detail.sub} data-testid="correction">
                  Corrected by {entry.correctedBy}{entry.correctedAt && ` on ${formatDateTime(entry.correctedAt)}`}: {entry.correctedReason}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

function OpenShiftRow({ shift, onCloseShift, onClosed }: { shift: OpenShift; onCloseShift?: CloseShift; onClosed: () => void }) {
  const [closing, setClosing] = useState(false)
  const [outAt, setOutAt] = useState(localNow)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onCloseShift!(shift.staffId, new Date(outAt).toISOString(), reason.trim())
      onClosed()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The shift could not be closed')
      setBusy(false)
    }
  }

  return (
    <li className="flex-col">
      <span className="flex items-center justify-between gap-4">
        <span>
          {shift.staffName}
          <span className={detail.sub}>Clocked in {formatDateTime(shift.since)}</span>
        </span>
        <span className="flex items-center gap-3">
          {shift.stale && (
            <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800" data-testid="stale-shift">
              Still open, clock-out forgotten?
            </span>
          )}
          {onCloseShift && !closing && (
            <button type="button" className="btn py-1.5 text-sm" onClick={() => setClosing(true)}>
              Close shift
            </button>
          )}
        </span>
      </span>
      {closing && (
        <form className="mt-3 flex flex-col gap-3" aria-label={`Close ${shift.staffName}'s shift`} onSubmit={submit}>
          <label className="flex flex-col gap-1 text-xs font-medium text-zinc-600">
            Clocked out at
            <input type="datetime-local" className={input} value={outAt} required onChange={(event) => setOutAt(event.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-xs font-medium text-zinc-600">
            Reason
            <input className={input} value={reason} maxLength={200} required onChange={(event) => setReason(event.target.value)} />
          </label>
          {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
          <span className="flex justify-end gap-2">
            <button type="button" className="btn py-1.5 text-sm" onClick={() => setClosing(false)}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary py-1.5 text-sm" disabled={busy || reason.trim() === ''}>
              Close shift
            </button>
          </span>
        </form>
      )}
    </li>
  )
}
