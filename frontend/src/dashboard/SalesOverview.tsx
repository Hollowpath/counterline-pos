import { useEffect, useState } from 'react'
import { ApiError, getJson } from '../api/http'
import { useApi } from '../api/useApi'
import type { Outlet } from '../auth/types'
import { addDays, todayIso } from '../lib/dates'
import { formatRM, toSen } from '../lib/money'
import { BarList, ColumnChart, Donut, TrendChart } from './charts'
import { field } from './styles'
import { dashboardPaths, type SalesFigures, type SalesOverview as Overview } from './types'
import { Card, Page, Segmented, Tile, Warning } from './ui'

/** How often the figures refresh while a period that includes today is shown. */
const REFRESH_MS = 30_000

const rm = (amount: number) => formatRM(toSen(amount))

type Period = 'today' | '7d' | '30d' | 'month'

const PERIODS: { period: Period; label: string; previous: string }[] = [
  { period: 'today', label: 'Today', previous: 'yesterday' },
  { period: '7d', label: '7 days', previous: 'the 7 days before' },
  { period: '30d', label: '30 days', previous: 'the 30 days before' },
  { period: 'month', label: 'This month', previous: 'the days before' },
]

function range(period: Period, today: string): { from: string; to: string } {
  switch (period) {
    case 'today':
      return { from: today, to: today }
    case '7d':
      return { from: addDays(today, -6), to: today }
    case '30d':
      return { from: addDays(today, -29), to: today }
    case 'month':
      return { from: `${today.slice(0, 8)}01`, to: today }
  }
}

/** "12 Oct" from "2026-10-12". */
function shortDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString('en-MY', { day: 'numeric', month: 'short' })
}

const orders = (count: number) => `${count} ${count === 1 ? 'order' : 'orders'}`

/** The kiosks' usual hours (8am–10pm), widened to any hour with sales. */
function shownHours(hours: Overview['hours']): Overview['hours'] {
  const busy = hours.filter((hour) => hour.orders > 0).map((hour) => hour.hour)
  const first = Math.min(8, ...busy)
  const last = Math.max(22, ...busy)
  return hours.filter((hour) => hour.hour >= first && hour.hour <= last)
}

/** "9am", "12pm", "3pm". */
function hourLabel(hour: number): string {
  return `${hour % 12 === 0 ? 12 : hour % 12}${hour < 12 ? 'am' : 'pm'}`
}

/**
 * The dashboard's home: how sales are going, in charts. Head office sees every outlet (or picks
 * one); a manager sees their own. Headline figures compare with the period before.
 */
export function SalesOverview() {
  const outlets = useApi<Outlet[]>(dashboardPaths.outlets)
  const [period, setPeriod] = useState<Period>('7d')
  const [outletId, setOutletId] = useState<number | null>(null)
  const [overview, setOverview] = useState<Overview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { from, to } = range(period, todayIso())
  const path = dashboardPaths.overview(from, to, outletId)

  useEffect(() => {
    let stopped = false
    let timer: number | undefined
    async function load() {
      try {
        const next = await getJson<Overview>(path)
        if (stopped) return
        setOverview(next)
        setError(null)
      } catch (caught) {
        if (!stopped) setError(caught instanceof ApiError ? caught.message : 'Offline')
      }
      if (!stopped) timer = window.setTimeout(load, REFRESH_MS)
    }
    void load()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [path])

  const outletList = outlets.status === 'success' ? outlets.data : []
  const previous = PERIODS.find((entry) => entry.period === period)!.previous
  // While a new period loads, the last one's figures stay up (dimmed).
  const stale = overview !== null && (overview.from !== from || overview.to !== to || overview.outletId !== outletId)

  return (
    <Page
      label="Sales overview"
      title="Overview"
      description={
        <>
          {from === to ? shortDate(from) : `${shortDate(from)} – ${shortDate(to)}`}
          {' · '}
          {outletId === null ? (outletList.length === 1 ? outletList[0].name : 'All outlets') : outletList.find((o) => o.id === outletId)?.name}
        </>
      }
      actions={
        <>
          <Segmented
            label="Period"
            value={period}
            onChange={setPeriod}
            options={PERIODS.map((entry) => ({ value: entry.period, label: entry.label }))}
          />
          {outletList.length > 1 && (
            <select aria-label="Outlet" className={`${field} max-md:w-full`} value={outletId ?? ''}
              onChange={(event) => setOutletId(event.target.value === '' ? null : Number(event.target.value))}>
              <option value="">All outlets</option>
              {outletList.map((outlet) => (
                <option key={outlet.id} value={outlet.id}>{outlet.name}</option>
              ))}
            </select>
          )}
        </>
      }
    >
      {error && <Warning>{error}</Warning>}
      {overview === null ? (
        !error && <p className="text-sm text-zinc-500">Loading…</p>
      ) : (
        <div className={`flex flex-col gap-4 transition-opacity ${stale ? 'opacity-55' : ''}`}>
          <Figures current={overview.current} previous={overview.previous} previousLabel={previous} />

          <div className="grid grid-cols-2 gap-4 max-lg:grid-cols-1">
            {overview.days.length > 1 && (
              <Card title="Net sales by day" className="col-span-full">
                <TrendChart
                  label="Net sales by day"
                  format={rm}
                  points={overview.days.map((day) => ({
                    label: shortDate(day.date),
                    value: day.netSales,
                    title: `${rm(day.netSales)} · ${orders(day.orders)}`,
                  }))}
                />
              </Card>
            )}
            <Card title="Busiest hours" className={overview.days.length === 1 ? 'col-span-full' : ''}>
              <ColumnChart
                label="Sales by hour"
                labelEvery={3}
                points={shownHours(overview.hours).map((hour) => ({
                  label: hourLabel(hour.hour),
                  value: hour.sales,
                  title: `${rm(hour.sales)} · ${orders(hour.orders)}`,
                }))}
              />
            </Card>
            {overview.outlets.length > 1 && (
              <Card title="Outlets">
                <BarList
                  label="Net sales by outlet"
                  rows={overview.outlets.map((outlet) => ({
                    label: outlet.name, value: outlet.netSales, shown: rm(outlet.netSales), sub: orders(outlet.orders),
                  }))}
                />
              </Card>
            )}
            <Card title="Top items">
              <BarList
                label="Top items by units sold"
                rows={overview.topItems.map((item) => ({
                  label: item.name, value: item.quantity, shown: `${item.quantity} sold`, sub: rm(item.sales),
                }))}
              />
            </Card>
            <Card title="Sales by folder">
              <BarList
                label="Sales by folder"
                rows={overview.categories.map((category) => ({ label: category.name, value: category.amount, shown: rm(category.amount) }))}
              />
            </Card>
            <Card title="Payment methods">
              <Donut
                label="Collected by payment method"
                centre={rm(overview.paymentMethods.reduce((sum, method) => sum + method.amount, 0))}
                rows={overview.paymentMethods.map((method) => ({ label: method.name, value: method.amount, shown: rm(method.amount) }))}
              />
            </Card>
          </div>
        </div>
      )}
    </Page>
  )
}

/** The headline figures on till tiles, each with how it compares with the period before. */
function Figures({ current, previous, previousLabel }: { current: SalesFigures; previous: SalesFigures; previousLabel: string }) {
  const memberShare = current.orders === 0 ? 0 : Math.round((current.memberOrders / current.orders) * 100)
  return (
    <dl className="grid grid-cols-6 gap-4 max-xl:grid-cols-3 max-md:grid-cols-2 max-md:gap-3">
      <Figure label="Net sales" value={rm(current.netSales)} now={current.netSales} before={previous.netSales} previousLabel={previousLabel} />
      <Figure label="Orders" value={String(current.orders)} now={current.orders} before={previous.orders} previousLabel={previousLabel} />
      <Figure label="Average order" value={rm(current.averageOrder)} now={current.averageOrder} before={previous.averageOrder}
        previousLabel={previousLabel} />
      <Figure label="Items sold" value={String(current.itemsSold)} now={current.itemsSold} before={previous.itemsSold}
        previousLabel={previousLabel} />
      <Tile label="Member sales" value={`${memberShare}%`}>
        <span className="text-xs text-zinc-500">{current.memberOrders} of {orders(current.orders)}</span>
      </Tile>
      <Tile label="Refunds" value={rm(current.refunds)} />
    </dl>
  )
}

function Figure({ label, value, now, before, previousLabel }: {
  label: string
  value: string
  now: number
  before: number
  previousLabel: string
}) {
  const change = before === 0 ? null : Math.round(((now - before) / before) * 100)
  const tone = change === null ? 'text-zinc-500' : change >= 0 ? 'font-semibold text-green-600' : 'font-semibold text-red-600'
  return (
    <Tile label={label} value={value}>
      <span className={`text-xs ${tone}`}>
        {change === null ? `none ${previousLabel}` : `${change >= 0 ? '▲' : '▼'} ${Math.abs(change)}% vs ${previousLabel}`}
      </span>
    </Tile>
  )
}
