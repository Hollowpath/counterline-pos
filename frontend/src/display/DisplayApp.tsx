import { useCallback, useEffect, useState } from 'react'
import { ApiError, pinLockout, postJson, setAuthToken } from '../api/http'
import { PinEntry } from '../auth/PinEntry'
import { clearConnection, loadConnection, saveConnection } from './displayStore'
import { MemberPanel } from './MemberPanel'
import { Posters } from './Posters'
import { displayPaths, type CartView, type DisplayConnection } from './types'
import { useDisplayState } from './useDisplayState'
import { formatRM, toSen } from '../lib/money'

const rm = (amount: number) => formatRM(toSen(amount))

/** How long "Thank you" stays up after a sale is paid, before the posters come back. */
export const THANK_YOU_MS = 10_000

/**
 * The customer display: a second iPad facing the customer. Paired once with a POS by the access
 * code shown there; then it shows posters until the till opens a sale, the sale (and member login
 * or sign-up) while it is open, and "Thank you" once it is paid.
 */
export function DisplayApp() {
  const [connection, setConnection] = useState<DisplayConnection | null>(() => {
    const stored = loadConnection()
    setAuthToken(stored?.token ?? null)
    return stored
  })
  const [notice, setNotice] = useState<string | undefined>(undefined)

  const disconnected = useCallback(() => {
    clearConnection()
    setAuthToken(null)
    setConnection(null)
    setNotice('Disconnected')
  }, [])

  async function connect(accessCode: string) {
    try {
      const connected = await postJson<DisplayConnection>(displayPaths.connect, { accessCode })
      saveConnection(connected)
      setAuthToken(connected.token)
      setConnection(connected)
      setNotice(undefined)
    } catch (error) {
      return pinLockout(error) ?? (error instanceof ApiError && (error.status === 422 || error.status === 429)
        ? error.message
        : 'Offline')
    }
  }

  if (connection === null) {
    return (
      <main className="flex h-dvh flex-col items-center justify-center gap-1 overflow-hidden bg-linear-to-b from-white to-zinc-100 px-4 py-3 text-center">
        <h1 className="text-2xl font-bold tracking-tight text-zinc-900">Customer Display</h1>
        <p className="mb-2 max-w-[26rem] text-sm text-zinc-500">Access code</p>
        <PinEntry onSubmit={connect} notice={notice} fitScreen />
      </main>
    )
  }
  return <CustomerScreen outletName={connection.outletName} onDisconnected={disconnected} />
}

/**
 * "Thank you" for {@link THANK_YOU_MS} after a paid sale, even though the till has already moved on
 * (its empty cart puts the display back to idle); a new sale opened meanwhile replaces it at once.
 */
function useThankYou(cart: CartView | null): CartView | null {
  const [thanks, setThanks] = useState<CartView | null>(null)

  useEffect(() => {
    if (cart?.stage === 'THANK_YOU') setThanks(cart)
    else if (cart?.stage === 'CART') setThanks(null)
  }, [cart])

  useEffect(() => {
    if (thanks === null) return
    const timer = window.setTimeout(() => setThanks(null), THANK_YOU_MS)
    return () => window.clearTimeout(timer)
  }, [thanks])

  return thanks
}

function CustomerScreen({ outletName, onDisconnected }: { outletName: string; onDisconnected: () => void }) {
  const state = useDisplayState(onDisconnected)
  const cart = state?.cart ?? null
  const thanks = useThankYou(cart)

  if (thanks) {
    return (
      <div className="flex h-dvh min-h-dvh bg-zinc-100">
        <ThankYou cart={thanks} />
      </div>
    )
  }
  if (cart?.stage !== 'CART') {
    // No sale open on the till: posters, full screen.
    return (
      <div className="flex h-dvh min-h-dvh bg-zinc-100">
        <Posters outletName={state?.outletName ?? outletName} />
      </div>
    )
  }
  return (
    // One screen: the heading and totals stay in place and only the list of items scrolls (the
    // membership side scrolls on its own if a form is taller than the screen). A phone-width
    // screen stacks the two and scrolls as a page.
    <div className="grid h-dvh animate-fade-in grid-cols-[minmax(0,3fr)_minmax(18rem,2fr)] grid-rows-[minmax(0,1fr)] bg-zinc-100 max-md:h-auto max-md:min-h-dvh max-md:grid-cols-[minmax(0,1fr)] max-md:grid-rows-none">
      <section className="flex min-h-0 min-w-0 flex-col p-8 max-md:p-4">
        <CartLines cart={cart} />
      </section>
      <aside className="flex min-h-0 flex-col gap-4 overflow-y-auto bg-zinc-800 p-8 text-white shadow-[-8px_0_32px_-12px_rgb(24_24_27/0.4)] max-md:p-4 max-md:shadow-[0_-8px_32px_-12px_rgb(24_24_27/0.4)]" aria-label="Membership">
        <MemberPanel state={state} memberName={cart.memberName} />
      </aside>
    </div>
  )
}

function CartLines({ cart }: { cart: CartView }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <h2 className="mb-4 text-3xl font-bold tracking-tight text-zinc-900 max-md:text-2xl">Your order</h2>
      {cart.lines.length === 0 ? (
        <p className="flex flex-1 flex-col items-center justify-center gap-3 rounded-3xl border-2 border-dashed border-zinc-300 px-4 py-8 text-center text-lg text-zinc-400">
          <span className="text-5xl" aria-hidden="true">🍿</span>
          Your items will appear here.
        </p>
      ) : (
        <ul
          className={[
            'min-h-0 flex-1 list-none divide-y divide-zinc-100 overflow-y-auto rounded-3xl bg-white shadow-sm ring-1 ring-zinc-900/5',
            '[&_li]:grid [&_li]:grid-cols-[3rem_1fr_auto] [&_li]:items-center [&_li]:gap-3 [&_li]:px-5 [&_li]:py-4 [&_li]:text-lg',
          ].join(' ')}
          aria-label="Your order">
          {cart.lines.map((line, index) => (
            <li key={index} className="animate-rise-in">
              <span className="grid size-10 place-items-center rounded-xl bg-indigo-50 text-base font-bold text-indigo-600 tabular-nums">{line.quantity}×</span>
              <span className="font-medium text-zinc-900">
                {line.name}
                {line.detail && <span className="block text-sm font-normal text-green-600">{line.detail}</span>}
              </span>
              <LineAmount line={line} />
            </li>
          ))}
        </ul>
      )}
      <dl className="mt-5 rounded-3xl bg-white px-6 py-4 shadow-sm ring-1 ring-zinc-900/5 [&_dd]:tabular-nums [&>div]:flex [&>div]:justify-between [&>div]:py-1 [&>div]:text-lg [&>div]:text-zinc-600">
        <div>
          <dt>Subtotal ({cart.itemCount} {cart.itemCount === 1 ? 'item' : 'items'})</dt>
          <dd>{rm(cart.subtotal)}</dd>
        </div>
        {cart.discount > 0 && (
          <div>
            <dt>Discounts &amp; free items</dt>
            <dd>−{rm(cart.discount)}</dd>
          </div>
        )}
        <div className="-mx-6 mt-3 -mb-4 rounded-b-3xl bg-linear-to-r from-indigo-600 to-violet-600 px-6! py-4! text-[2rem]! font-bold text-white!" data-testid="display-total">
          <dt>Total</dt>
          <dd>{rm(cart.total)}</dd>
        </div>
      </dl>
    </div>
  )
}

/**
 * What a line costs. A free item shows its price struck through and "Free", so the customer sees
 * what was taken off without a column of minus amounts; a discounted one, its price struck through
 * and what it costs now.
 */
function LineAmount({ line }: { line: CartView['lines'][number] }) {
  // Published by a till from before line prices were shown: just the amount.
  const price = line.price ?? line.total
  if (line.free) {
    return (
      <span className="flex items-baseline gap-2 whitespace-nowrap">
        {price > 0 && <s className="text-base text-zinc-400">{rm(price)}</s>}
        <span className="rounded-full bg-green-50 px-3 py-0.5 text-base font-bold text-green-700 ring-1 ring-green-200 ring-inset">Free</span>
      </span>
    )
  }
  return (
    <span className="flex items-baseline gap-2 whitespace-nowrap">
      {line.total < price && <s className="text-base text-zinc-400">{rm(price)}</s>}
      <span className="font-semibold text-zinc-900 tabular-nums">{rm(line.total)}</span>
    </span>
  )
}

function ThankYou({ cart }: { cart: CartView }) {
  return (
    <div className="flex flex-1 animate-fade-in flex-col items-center justify-center gap-2 text-center text-xl text-zinc-600" role="status">
      <span className="mb-4 grid size-24 animate-check-pop place-items-center rounded-full bg-linear-to-br from-indigo-500 to-fuchsia-500 text-5xl shadow-xl shadow-indigo-500/30" aria-hidden="true">🍿</span>
      <p className="bg-linear-to-r from-indigo-600 to-fuchsia-600 bg-clip-text text-[3rem] leading-tight font-black tracking-tight text-transparent">Thank you{cart.memberName ? `, ${cart.memberName}` : ''}!</p>
      <p>Paid {rm(cart.total)}. Enjoy your popcorn.</p>
    </div>
  )
}
