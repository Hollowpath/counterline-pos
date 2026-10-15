import { useState, type FormEvent } from 'react'
import { ApiError, postJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { formatDateTime } from '../lib/dates'
import { field } from './styles'
import { dashboardPaths } from './types'
import { detail } from '../styles/ui'

/** A small form on one line: the fields, then its button. */
const form = 'my-2 flex flex-wrap items-center gap-2'

interface PointsHistory {
  balance: number
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
          <p className="mt-1 mb-3 inline-flex rounded-2xl bg-linear-to-br from-indigo-600 to-violet-600 px-5 py-3 text-2xl font-bold tracking-tight text-white shadow-lg shadow-indigo-600/20" data-testid="points-balance">{points.data.balance} points</p>
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

function AdjustPoints({ customerId, onDone }: { customerId: number; onDone: () => void }) {
  const [points, setPoints] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function adjust(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await postJson(dashboardPaths.points(customerId), { points: Number(points), reason: reason.trim() })
      setPoints('')
      setReason('')
      onDone()
    } catch (caught) {
      setError(message(caught))
    }
  }

  return (
    <form className={form} aria-label="Adjust points" onSubmit={(event) => void adjust(event)}>
      <input className={`${field} w-28`} aria-label="Points to add or take away" type="number" step="1" placeholder="+5 or −2" value={points}
        onChange={(event) => setPoints(event.target.value)} />
      <input className={`${field} min-w-56 flex-1`} aria-label="Reason for the adjustment" placeholder="Reason (e.g. points missed on R12)" maxLength={200}
        value={reason} onChange={(event) => setReason(event.target.value)} />
      <button type="submit" className="btn" disabled={!points || Number(points) === 0 || reason.trim() === ''}>
        Adjust
      </button>
      {error && <p className="basis-full text-sm font-medium text-red-600" role="alert">{error}</p>}
    </form>
  )
}

function OweItem({ customerId, onDone }: { customerId: number; onDone: () => void }) {
  const promotions = useApi<OwablePromotion[]>(dashboardPaths.promotions)
  const [promotionId, setPromotionId] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function owe(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await postJson(dashboardPaths.owedItems(customerId), { promotionId: Number(promotionId), quantity: Number(quantity), reason: reason.trim() })
      setReason('')
      onDone()
    } catch (caught) {
      setError(message(caught))
    }
  }

  return (
    <form className={form} aria-label="Owe a free item" onSubmit={(event) => void owe(event)}>
      <select className={`${field} min-w-56 flex-1`} aria-label="Promotion" value={promotionId} onChange={(event) => setPromotionId(event.target.value)}>
        <option value="">Free item from…</option>
        {promotions.status === 'success' && promotions.data.map((promotion) => (
          <option key={promotion.id} value={promotion.id}>
            {promotion.name} ({promotion.items.slice(0, 2).join(', ')}{promotion.items.length > 2 ? '…' : ''})
          </option>
        ))}
      </select>
      <input className={`${field} w-24`} aria-label="How many" type="number" min="1" max="10" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
      <input className={`${field} min-w-56 flex-1`} aria-label="Reason for owing" placeholder="Reason (e.g. forgot the birthday can on R40)" maxLength={200}
        value={reason} onChange={(event) => setReason(event.target.value)} />
      <button type="submit" className="btn" disabled={!promotionId || reason.trim() === ''}>
        Owe item
      </button>
      {error && <p className="basis-full text-sm font-medium text-red-600" role="alert">{error}</p>}
    </form>
  )
}
