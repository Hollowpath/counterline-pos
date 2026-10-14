import type { Dispatch } from 'react'
import { formatRM } from '../../lib/money'
import { maskPhone } from '../../lib/privacy'
import { REGISTER_CLOSED_MESSAGE, useRegister } from '../register/registerApi'
import {
  cartItemCount,
  cartSubtotalSen,
  cartTotalSen,
  discountNotForMembers,
  lineGroup,
  linePromotionSen,
  lineSubtotalSen,
  lineTotalSen,
  needsMember,
  orderDiscountAmountsSen,
  sortedLines,
  type Cart,
  type CartAction,
  type CartLine,
} from './cart'
import type { QuoteState } from './useCartQuote'
import { useMemberPerks } from './useMemberPerks'
import { cartNotice, lineDetail, removeX } from './styles'

const NEEDS_MEMBER = 'Promotional items need a member. Add the customer’s membership to the sale, or remove the promotional items.'

/** The sale on the right of the Point of Sale (below the grid on a phone). */
const cartPanel = 'z-[1] flex min-h-0 flex-col bg-white shadow-[-8px_0_24px_-12px_rgb(24_24_27/0.15)] ring-1 ring-zinc-900/5 max-md:shadow-[0_-8px_24px_-12px_rgb(24_24_27/0.2)]'
/** A member's perk under their name (birthday month, a free gift). */
const perk = 'mx-4 mb-1.5 rounded-xl px-3 py-1.5 text-xs font-medium ring-1 ring-inset'
/** [Current points] + [Earned from this cart] = [Total after this purchase] */
const pointsBox = 'flex min-w-0 flex-1 flex-col items-center justify-between gap-0.5 rounded-xl px-1 py-1.5 text-center leading-tight font-medium text-zinc-500 ring-1 ring-inset [&_strong]:text-[0.95rem] [&_strong]:tabular-nums'

/** e.g. "14 Oct, 3:05 pm", in the terminal's time zone (the outlet's). */
function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('en-MY', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
}

export interface CartNotice {
  tone: 'info' | 'error'
  text: string
  /** The placed order it is about, so it can go once that order is voided. */
  orderId?: number
}

interface CartPanelProps {
  cart: Cart
  dispatch: Dispatch<CartAction>
  /** A Place Order request is in flight. */
  busy: boolean
  notice: CartNotice | null
  onPay: () => void
  onPlaceOrder: () => void
  onChooseCustomer: () => void
  onEditLine: (line: CartLine) => void
  /** The server's quote for this cart (promotion problems, points). */
  quote: QuoteState
}

export function CartPanel({
  cart, dispatch, busy, notice, onPay, onPlaceOrder, onChooseCustomer, onEditLine, quote,
}: CartPanelProps) {
  const itemCount = cartItemCount(cart)
  const subtotal = formatRM(cartSubtotalSen(cart))
  const orderDiscounts = orderDiscountAmountsSen(cart)
  const empty = cart.lines.length === 0
  const registerClosed = useRegister().open === false
  // Shown at once; the server's quote adds the rest (free gifts due, Buy 3, Spend RMx, points,
  // once-only rewards).
  const staffOnly = cart.customer ? discountNotForMembers(cart) : null
  const problems = needsMember(cart) && cart.customer === null ? [NEEDS_MEMBER]
    : staffOnly ? [`${staffOnly.name} can’t be given on a member’s sale. Remove ${cart.customer!.name} or the discount.`]
      : quote.status === 'ready' ? quote.quote.problems : []
  const perks = useMemberPerks(cart.customer?.id ?? null)
  // The server's preview once the cart is quoted; until then, the member's points as they stand.
  const loyalty = (quote.status === 'ready' ? quote.quote.loyalty : null)
    ?? (perks ? { before: perks.points, earned: 0, redeemed: 0, after: perks.points } : null)
  const mustRedeem = perks?.redemptionCost != null && loyalty !== null && loyalty.after >= perks.redemptionCost
  // Problems stop payment only: Place Order always saves the sale as it is.
  const blocked = problems.length > 0

  if (!cart.open) {
    // "No Sale": New Sale (or adding an item) opens one, and only then
    // does the customer display offer member login.
    return (
      <aside className={cartPanel} aria-label="Current sale">
        <header className="flex items-center justify-between border-b border-zinc-100 px-4 py-3.5">
          <h2 className="text-base font-bold tracking-tight text-zinc-900">No Sale</h2>
        </header>
        {notice && (
          <p className={cartNotice[notice.tone]} role={notice.tone === 'error' ? 'alert' : 'status'}>
            {notice.text}
          </p>
        )}
        <div className="flex flex-1 flex-col items-center justify-center gap-4 p-6 text-center">
          <span className="grid size-16 place-items-center rounded-2xl bg-indigo-50 text-indigo-500 ring-1 ring-indigo-100" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} className="size-8"><path strokeLinecap="round" strokeLinejoin="round" d="M15.75 10.5V6a3.75 3.75 0 1 0-7.5 0v4.5m11.356-1.993 1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 0 1-1.12-1.243l1.264-12A1.125 1.125 0 0 1 5.513 7.5h12.974c.576 0 1.059.435 1.119 1.007ZM8.625 10.5a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm7.5 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Z" /></svg>
          </span>
          <button type="button" className="btn btn-primary w-[min(16rem,100%)] py-4! text-base!" onClick={() => dispatch({ type: 'newSale' })}>
            New Sale
          </button>
          <p className="text-sm text-zinc-500">Or tap an item to start.</p>
        </div>
      </aside>
    )
  }

  return (
    <aside className={cartPanel} aria-label="Current sale">
      <header className="flex items-center justify-between border-b border-zinc-100 px-4 py-3">
        <h2 className="text-base font-bold tracking-tight text-zinc-900">{cart.editing ? `Editing ${cart.editing.orderNumber}` : 'New Sale'}</h2>
        <button
          type="button"
          className="icon-btn"
          aria-label={cart.editing ? 'Stop editing' : 'Clear sale'}
          title={cart.editing ? 'Stop editing (the order stays as it was)' : 'Clear the sale'}
          disabled={busy}
          onClick={() => dispatch({ type: 'clear' })}
        >
          ✕
        </button>
      </header>

      <div className="mx-3 my-2.5 flex min-h-11 items-center justify-between gap-2 rounded-xl bg-zinc-50 px-3 py-1 text-sm ring-1 ring-zinc-200/70 ring-inset">
        {cart.customer ? (
          <>
            <span>
              <strong className="font-semibold text-zinc-900">{cart.customer.name}</strong>
              {perks?.birthdayMonth && (
                <span className="ml-[0.35rem] text-[1.25rem] leading-none" role="img" aria-label="Birthday month" title="Birthday month">
                  🎂
                </span>
              )}
              {cart.customer.phone && <span className="text-zinc-500"> · {maskPhone(cart.customer.phone)}</span>}
            </span>
            <button
              type="button"
              className={removeX}
              aria-label="Remove customer"
              disabled={busy}
              onClick={() => dispatch({ type: 'setCustomer', customer: null })}
            >
              ✕
            </button>
          </>
        ) : (
          <button type="button" className="flex w-full items-center gap-2 py-1.5 font-semibold text-indigo-600 transition hover:text-indigo-500" disabled={busy} onClick={onChooseCustomer}>
            <span className="grid size-6 place-items-center rounded-full bg-indigo-600 text-sm leading-none text-white">+</span>
            Add customer
          </button>
        )}
      </div>
      {perks?.birthdayMonth && (
        <p className={`${perk} bg-orange-50 text-orange-800 ring-orange-200`} role="status">
          Birthday month: a free birthday can with every 3 items (max 2 a receipt).
        </p>
      )}
      {perks?.owedItems?.map((owed) => (
        <p key={owed.promotion} className={`${perk} bg-green-50 text-green-800 ring-green-200`} role="status">
          Owed from last time: {owed.quantity} free {owed.items.join(' or ')} ({owed.reason}).
        </p>
      ))}
      {perks?.newMemberGifts.map((gift) => (
        <p key={gift.promotion} className={`${perk} bg-green-50 text-green-800 ring-green-200`} role="status">
          New member gift: 1 free {gift.items.join(' or ')} until {formatTime(gift.availableUntil)} (optional).
        </p>
      ))}
      {loyalty && (
        <div className="flex items-stretch gap-1 px-3 pt-1 pb-2 text-[0.6rem]" data-testid="points-preview" aria-label="Loyalty points">
          <span className={`${pointsBox} bg-zinc-50 ring-zinc-200/70 [&_strong]:text-zinc-900`}>
            <span>Current points</span>
            <strong>{loyalty.before}</strong>
          </span>
          <span className="self-center text-[0.8rem] font-bold text-zinc-300">+</span>
          <span className={`${pointsBox} bg-zinc-50 ring-zinc-200/70 [&_strong]:text-zinc-900`}>
            <span>Earned from this cart</span>
            <strong>{loyalty.earned}</strong>
          </span>
          {loyalty.redeemed > 0 && (
            <>
              <span className="self-center text-[0.8rem] font-bold text-zinc-300">−</span>
              <span className={`${pointsBox} bg-zinc-50 ring-zinc-200/70 [&_strong]:text-zinc-900`}>
                <span>Redeemed</span>
                <strong>{loyalty.redeemed}</strong>
              </span>
            </>
          )}
          <span className="self-center text-[0.8rem] font-bold text-zinc-300">=</span>
          <span className={`${pointsBox} ${mustRedeem ? 'bg-red-50 ring-red-300 [&_strong]:text-red-600' : 'bg-green-50 ring-green-200 [&_strong]:text-green-700'}`}>
            <span>Total after this purchase</span>
            <strong>{loyalty.after}</strong>
          </span>
        </div>
      )}

      {/* Charged items first (those counting towards Buy 3 at the top), free items last. */}
      <ul className="list-none overflow-y-auto px-2">
        {sortedLines(cart.lines).map((line, index, lines) => (
          <li
            key={line.key}
            className={`grid grid-cols-[auto_1fr_auto_auto] items-center gap-2.5 rounded-xl py-2 pr-1 pl-2 text-sm transition hover:bg-zinc-50 ${
              // A heavier line where the next group starts (charged, then other items, then free).
              index > 0 && lineGroup(lines[index - 1]) < lineGroup(line) ? 'mt-2 rounded-t-none border-t border-dashed border-zinc-300 pt-3' : ''}`}
          >
            <span className="grid h-6 min-w-7 place-items-center rounded-lg bg-zinc-100 px-1.5 text-xs font-bold text-zinc-600 tabular-nums">{line.quantity}x</span>
            <button
              type="button"
              className="flex flex-col gap-0.5 text-left font-medium text-zinc-800"
              aria-label={`Edit ${line.name}`}
              disabled={busy}
              onClick={() => onEditLine(line)}
            >
              {line.name}
              {line.modifiers.length > 0 && (
                <span className={lineDetail}>
                  {line.modifiers.map((modifier) => `${modifier.quantity} ${modifier.name}`).join(', ')}
                </span>
              )}
              {line.note && <span className={lineDetail}>“{line.note}”</span>}
              {line.promotion && (
                <span className={lineDetail}>
                  {line.promotion.name} −{formatRM(linePromotionSen(line))}
                </span>
              )}
              {line.discounts.length > 0 && (
                <span className={`${lineDetail} text-green-600!`}>
                  {line.discounts.map((discount) => discount.name).join(', ')} −
                  {formatRM(lineSubtotalSen(line) - lineTotalSen(line))}
                </span>
              )}
            </button>
            <span className="font-semibold text-zinc-900 tabular-nums">{formatRM(lineTotalSen(line))}</span>
            <button
              type="button"
              className="grid size-8 place-items-center rounded-lg text-xs text-red-400 transition hover:bg-red-500 hover:text-white"
              aria-label={`Remove ${line.name}`}
              disabled={busy}
              onClick={() => dispatch({ type: 'removeLine', key: line.key })}
            >
              ✕
            </button>
          </li>
        ))}
      </ul>

      <div className="mx-4 mt-1 flex justify-between border-t border-zinc-100 pt-3 pb-2 text-sm text-zinc-500">
        <span>Subtotal</span>
        <span className="font-medium text-zinc-700 tabular-nums">{subtotal}</span>
      </div>
      {cart.orderDiscounts.map((discount, index) => (
        <div key={discount.id} className="flex items-center justify-between px-4 pb-2 text-sm font-medium text-green-600">
          <span>
            {discount.name}
            <button
              type="button"
              className={removeX}
              aria-label={`Remove ${discount.name}`}
              disabled={busy}
              onClick={() => dispatch({ type: 'setOrderDiscounts', discounts: cart.orderDiscounts.filter((d) => d.id !== discount.id) })}
            >
              ✕
            </button>
          </span>
          <span className="tabular-nums">−{formatRM(orderDiscounts[index])}</span>
        </div>
      ))}

      <textarea
        className="mx-4 mb-3 min-h-16 flex-1 resize-none rounded-xl bg-zinc-50 px-3 py-2 text-sm text-zinc-900 ring-1 ring-zinc-200/70 transition ring-inset placeholder:text-zinc-400 focus:bg-white focus:ring-2 focus:ring-indigo-500 focus:outline-none"
        placeholder="Enter an order note…"
        aria-label="Order note"
        value={cart.note}
        maxLength={500}
        disabled={busy}
        onChange={(event) => dispatch({ type: 'setNote', note: event.target.value })}
      />

      {notice && (
        <p className={cartNotice[notice.tone]} role={notice.tone === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </p>
      )}

      {problems.length > 0 && (
        <ul className="mx-4 mb-3 list-disc rounded-xl bg-red-50 py-2.5 pr-3 pl-8 text-[0.8rem] font-medium text-red-700 ring-1 ring-red-200 ring-inset [&_li+li]:mt-1" role="alert" aria-label="Why this sale can’t be paid yet">
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      )}
      {registerClosed && !empty && (
        <p className={cartNotice.error} role="status">
          {REGISTER_CLOSED_MESSAGE}
        </p>
      )}

      <footer className="mx-3 flex items-baseline justify-between rounded-t-2xl bg-zinc-100 px-4 pt-3 pb-2 text-zinc-900 ring-1 ring-zinc-200/70 ring-inset">
        <span className="text-xs font-semibold tracking-wider text-zinc-500 uppercase">
          {itemCount} {itemCount === 1 ? 'Item' : 'Items'}
        </span>
        <span className="text-2xl font-bold tracking-tight tabular-nums" data-testid="cart-total">{formatRM(cartTotalSen(cart))}</span>
      </footer>
      <div className="mx-3 mb-3 grid grid-cols-[2fr_1fr] gap-2 rounded-b-2xl bg-zinc-100 px-2 pb-2">
        <button type="button" className="rounded-xl bg-linear-to-b from-indigo-500 to-indigo-600 px-2 py-3.5 text-base font-bold text-white shadow-lg shadow-indigo-600/30 transition enabled:hover:brightness-110 enabled:active:scale-[0.98]" disabled={empty || busy} onClick={onPlaceOrder}>
          {busy ? 'Placing…' : 'Place Order'}
        </button>
        <button type="button" className="rounded-xl bg-zinc-300 px-2 py-3.5 text-base font-bold text-zinc-800 transition enabled:hover:bg-zinc-400/70 enabled:active:scale-[0.98]" disabled={empty || busy || registerClosed || blocked} onClick={onPay}>
          Pay
        </button>
      </div>
    </aside>
  )
}
