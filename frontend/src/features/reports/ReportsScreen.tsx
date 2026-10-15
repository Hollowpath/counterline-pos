import { useState } from 'react'
import { useApi } from '../../api/useApi'
import { AppBar } from '../../app/AppBar'
import { LoadState } from '../../components/LoadState'
import { addDays, daysBetween, formatDate, formatDateTime, formatTime, todayIso } from '../../lib/dates'
import { formatRM, formatSignedRM, toSen } from '../../lib/money'
import { printScreen } from '../receipts/printReceipt'
import { SalesSummaryView } from './SalesSummaryView'
import {
  reportPath,
  type CategorySales,
  type DiscountsReport,
  type HourlySales,
  type ItemSales,
  type ItemTotal,
  type LoyaltyPointsReport,
  type ModifiersReport,
  type PaymentMethodsReport,
  type ReportName,
  type SalesSummary,
  type Timecards,
} from './types'
import { appBarTitle, detail, rowList, splitScreen } from '../../styles/ui'

/** The reports, in menu order. */
const REPORTS: { name: ReportName; label: string }[] = [
  { name: 'sales-summary', label: 'Sales Summary' },
  { name: 'hourly-sales', label: 'Hourly Sales' },
  { name: 'payment-methods', label: 'Payment Methods' },
  { name: 'item-sales', label: 'Item Sales' },
  { name: 'category-sales', label: 'Category Sales' },
  { name: 'discounts', label: 'Discounts' },
  { name: 'modifiers', label: 'Modifiers' },
  { name: 'loyalty-points', label: 'Loyalty Points' },
  { name: 'timecards', label: 'Timecards' },
]

const rm = (amount: number) => formatRM(toSen(amount))

interface ReportsScreenProps {
  /** Whose reports these are (printed at the top): the terminal's outlet, or the one chosen on the dashboard. */
  outletName: string
  /** The POS's bottom bar; the dashboard has its own navigation. */
  onOpenMenu?: () => void
  /** Where a report comes from (default: the terminal's outlet). */
  pathFor?: (name: ReportName, from: string, to: string) => string
}

export function ReportsScreen({ outletName, onOpenMenu, pathFor = reportPath }: ReportsScreenProps) {
  const [report, setReport] = useState<ReportName>('sales-summary')
  const [from, setFrom] = useState(todayIso)
  const [to, setTo] = useState(todayIso)
  const label = REPORTS.find((entry) => entry.name === report)!.label

  /** ◀ / ▶ move the whole range by its own length, e.g. one day at a time. */
  function shift(direction: -1 | 1) {
    const span = daysBetween(from, to)
    setFrom(addDays(from, direction * span))
    setTo(addDays(to, direction * span))
  }

  const period = from === to ? formatDate(`${from}T00:00:00`) : `${formatDate(`${from}T00:00:00`)} – ${formatDate(`${to}T00:00:00`)}`

  return (
    <div className={splitScreen.screen}>
      <nav className={splitScreen.list} aria-label="Reports">
        <ul className={rowList}>
          {REPORTS.map((entry) => (
            <li key={entry.name}>
              <button
                type="button"
                className="mb-1 flex w-full items-center justify-between rounded-xl px-3 py-3 text-left text-sm font-medium text-zinc-600 transition after:text-zinc-300 after:content-['›'] hover:bg-zinc-50 hover:text-zinc-900 aria-[current=page]:bg-zinc-200/70 aria-[current=page]:font-semibold aria-[current=page]:text-zinc-900 aria-[current=page]:after:text-zinc-500"
                aria-current={entry.name === report ? 'page' : undefined}
                onClick={() => setReport(entry.name)}
              >
                {entry.label}
              </button>
            </li>
          ))}
        </ul>
      </nav>

      <section className={splitScreen.detail} aria-label={label}>
        <div className="flex flex-wrap items-center gap-2 rounded-2xl bg-linear-to-r from-indigo-600 to-violet-600 p-2 text-white shadow-lg shadow-indigo-600/25 print:hidden [&_input]:rounded-lg [&_input]:bg-white/15 [&_input]:px-2.5 [&_input]:py-2 [&_input]:text-sm [&_input]:font-medium [&_input]:text-white [&_input]:[color-scheme:dark] [&_input]:focus:ring-2 [&_input]:focus:ring-white/60 [&_input]:focus:outline-none">
          <button type="button" className="grid size-9 place-items-center rounded-lg bg-white/15 text-lg text-white transition hover:bg-white/25" aria-label="Previous period" onClick={() => shift(-1)}>
            ‹
          </button>
          <label>
            <span className="sr-only">From</span>
            <input
              type="date"
              value={from}
              max={to}
              onChange={(event) => event.target.value && setFrom(event.target.value)}
            />
          </label>
          <span aria-hidden="true">–</span>
          <label>
            <span className="sr-only">To</span>
            <input type="date" value={to} min={from} onChange={(event) => event.target.value && setTo(event.target.value)} />
          </label>
          <button type="button" className="grid size-9 place-items-center rounded-lg bg-white/15 text-lg text-white transition hover:bg-white/25" aria-label="Next period" onClick={() => shift(1)}>
            ›
          </button>
          <button type="button" className="btn ml-auto py-2 text-indigo-700" onClick={printScreen}>
            Print
          </button>
        </div>

        {/* Printed as an end-of-day slip: outlet, "Report", the period, then the report. */}
        <div>
          <header className="hidden print:flex print:flex-col print:items-center print:gap-[0.1rem] print:border-x-0 print:border-t-0 print:border-b print:border-dashed print:border-black print:pb-2">
            <strong>{outletName}</strong>
            <span>Report</span>
            <span>{period}</span>
          </header>
          <h2 className="mt-6 mb-3 text-2xl font-bold tracking-tight text-zinc-900 print:my-[0.4rem] print:border-x-0 print:border-t-0 print:border-b print:border-dashed print:border-black print:pb-[0.3rem] print:text-[12px]">{label}</h2>
          <Report key={report} name={report} path={pathFor(report, from, to)} />
        </div>
      </section>

      {onOpenMenu && (
        <AppBar label="Reports" onOpenMenu={onOpenMenu}>
          <p className={appBarTitle}>Reports</p>
        </AppBar>
      )}
    </div>
  )
}

function Report({ name, path }: { name: ReportName; path: string }) {
  const result = useApi<unknown>(path)
  if (result.status !== 'success') {
    return <LoadState status={result.status} message={result.status === 'error' ? result.message : undefined} onRetry={result.reload} />
  }
  return (
    <>
      <ReportBody name={name} data={result.data} />
      <p className="mt-6 text-center text-xs text-zinc-400 print:text-[10px] print:text-black">Generated on {formatDateTime(new Date().toISOString())}</p>
    </>
  )
}

function ReportBody({ name, data }: { name: ReportName; data: unknown }) {
  switch (name) {
    case 'sales-summary':
      return <SalesSummaryView summary={data as SalesSummary} />
    case 'hourly-sales': {
      const { hours } = data as HourlySales
      // Refunds are deducted in the hour they were made, so the total matches the Sales Summary.
      const totalSen = hours.reduce((sum, hour) => sum + toSen(hour.net), 0)
      const totalOrders = hours.reduce((sum, hour) => sum + hour.orderCount, 0)
      return hours.length === 0 ? <NoSales /> : (
        <ul className={detail.rows}>
          {hours.map((hour) => (
            <li key={hour.hour}>
              <span>
                {String(hour.hour).padStart(2, '0')}:00 – {String(hour.hour).padStart(2, '0')}:59
                <span className={detail.sub}>
                  {hour.orderCount} orders
                  {hour.refunds > 0 && ` · refund (${rm(hour.refunds)})`}
                </span>
              </span>
              <span>{formatSignedRM(toSen(hour.net))}</span>
            </li>
          ))}
          <li className={detail.total} data-testid="hourly-total">
            <span>
              Total Net Sales
              <span className={detail.sub}>{totalOrders} orders</span>
            </span>
            <span>{formatSignedRM(totalSen)}</span>
          </li>
        </ul>
      )
    }
    case 'payment-methods': {
      const { methods, total } = data as PaymentMethodsReport
      return methods.length === 0 ? <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No payments in this period.</p> : (
        <ul className={detail.rows}>
          {methods.map((method) => (
            <li key={method.method}>
              <span>
                {method.method}
                <span className={detail.sub}>
                  {method.paymentCount} {method.paymentCount === 1 ? 'Payment' : 'Payments'}
                  {method.refunded > 0 && ` · ${rm(method.refunded)} refunded`}
                </span>
              </span>
              <span>{rm(method.net)}</span>
            </li>
          ))}
          <li className={detail.total}>
            <span>Total</span>
            <span>{rm(total)}</span>
          </li>
        </ul>
      )
    }
    case 'item-sales': {
      const { items } = data as ItemSales
      return items.length === 0 ? <NoSales /> : <ItemRows items={items} />
    }
    case 'category-sales': {
      const { categories } = data as CategorySales
      return categories.length === 0 ? <NoSales /> : (
        <>
          {categories.map((category) => {
            const name = category.categoryName ?? 'Custom Amount'
            return (
              <section key={name} aria-label={name}>
                <h3 className={`${detail.section} flex justify-between`}>
                  <span>
                    {category.quantity} {name}
                  </span>
                  <span>{rm(category.amount)}</span>
                </h3>
                <ul className={detail.rows}>
                  {category.items.map((item) => (
                    <li key={item.itemName} className="flex-col">
                      <span className="flex justify-between gap-4">
                        <span>
                          {item.quantity} {item.itemName}
                        </span>
                        <span>{rm(item.amount)}</span>
                      </span>
                      {item.prices.map((price) => (
                        <span key={`${price.label}/${price.unitPrice}`} className="flex justify-between gap-4 pl-4 text-xs text-zinc-500 print:text-[10px] print:text-black">
                          <span>
                            {price.quantity} {price.label}
                            {item.prices.length > 1 && ` @ ${rm(price.unitPrice)}`}
                          </span>
                          <span>{rm(price.amount)}</span>
                        </span>
                      ))}
                    </li>
                  ))}
                </ul>
              </section>
            )
          })}
        </>
      )
    }
    case 'discounts': {
      const { discounts } = data as DiscountsReport
      return discounts.length === 0 ? <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No discounts in this period.</p> : (
        <ul className={detail.rows}>
          {discounts.map((discount) => (
            <li key={discount.name}>
              <span>
                {discount.name}
                <span className={detail.sub}>Applied {discount.count} times</span>
              </span>
              <span>{rm(discount.amount)}</span>
            </li>
          ))}
        </ul>
      )
    }
    case 'modifiers': {
      const { options } = data as ModifiersReport
      if (options.length === 0) return <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No options chosen in this period.</p>
      // As printed: each option group with how many were picked, then its options.
      const groups = new Map<string, typeof options>()
      for (const option of options) groups.set(option.groupName, [...(groups.get(option.groupName) ?? []), option])
      return (
        <>
          {[...groups].map(([groupName, groupOptions]) => (
            <section key={groupName} aria-label={groupName}>
              <h3 className={`${detail.section} flex justify-between`}>
                <span>
                  {groupOptions.reduce((sum, option) => sum + option.quantity, 0)} {groupName}
                </span>
                <span>{rm(groupOptions.reduce((sum, option) => sum + option.amount, 0))}</span>
              </h3>
              <ul className={detail.rows}>
                {groupOptions.map((option) => (
                  <li key={option.optionName}>
                    <span>
                      {option.quantity} {option.optionName}
                    </span>
                    <span>{rm(option.amount)}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </>
      )
    }
    case 'loyalty-points': {
      const points = data as LoyaltyPointsReport
      return (
        <ul className={detail.rows}>
          <li>
            <span>Issued Points</span>
            <span>{points.issued}</span>
          </li>
          <li>
            <span>Redeemed Points</span>
            <span>({points.redeemed})</span>
          </li>
          {points.redemptions.map((redemption) => (
            <li key={redemption.name}>
              <span className={detail.indent}>
                {redemption.name}
                <span className={detail.sub}>
                  {redemption.count} {redemption.count === 1 ? 'item' : 'items'}
                </span>
              </span>
              <span>({redemption.points})</span>
            </li>
          ))}
          {points.issuedReversed > 0 && (
            <li>
              <span>Issued Points Reversed (refunds)</span>
              <span>({points.issuedReversed})</span>
            </li>
          )}
          {points.redeemedReturned > 0 && (
            <li>
              <span>Redeemed Points Returned (refunds)</span>
              <span>{points.redeemedReturned}</span>
            </li>
          )}
        </ul>
      )
    }
    case 'timecards': {
      const { entries } = data as Timecards
      return entries.length === 0 ? <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No clock-ins in this period.</p> : (
        <ul className={detail.rows}>
          {entries.map((entry, index) => (
            <li key={index}>
              <span>{entry.staffName}</span>
              <span className={`font-semibold ${entry.type === 'IN' ? 'text-green-600' : 'text-red-600'}`}>
                {entry.type} · {formatTime(entry.at)}
              </span>
            </li>
          ))}
        </ul>
      )
    }
  }
}

function ItemRows({ items }: { items: ItemTotal[] }) {
  return (
    <ul className={detail.rows}>
      {items.map((item) => (
        <li key={item.itemName}>
          <span>
            {item.quantity} {item.itemName}
          </span>
          <span>{rm(item.amount)}</span>
        </li>
      ))}
    </ul>
  )
}

function NoSales() {
  return <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No sales in this period.</p>
}
