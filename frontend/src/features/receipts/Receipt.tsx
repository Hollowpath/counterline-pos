import type { ReactNode } from 'react'
import type { Outlet } from '../../auth/types'
import { formatDateTime } from '../../lib/dates'
import { formatRM, toSen } from '../../lib/money'
import { maskPhone } from '../../lib/privacy'
import type { Order } from '../checkout/types'
import { receiptStatus } from './receiptStatus'

const rm = (amount: number) => formatRM(toSen(amount))
const negative = (amount: number) => `-${rm(amount)}`

/** A label and an amount on one line of the slip. */
function Row({ label, value, strong = false, indent = false }: {
  label: ReactNode
  value?: ReactNode
  strong?: boolean
  indent?: boolean
}) {
  return (
    <div className={`flex justify-between gap-3 ${strong ? 'font-bold' : ''} ${indent ? 'pl-3' : ''}`}>
      <span className="min-w-0">{label}</span>
      {value !== undefined && <span className="shrink-0 tabular-nums">{value}</span>}
    </div>
  )
}

const rule = 'my-1.5 border-t border-dashed border-black'

/**
 * The customer receipt, laid out for an 80 mm thermal slip: the outlet, the order, its lines with
 * their promotions and discounts, the totals and payments, the payment status, the member and their
 * points. An unpaid, voided or refunded order says so in large type at the top, so it can't be
 * mistaken for a paid receipt.
 */
export function Receipt({ order, outlet }: { order: Order; outlet: Pick<Outlet, 'name' | 'address'> | null }) {
  const status = receiptStatus(order)
  const loyalty = order.loyalty
  return (
    <article className="mx-auto w-full max-w-[80mm] bg-white font-mono text-[11px] leading-snug text-black" aria-label={`Receipt ${order.receiptCode}`}>
      <header className="text-center">
        {outlet && <p className="text-[13px] font-bold">{outlet.name}</p>}
        {outlet?.address && <p>{outlet.address}</p>}
      </header>

      {status !== 'Paid' && (
        <p className="my-2 border-2 border-black py-1 text-center text-[15px] font-bold tracking-widest" data-testid="receipt-status-banner">
          *** {status.toUpperCase()} ***
        </p>
      )}

      <div className={rule} />
      <Row label={`Order ${order.orderNumber}`} value={order.receiptCode} />
      <Row label={formatDateTime(order.createdAt)} value={order.staffName} />
      <Row label="Retail" value="Pax: 1" />
      <div className={rule} />

      {order.lines.map((line) => (
        <div key={line.lineNumber} className="mb-0.5">
          <Row label={`${line.quantity}x ${line.itemName}`} value={rm(line.lineSubtotal)} />
          {line.modifiers.map((modifier) => (
            <Row key={`${modifier.groupName}/${modifier.optionName}`} indent label={`${modifier.quantity} ${modifier.optionName}`} />
          ))}
          {line.note && <Row indent label={`"${line.note}"`} />}
          {line.discounts.map((discount) => (
            <Row key={discount.name} indent label={discount.promotion ? discount.name.toUpperCase() : discount.name} value={negative(discount.amount)} />
          ))}
        </div>
      ))}

      <div className={rule} />
      <Row label="Subtotal" value={rm(order.subtotal)} />
      {order.orderDiscounts.map((discount) => (
        <Row key={discount.name} label={discount.name} value={negative(discount.amount)} />
      ))}
      <div className="mt-1 flex justify-between text-[15px] font-bold">
        <span>Total</span>
        <span className="tabular-nums">{rm(order.total)}</span>
      </div>

      {order.payments.length > 0 && (
        <>
          <div className={rule} />
          {order.payments.map((payment) => (
            <div key={payment.id} className="mb-0.5">
              <Row label={payment.method} value={rm(payment.cashTendered ?? payment.amount)} />
              {payment.reference && <Row indent label={`REFERENCE: ${payment.reference}`} />}
              {payment.change != null && payment.change > 0 && <Row indent label="Change" value={rm(payment.change)} />}
            </div>
          ))}
        </>
      )}

      <div className={rule} />
      <Row label="Payment Status" value={status} strong />
      {order.voided && <Row label="Void reason" value={order.voided.reason} />}
      {order.refund && (
        <>
          <Row label="Refunded" value={negative(order.refund.amount)} />
          <Row label="Refund reason" value={order.refund.reason} />
        </>
      )}

      {order.customer && (
        <>
          <div className={rule} />
          <p className="font-bold">Customer Info</p>
          <Row label={`Name: ${order.customer.name}`} />
          {order.customer.phone && <Row label={`Phone: ${maskPhone(order.customer.phone)}`} />}
        </>
      )}

      {loyalty && (
        <>
          <div className={rule} />
          <p className="font-bold">Loyalty Program</p>
          <Row label="Previous points" value={loyalty.before} />
          {loyalty.redeemed > 0 && <Row label="Points redeemed" value={-loyalty.redeemed} />}
          <Row label="Points earned" value={loyalty.earned} />
          <Row label="Current points" value={loyalty.after} />
        </>
      )}

      <div className={rule} />
      <p className="text-center">Thank you for the purchase!</p>
      <p className="text-center">Please come again!</p>
    </article>
  )
}
