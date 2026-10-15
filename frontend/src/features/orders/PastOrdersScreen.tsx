import { useState } from 'react'
import { usePagedList } from '../../api/usePagedList'
import { AppBar } from '../../app/AppBar'
import type { Staff } from '../../auth/types'
import { LoadState } from '../../components/LoadState'
import { formatDate, formatTime } from '../../lib/dates'
import { formatRM, toSen } from '../../lib/money'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import type { OrderStatus } from '../checkout/types'
import { OrderDetail } from './OrderDetail'
import { ordersPaths, type OrderSummary } from './ordersApi'
import { useRegister } from '../register/registerApi'
import { appBarTitle, countBadge, pill, rowList, segmented, splitScreen } from '../../styles/ui'

const TABS: { status: OrderStatus; label: string }[] = [
  { status: 'OPEN', label: 'Open' },
  { status: 'CLOSED', label: 'Closed' },
  { status: 'VOIDED', label: 'Voided' },
]

interface PastOrdersScreenProps {
  staff: Staff
  onOpenMenu: () => void
  /** "Edit Order" on an unpaid order: back to the Point of Sale with it in the cart. */
  onEditOrder: (orderId: number) => void
  /** An unpaid order was voided. */
  onOrderVoided?: (orderId: number) => void
}

/** Past Orders: status tabs and search on the left, the selected order on the right. */
export function PastOrdersScreen({ staff, onOpenMenu, onEditOrder, onOrderVoided }: PastOrdersScreenProps) {
  const [status, setStatus] = useState<OrderStatus>('CLOSED')
  const [searchInput, setSearchInput] = useState('')
  const query = useDebouncedValue(searchInput.trim(), 300)
  const { state, hasMore, loadMore, refresh } = usePagedList<OrderSummary>((page) => ordersPaths.search(status, query, page))
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const { openOrders } = useRegister()

  return (
    <div className={splitScreen.screen} data-detail={selectedId !== null}>
      <section className={splitScreen.list} aria-label="Orders">
        <div className={segmented} role="tablist" aria-label="Order status">
          {TABS.map((tab) => (
            <button
              key={tab.status}
              type="button"
              role="tab"
              aria-selected={tab.status === status}
              onClick={() => {
                setStatus(tab.status)
                setSelectedId(null)
              }}
            >
              {tab.label}
              {tab.status === 'OPEN' && openOrders > 0 && (
                <span className={`${countBadge} ml-1.5 align-[1px]`} aria-label={`${openOrders} open`}>{openOrders}</span>
              )}
            </button>
          ))}
        </div>
        <input
          className={splitScreen.search}
          type="search"
          placeholder="Search order no., receipt or payment ref"
          aria-label="Search orders"
          value={searchInput}
          maxLength={40}
          onChange={(event) => setSearchInput(event.target.value)}
        />

        {state.phase !== 'ready' ? (
          <LoadState status={state.phase} message={state.phase === 'error' ? state.message : undefined} onRetry={refresh} />
        ) : state.items.length === 0 ? (
          <p className="p-10 text-center text-sm text-zinc-500">{query ? 'No orders match your search.' : 'No orders here yet.'}</p>
        ) : (
          <>
            <ul className={rowList}>
              {state.items.map((order) => (
                <li key={order.id}>
                  <OrderRow order={order} selected={order.id === selectedId} onSelect={() => setSelectedId(order.id)} />
                </li>
              ))}
            </ul>
            {hasMore && (
              <button type="button" className={`btn ${splitScreen.more}`} disabled={state.loadingMore} onClick={() => void loadMore()}>
                {state.loadingMore ? 'Loading…' : 'Load more'}
              </button>
            )}
          </>
        )}
      </section>

      <section className={splitScreen.detail} aria-label="Order detail">
        {selectedId === null ? (
          <div className="grid h-full place-items-center">
            <p className="flex flex-col items-center gap-3 text-sm font-medium text-zinc-400">
              <span className="grid size-14 place-items-center rounded-2xl bg-white text-2xl shadow-sm ring-1 ring-zinc-900/5" aria-hidden="true">🧾</span>
              Select an order
            </p>
          </div>
        ) : (
          <>
            <button type="button" className={`btn ${splitScreen.back}`} onClick={() => setSelectedId(null)}>
              ‹ Orders
            </button>
            <OrderDetail key={selectedId} orderId={selectedId} staff={staff} onChanged={refresh} onVoided={onOrderVoided}
              onEdit={onEditOrder} />
          </>
        )}
      </section>

      <AppBar label="Past orders" onOpenMenu={onOpenMenu}>
        <p className={appBarTitle}>Past Orders</p>
      </AppBar>
    </div>
  )
}

const STATUS_LABEL: Record<OrderStatus, string> = { OPEN: 'Unpaid', CLOSED: 'Paid', VOIDED: 'Voided' }
const STATUS_COLOUR: Record<string, string> = {
  Paid: pill.green, Unpaid: pill.orange, Voided: pill.red, Refunded: pill.red,
}

function OrderRow({ order, selected, onSelect }: { order: OrderSummary; selected: boolean; onSelect: () => void }) {
  const label = order.refunded ? 'Refunded' : STATUS_LABEL[order.status]
  return (
    <button
      type="button"
      className={[
        "grid w-full grid-cols-[auto_auto_1fr_auto] [grid-template-areas:'number_total_status_time'_'items_items_items_time']",
        'mb-1 items-center gap-x-2 gap-y-1 rounded-xl px-3 py-2.5 text-left transition hover:bg-zinc-50',
        'aria-[current=true]:bg-zinc-200/70',
      ].join(' ')}
      aria-current={selected ? 'true' : undefined} onClick={onSelect}>
      <span className="rounded-lg bg-zinc-900 px-2 py-0.5 font-mono text-xs font-bold text-white [grid-area:number]">{order.orderNumber}</span>
      <span className="font-semibold text-zinc-900 tabular-nums [grid-area:total]">{formatRM(toSen(order.total))}</span>
      <span className={`justify-self-start [grid-area:status] ${pill.base} ${STATUS_COLOUR[label]}`}>{label}</span>
      <span className="truncate text-xs text-zinc-500 [grid-area:items]">
        {order.customerName && <span className="mr-1.5 rounded-md bg-indigo-100 px-1.5 py-0.5 text-[0.7rem] font-semibold text-indigo-700">{order.customerName}</span>}
        {order.itemSummary}
      </span>
      <span className="text-right text-[0.7rem] leading-snug text-zinc-400 tabular-nums [grid-area:time]">
        {formatTime(order.createdAt)}
        <br />
        {formatDate(order.createdAt)}
      </span>
    </button>
  )
}
