import { useState, type FormEvent } from 'react'
import { ApiError, postJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { formatDate, formatDateTime } from '../lib/dates'
import { field } from './styles'
import { dashboardPaths } from './types'
import { detail } from '../styles/ui'

/** A small form on one line: the fields, then its button. */
const form = 'my-2 flex flex-wrap items-center gap-2'

interface PointsHistory {
  balance: number
  /** When the points expire (365 days after the latest completed, unrefunded purchase); null with none, in debt, or with no purchase. */
  expiresAt: string | null
  entries: { type: string; points: number; at: string; orderNumber: string | null; reason: string | null; adjustedBy: string | null }[]
}

interface OwedItem {
  id: number
  promotion: string
  remaining: number
  reason: string
  givenBy: string
  givenAt: string
}

interface OwablePromotion {
  id: number
  name: string
  items: string[]
}

const ENTRY_TEXT: Record<string, string> = {
  EARN: 'Earned',
  REDEEM: 'Redeemed',
  REVERSE_EARN: 'Refund: earned points taken back',
  REVERSE_REDEEM: 'Refund: redeemed points given back',
  ADJUST: 'Adjusted',
  EXPIRE: 'Expired: 365 days since the last purchase',
}

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'Offline'
}

/**
 * On the dashboard's customer page: the member's points (with every change), a points adjustment
 * with a reason, and free items owed because the staff forgot them.
 */
export function MemberRewardsPanel({ customerId }: { customerId: number }) {
  const points = useApi<PointsHistory>(dashboardPaths.points(customerId))
  const owed = useApi<OwedItem[]>(dashboardPaths.owedItems(customerId))

  return (
    <section aria-label="Points and rewards">
      <h3 className={detail.section}>Loyalty Points</h3>
      {points.status !== 'success' ? (
        <LoadState status={points.status} message={points.status === 'error' ? points.message : undefined} onRetry={points.reload} />
      ) : (
        <>
          <p className={`mt-1 mb-3 inline-flex rounded-2xl bg-linear-to-br px-5 py-3 text-2xl font-bold tracking-tight text-white shadow-lg ${points.data.balance < 0 ? 'from-red-600 to-rose-600 shadow-red-600/20' : 'from-indigo-600 to-violet-600 shadow-indigo-600/20'}`} data-testid="points-balance">{points.data.balance} points</p>
          {points.data.expiresAt && (
            <p className="mb-3 text-sm text-zinc-500" data-testid="points-expiry">Points expire on {formatDate(points.data.expiresAt)}</p>
          )}
          {points.data.balance < 0 && (
            <p className="mb-3 text-sm font-medium text-red-600" data-testid="points-owed">
              Owes {-points.data.balance} points: a refund took back points that had already been spent. New purchases earn them back
              first; nothing can be taken away until then.
            </p>
          )}
          <AdjustPoints customerId={customerId} onDone={points.reload} />
          {points.data.entries.length > 0 && (
            <ul className={detail.rows} aria-label="Points history">
              {points.data.entries.map((entry, index) => (
                <li key={index}>
                  <span>
                    {ENTRY_TEXT[entry.type] ?? entry.type}
                    {entry.orderNumber && ` · ${entry.orderNumber}`}
                    <span className={detail.sub}>
                      {formatDateTime(entry.at)}
                      {entry.reason && ` · ${entry.reason} (${entry.adjustedBy})`}
                    </span>
                  </span>
                  <span>{entry.points > 0 ? `+${entry.points}` : entry.points}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      <h3 className={detail.section}>Free Items Owed</h3>
      <p className="mb-3 text-sm text-zinc-500">When the staff forgot to give a free item, owe it here; it is free on the member’s next sale.</p>
      {owed.status === 'success' && owed.data.length > 0 && (
        <ul className={detail.rows} aria-label="Free items owed">
          {owed.data.map((item) => (
            <li key={item.id}>
              <span>
                {item.remaining} × {item.promotion}
                <span className={detail.sub}>{item.reason} · {item.givenBy}, {formatDateTime(item.givenAt)}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
      <OweItem customerId={customerId} onDone={owed.reload} />
    </section>
  )
}

/** The most points one adjustment adds or takes away (the server's limit too). */
const MAX_POINTS = 10_000

/** What is wrong with the points typed, if anything: a whole number, not 0, from -10,000 to 10,000. */
function pointsProblem(text: string): string | null {
  if (text.trim() === '') return null
  const points = Number(text)
  if (!Number.isInteger(points)) return 'Points must be a whole number'
  if (points === 0) return "Points can't be 0"
  if (points < -MAX_POINTS || points > MAX_POINTS) return 'Points must be from -10,000 to 10,000'
  return null
}

/** What is wrong with how many items are owed, if anything. */
function quantityProblem(text: string): string | null {
  const quantity = Number(text)
  return text.trim() !== '' && Number.isInteger(quantity) && quantity >= 1 && quantity <= 10 ? null : 'How many must be a whole number from 1 to 10'
}

function AdjustPoints({ customerId, onDone }: { customerId: number; onDone: () => void }) {
  const [points, setPoints] = useState('')
  const [reason, setReason] = useState('')
  const [reasonTouched, setReasonTouched] = useState(false)
  // What the server answered to the last try; any change to the form takes it away.
  const [error, setError] = useState<string | null>(null)
  const pointsMessage = pointsProblem(points)
  const reasonMessage = reasonTouched && reason.trim() === '' ? 'Give a reason for the adjustment' : null
  const shown = pointsMessage ?? reasonMessage ?? error

  async function adjust(event: FormEvent) {
    event.preventDefault()
    if (points.trim() === '' || pointsMessage || reason.trim() === '') {
      setReasonTouched(true)
      return
    }
    setError(null)
    try {
      await postJson(dashboardPaths.points(customerId), { points: Number(points), reason: reason.trim() })
      setPoints('')
      setReason('')
      setReasonTouched(false)
      onDone()
    } catch (caught) {
      setError(message(caught))
    }
  }

  return (
    <form className={form} aria-label="Adjust points" onSubmit={(event) => void adjust(event)}>
      <input className={`${field} w-28`} aria-label="Points to add or take away" type="number" step="1" min={-MAX_POINTS} max={MAX_POINTS}
        placeholder="+5 or −2" value={points} aria-invalid={pointsMessage !== null}
        onChange={(event) => { setPoints(event.target.value); setError(null) }} />
      <input className={`${field} min-w-56 flex-1`} aria-label="Reason for the adjustment" placeholder="Reason (e.g. points missed on R12)" maxLength={200}
        value={reason} onBlur={() => setReasonTouched(true)} onChange={(event) => { setReason(event.target.value); setError(null) }} />
      <button type="submit" className="btn" disabled={points.trim() === '' || pointsMessage !== null || reason.trim() === ''}>
        Adjust
      </button>
      {shown && <p className="basis-full text-sm font-medium text-red-600" role="alert">{shown}</p>}
    </form>
  )
}

function OweItem({ customerId, onDone }: { customerId: number; onDone: () => void }) {
  const promotions = useApi<OwablePromotion[]>(dashboardPaths.promotions)
  const [promotionId, setPromotionId] = useState('')
  const [promotionTouched, setPromotionTouched] = useState(false)
  const [quantity, setQuantity] = useState('1')
  const [reason, setReason] = useState('')
  const [reasonTouched, setReasonTouched] = useState(false)
  // What the server answered to the last try; any change to the form takes it away.
  const [error, setError] = useState<string | null>(null)
  const quantityMessage = quantityProblem(quantity)
  const promotionMessage = promotionTouched && promotionId === '' ? 'Choose which free item is owed' : null
  const reasonMessage = reasonTouched && reason.trim() === '' ? 'Give a reason for owing' : null
  const shown = quantityMessage ?? promotionMessage ?? reasonMessage ?? error

  async function owe(event: FormEvent) {
    event.preventDefault()
    if (promotionId === '' || quantityMessage || reason.trim() === '') {
      setPromotionTouched(true)
      setReasonTouched(true)
      return
    }
    setError(null)
    try {
      await postJson(dashboardPaths.owedItems(customerId), { promotionId: Number(promotionId), quantity: Number(quantity), reason: reason.trim() })
      setReason('')
      setReasonTouched(false)
      onDone()
    } catch (caught) {
      setError(message(caught))
    }
  }

  return (
    <form className={form} aria-label="Owe a free item" onSubmit={(event) => void owe(event)}>
      <select className={`${field} min-w-56 flex-1`} aria-label="Promotion" value={promotionId}
        onBlur={() => setPromotionTouched(true)} onChange={(event) => { setPromotionId(event.target.value); setError(null) }}>
        <option value="">Free item from…</option>
        {promotions.status === 'success' && promotions.data.map((promotion) => (
          <option key={promotion.id} value={promotion.id}>
            {promotion.name} ({promotion.items.slice(0, 2).join(', ')}{promotion.items.length > 2 ? '…' : ''})
          </option>
        ))}
      </select>
      <input className={`${field} w-24`} aria-label="How many" type="number" min="1" max="10" step="1" value={quantity} aria-invalid={quantityMessage !== null}
        onChange={(event) => { setQuantity(event.target.value); setError(null) }} />
      <input className={`${field} min-w-56 flex-1`} aria-label="Reason for owing" placeholder="Reason (e.g. forgot the birthday can on R40)" maxLength={200}
        value={reason} onBlur={() => setReasonTouched(true)} onChange={(event) => { setReason(event.target.value); setError(null) }} />
      <button type="submit" className="btn" disabled={!promotionId || quantityMessage !== null || reason.trim() === ''}>
        Owe item
      </button>
      {shown && <p className="basis-full text-sm font-medium text-red-600" role="alert">{shown}</p>}
    </form>
  )
}
