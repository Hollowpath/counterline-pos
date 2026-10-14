import { useState } from 'react'
import { useApi } from '../../api/useApi'
import type { Staff } from '../../auth/types'
import { LoadState } from '../../components/LoadState'
import { formatDateTime } from '../../lib/dates'
import { formatRM, toSen } from '../../lib/money'
import { maskEmail, maskPhone } from '../../lib/privacy'
import type { Order } from '../checkout/types'
import { ManagerActionDialog } from './ManagerActionDialog'
import { ordersPaths, refundOrder, voidOrder } from './ordersApi'
import { detail } from '../../styles/ui'

type Action = 'void' | 'refund' | null

interface OrderDetailProps {
  orderId: number
  staff: Staff
  /** The order changed (voided, refunded): the list should refresh. */
  onChanged: () => void
  /** An unpaid order was voided: the Point of Sale lets go of it. */
  onVoided?: (orderId: number) => void
  /** "Edit Order": an unpaid order goes back into the cart at the Point of Sale, to change or pay. */
  onEdit: (orderId: number) => void
}

/** Paid / Remaining. */
const tileStat = 'flex flex-col items-start gap-1 rounded-2xl p-4 text-xs font-semibold tracking-wider uppercase [&_strong]:text-[1.6rem] [&_strong]:tracking-tight [&_strong]:normal-case [&_strong]:tabular-nums'
/** The order's status across the detail ("Awaiting Payment"). */
const banner = 'my-5 flex items-center justify-center gap-2 rounded-xl px-3 py-2 text-center text-sm font-semibold ring-1 ring-inset'

export function OrderDetail({ orderId, staff, onChanged, onVoided, onEdit }: OrderDetailProps) {
  const result = useApi<Order>(ordersPaths.order(orderId))
  const [action, setAction] = useState<Action>(null)

  if (result.status !== 'success') {
    return <LoadState status={result.status} message={result.status === 'error' ? result.message : undefined} onRetry={result.reload} />
  }

  const order = result.data
  const totalSen = toSen(order.total)
  const paidSen = toSen(order.amountPaid)
  const remainingSen = order.status === 'OPEN' ? totalSen - paidSen : 0
  const needsApproval = staff.role !== 'MANAGER'

  function finish() {
    setAction(null)
    result.reload()
    onChanged()
  }

  return (
    <article aria-label={`Order ${order.orderNumber}`}>
      <header className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-2xl font-bold tracking-tight text-zinc-900">
          {order.receiptCode} – {formatRM(totalSen)}
        </h2>
        <div className="flex gap-2">
          {order.status === 'OPEN' && (
            <>
              <button type="button" className="btn btn-primary" onClick={() => onEdit(order.id)}>
                Edit Order
              </button>
              <button type="button" className="btn btn-danger" onClick={() => setAction('void')}>
                Void Order
              </button>
            </>
          )}
          {order.status === 'CLOSED' && !order.refund && paidSen > 0 && (
            <button type="button" className="btn btn-danger" onClick={() => setAction('refund')}>
              Issue Refund
            </button>
          )}
        </div>
      </header>

      <StatusBanner order={order} />

      <div className="grid grid-cols-2 gap-3">
        <div className={`${tileStat} text-white shadow-lg ${order.status === 'VOIDED' || order.refund
          ? 'bg-linear-to-br from-red-500 to-red-700 shadow-red-600/20' : 'bg-linear-to-br from-green-500 to-green-700 shadow-green-600/20'}`}>
          <span>{order.refund ? 'Refunded' : 'Paid'}</span>
          <strong>{formatRM(order.refund ? toSen(order.refund.amount) : paidSen)}</strong>
        </div>
        <div className={`${tileStat} bg-white text-zinc-500 shadow-sm ring-1 ring-zinc-900/5 [&_strong]:text-zinc-900`}>
          <span>Remaining</span>
          <strong>{formatRM(remainingSen)}</strong>
        </div>
      </div>

      <dl className={detail.info}>
        <div>
          <dt>Order</dt>
          <dd>
            {order.orderNumber} · {order.receiptCode}
          </dd>
        </div>
        <div>
          <dt>Created</dt>
          <dd>{formatDateTime(order.createdAt)}</dd>
        </div>
        <div>
          <dt>Staff</dt>
          <dd>{order.staffName}</dd>
        </div>
        {order.note && (
          <div>
            <dt>Note</dt>
            <dd>{order.note}</dd>
          </div>
        )}
        {order.voided && (
          <div>
            <dt>Void reason</dt>
            <dd>
              {order.voided.reason} ({order.voided.voidedBy}, {formatDateTime(order.voided.voidedAt)})
            </dd>
          </div>
        )}
        {order.refund && (
          <div>
            <dt>Refund reason</dt>
            <dd>
              {order.refund.reason} ({order.refund.refundedBy}, {formatDateTime(order.refund.refundedAt)})
            </dd>
          </div>
        )}
      </dl>

      <h3 className={detail.section}>Order Items</h3>
      <ul className={detail.rows}>
        {order.lines.map((line) => (
          <li key={line.lineNumber}>
            <span>
              <span className="font-medium text-zinc-900">{line.itemName}</span> <span className="text-zinc-400">×{line.quantity}</span>
              <span className={detail.sub}>
                Unit price {formatRM(toSen(line.unitPrice))}
                {line.modifierUnitPrice > 0 && ` + ${formatRM(toSen(line.modifierUnitPrice))} options`}
              </span>
              {line.modifiers.length > 0 && (
                <span className={detail.sub}>
                  {line.modifiers.map((modifier) => `${modifier.quantity} ${modifier.optionName}`).join(', ')}
                </span>
              )}
              {line.note && <span className={detail.sub}>“{line.note}”</span>}
              {line.discounts.map((discount) => (
                <span key={discount.name} className={`${detail.sub} ${detail.discount}`}>
                  {discount.name} −{formatRM(toSen(discount.amount))}
                  {discount.detail && ` · ${discount.detail}`}
                </span>
              ))}
            </span>
            <span>{formatRM(toSen(line.lineTotal))}</span>
          </li>
        ))}
        {order.discountTotal > 0 && (
          <li>
            <span>Subtotal</span>
            <span>{formatRM(toSen(order.subtotal))}</span>
          </li>
        )}
        {order.orderDiscounts.map((discount) => (
          <li key={discount.name} className={detail.discount}>
            <span>
              {discount.name}
              {discount.detail && <span className={detail.sub}>{discount.detail}</span>}
            </span>
            <span>−{formatRM(toSen(discount.amount))}</span>
          </li>
        ))}
        <li className={detail.total}>
          <span>Total</span>
          <span>{formatRM(totalSen)}</span>
        </li>
      </ul>

      {order.customer && (
        <>
          <h3 className={detail.section}>Customer Information</h3>
          <dl className={detail.info}>
            <div>
              <dt>Name</dt>
              <dd>{order.customer.name}</dd>
            </div>
            {order.customer.phone && (
              <div>
                <dt>Phone</dt>
                <dd>{maskPhone(order.customer.phone)}</dd>
              </div>
            )}
            {order.customer.email && (
              <div>
                <dt>Email</dt>
                <dd>{maskEmail(order.customer.email)}</dd>
              </div>
            )}
          </dl>
        </>
      )}

      {order.loyalty && (
        <>
          <h3 className={detail.section}>Loyalty Program</h3>
          <ul className={detail.rows} aria-label="Loyalty points">
            <li>
              <span>Previous points</span>
              <span>{order.loyalty.before}</span>
            </li>
            {order.loyalty.redeemed > 0 && (
              <li>
                <span>Points redeemed</span>
                <span>−{order.loyalty.redeemed}</span>
              </li>
            )}
            <li>
              <span>Points earned</span>
              <span>{order.loyalty.earned}</span>
            </li>
            <li className={detail.total}>
              <span>Current points</span>
              <span>{order.loyalty.after}</span>
            </li>
          </ul>
        </>
      )}

      {order.payments.length > 0 && (
        <>
          <h3 className={detail.section}>Payment</h3>
          <ul className={detail.rows}>
            {order.payments.map((payment) => (
              <li key={payment.id}>
                <span>
                  {payment.method}
                  <span className={detail.sub}>
                    {formatDateTime(payment.createdAt)}
                    {payment.reference && ` · Ref ${payment.reference}`}
                    {payment.change !== null && payment.change > 0 &&
                      ` · Received ${formatRM(toSen(payment.cashTendered!))}, change ${formatRM(toSen(payment.change))}`}
                  </span>
                </span>
                <span>{formatRM(toSen(payment.amount))}</span>
              </li>
            ))}
          </ul>
        </>
      )}

      {action === 'void' && (
        <ManagerActionDialog
          title={`Void ${order.orderNumber}`}
          confirmLabel="Void Order"
          description={<p>The unpaid order ({formatRM(totalSen)}) will be cancelled.</p>}
          needsApproval={needsApproval}
          onConfirm={async (reason, token) => {
            await voidOrder(order.id, reason, token)
            onVoided?.(order.id)
            finish()
          }}
          onCancel={() => setAction(null)}
        />
      )}
      {action === 'refund' && (
        <ManagerActionDialog
          title={`Refund ${order.orderNumber}`}
          confirmLabel="Issue Refund"
          description={
            <p>
              {formatRM(paidSen)} will be refunded to the original payment
              {order.payments.length > 1 ? ' methods' : ' method'}: {order.payments.map((p) => p.method).join(', ')}.
            </p>
          }
          needsApproval={needsApproval}
          onConfirm={async (reason, token) => {
            await refundOrder(order.id, reason, token)
            finish()
          }}
          onCancel={() => setAction(null)}
        />
      )}
    </article>
  )
}

function StatusBanner({ order }: { order: Order }) {
  if (order.status === 'VOIDED') return <p className={`${banner} bg-red-50 text-red-700 ring-red-200`}>Order Cancelled</p>
  if (order.refund) return <p className={`${banner} bg-red-50 text-red-700 ring-red-200`}>Order Refunded</p>
  if (order.status === 'CLOSED') return <p className={`${banner} bg-zinc-100 text-zinc-600 ring-zinc-200`}>Order Closed</p>
  return <p className={`${banner} bg-orange-50 text-orange-800 ring-orange-200`}>Awaiting Payment</p>
}
