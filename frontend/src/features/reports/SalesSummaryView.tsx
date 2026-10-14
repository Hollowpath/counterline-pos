import { formatRM, toSen } from '../../lib/money'
import type { SalesSummary } from './types'
import { detail } from '../../styles/ui'

const rm = (amount: number) => formatRM(toSen(amount))

/**
 * The printed Sales Summary: gross / discounts & comps / net, totals per payment method and total
 * collected, order statuses and dining options. Shared by Reports and Manage Register.
 */
export function SalesSummaryView({ summary }: { summary: SalesSummary }) {
  return (
    <>
      <ul className={detail.rows}>
        <li>
          <span>Gross Sales</span>
          <span>{rm(summary.grossSales)}</span>
        </li>
        <li>
          <span className={detail.indent}>Discounts &amp; Comps</span>
          <span>({rm(summary.discounts)})</span>
        </li>
        {summary.refunds > 0 && (
          <li>
            <span className={detail.indent}>Refunds ({summary.refundCount})</span>
            <span>({rm(summary.refunds)})</span>
          </li>
        )}
        <li className={detail.total}>
          <span>Net Sales</span>
          <span>{rm(summary.netSales)}</span>
        </li>
      </ul>

      <h3 className={detail.section}>Totals</h3>
      {summary.paymentMethods.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No payments.</p>
      ) : (
        <ul className={detail.rows} aria-label="Totals by payment method">
          {summary.paymentMethods.map((method) => (
            <li key={method.method}>
              <span className={detail.indent}>
                {method.method}
                <span className={detail.sub}>
                  {method.paymentCount} {method.paymentCount === 1 ? 'payment' : 'payments'}
                  {method.refunded > 0 && ` · ${rm(method.refunded)} refunded`}
                </span>
              </span>
              <span>{rm(method.net)}</span>
            </li>
          ))}
          <li className={detail.total}>
            <span>Total Collected</span>
            <span>{rm(summary.totalCollected)}</span>
          </li>
        </ul>
      )}

      <h3 className={detail.section}>Order Statuses</h3>
      {summary.orderStatuses.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No orders.</p>
      ) : (
        <ul className={detail.rows} aria-label="Order statuses">
          {summary.orderStatuses.map((status) => (
            <li key={status.status}>
              <span>
                {status.status}
                <span className={detail.sub}>{rm(status.amount)}</span>
              </span>
              <span>{status.count}</span>
            </li>
          ))}
          <li className={detail.total}>
            <span>Totals</span>
            <span>{rm(summary.netSales)}</span>
          </li>
        </ul>
      )}

      {summary.diningOptions.map((option) => (
        <section key={option.name} aria-label="Dining options">
          <h3 className={detail.section}>Dining Options</h3>
          <ul className={detail.rows}>
            <li>
              <span>{option.name}</span>
              <span>{option.orderCount}</span>
            </li>
            <li>
              <span className={detail.indent}>Totals</span>
              <span>{rm(option.amount)}</span>
            </li>
            <li>
              <span className={detail.indent}>Pax Count</span>
              <span>{option.paxCount}</span>
            </li>
            <li>
              <span className={detail.indent}>Per Pax Average</span>
              <span>{rm(option.perPaxAverage)}</span>
            </li>
          </ul>
        </section>
      ))}
    </>
  )
}
