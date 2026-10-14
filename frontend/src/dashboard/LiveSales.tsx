import { useEffect, useState } from 'react'
import { getJson } from '../api/http'
import { formatTime } from '../lib/dates'
import { formatRM, toSen } from '../lib/money'
import { dashboardPaths, type LiveSales as LiveSalesData } from './types'
import { Page, Warning } from './ui'

/** How often the figures refresh. */
export const LIVE_REFRESH_MS = 10_000

const rm = (amount: number) => formatRM(toSen(amount))

/** Today's sales per outlet, refreshed every few seconds: the chain at a glance. */
export function LiveSales() {
  const [live, setLive] = useState<LiveSalesData | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let stopped = false
    let timer: number | undefined
    async function refresh() {
      try {
        const next = await getJson<LiveSalesData>(dashboardPaths.live)
        if (!stopped) {
          setLive(next)
          setError(null)
        }
      } catch {
        if (!stopped) setError('Could not refresh. Showing the last figures.')
      }
      if (!stopped) timer = window.setTimeout(refresh, LIVE_REFRESH_MS)
    }
    void refresh()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [])

  if (live === null) {
    return (
      <Page title="Live Sales">
        <p className="text-sm text-zinc-500">{error ?? 'Loading…'}</p>
      </Page>
    )
  }

  return (
    <Page title="Live Sales" description={`Today · updated ${formatTime(live.generatedAt)} · refreshes by itself`}>
      {error && <Warning>{error}</Warning>}
      {live.outlets.length > 1 && (
        // The till's dark strip, as over an item's price.
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 rounded-2xl bg-linear-to-r from-zinc-800 to-zinc-900 px-6 py-4 text-white shadow-lg shadow-zinc-900/20" aria-label="All outlets">
          <span className="text-sm font-semibold tracking-wider text-zinc-400 uppercase">All outlets</span>
          <span className="flex items-baseline gap-3">
            <span className="text-sm text-zinc-400">{live.orderCount} orders</span>
            <strong className="text-[1.75rem] font-bold tracking-tight tabular-nums" data-testid="chain-net">{rm(live.netSales)}</strong>
          </span>
        </div>
      )}
      <div className="grid grid-cols-[repeat(auto-fill,minmax(20rem,1fr))] gap-4 max-md:grid-cols-1">
        {live.outlets.map((outlet) => (
          <article key={outlet.outletId} className="overflow-hidden rounded-2xl bg-white shadow-sm ring-1 ring-zinc-900/5" aria-label={outlet.name}>
            {/* The register screen's colours: green while open, red while closed. */}
            <p className={`flex items-center justify-center gap-2 px-4 py-1.5 text-center text-xs font-bold tracking-wider text-white uppercase before:size-1.5 before:rounded-full before:bg-white before:content-[''] ${
              outlet.registerOpen ? 'bg-linear-to-r from-green-500 to-green-600 before:animate-pulse' : 'bg-linear-to-r from-red-500 to-red-600 before:opacity-60'}`}>
              Register {outlet.registerOpen ? 'open' : 'closed'}
            </p>
            <div className="p-5">
              <h2 className="text-base font-semibold text-zinc-900">{outlet.name}</h2>
              <p className="mt-2 mb-1 text-[2rem] leading-tight font-bold tracking-tight tabular-nums">{rm(outlet.netSales)}</p>
              <p className="m-0 text-sm text-zinc-500">
                {outlet.orderCount} {outlet.orderCount === 1 ? 'order' : 'orders'}
                {outlet.openOrders > 0 && ` · ${outlet.openOrders} unpaid`}
                {outlet.lastSaleAt && ` · last sale ${formatTime(outlet.lastSaleAt)}`}
              </p>
            </div>
            {outlet.recentOrders.length > 0 && (
              <ul className="list-none divide-y divide-zinc-100 border-t border-zinc-100" aria-label={`Latest orders at ${outlet.name}`}>
                {outlet.recentOrders.map((order) => (
                  <li key={order.orderNumber} className="flex items-start justify-between gap-3 px-5 py-3 text-sm transition hover:bg-zinc-50">
                    <span className="min-w-0">
                      {/* The order number on the till's dark chip. */}
                      <strong className="mr-1.5 rounded-md bg-zinc-900 px-1.5 py-0.5 font-mono text-xs text-white">{order.orderNumber}</strong>{' '}
                      {order.items}
                      <span className="block text-xs text-zinc-500">{order.staffName} · {formatTime(order.paidAt)}</span>
                    </span>
                    <span className="font-semibold tabular-nums">{rm(order.total)}</span>
                  </li>
                ))}
              </ul>
            )}
          </article>
        ))}
      </div>
    </Page>
  )
}
