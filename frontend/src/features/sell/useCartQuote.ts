import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../../api/http'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import { quoteCart } from '../checkout/checkoutApi'
import type { Quote } from '../checkout/types'
import { hasPromotionalItems, type Cart } from './cart'

export type QuoteState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; quote: Quote }
  | { status: 'error'; message: string }

const DEBOUNCE_MS = 300

/**
 * Asks the server whether the sale qualifies for its promotional items, and what it does to the
 * member's points, whenever a sale with a member or promotional items changes. The server applies
 * the same rules at checkout; this lets the till say why before the cashier presses Pay.
 */
export function useCartQuote(cart: Cart): QuoteState {
  const relevant = cart.lines.length > 0 && (cart.customer !== null || hasPromotionalItems(cart))
  // Only what the server prices: a new key means a new quote.
  const key = useMemo(
    () => (relevant
      ? JSON.stringify({
          lines: cart.lines.map((line) => [line.itemId, line.unitPriceSen, line.quantity, line.modifiers, line.discounts]),
          customer: cart.customer?.id,
          discounts: cart.orderDiscounts,
        })
      : ''),
    [relevant, cart],
  )
  const debouncedKey = useDebouncedValue(key, DEBOUNCE_MS)
  const [state, setState] = useState<{ key: string; value: QuoteState }>({ key: '', value: { status: 'idle' } })

  useEffect(() => {
    if (debouncedKey === '' || debouncedKey !== key) return
    let current = true
    quoteCart(cart)
      .then((quote) => current && setState({ key: debouncedKey, value: { status: 'ready', quote } }))
      .catch((error: unknown) => {
        if (!current) return
        const message = error instanceof ApiError ? error.message : 'Could not check the promotions. Pay will check again.'
        setState({ key: debouncedKey, value: { status: 'error', message } })
      })
    return () => {
      current = false
    }
    // The cart is read when the debounced key settles; the key captures everything quoted.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedKey])

  if (!relevant) return { status: 'idle' }
  return state.key === key ? state.value : { status: 'loading' }
}
