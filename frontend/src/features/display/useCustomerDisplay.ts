import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getJson, postJson, putJson } from '../../api/http'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import {
  cartItemCount,
  cartSubtotalSen,
  cartTotalSen,
  lineSubtotalSen,
  lineTotalSen,
  sortedLines,
  type Cart,
} from '../sell/cart'
import { displayPaths, saveDisplayId, type DisplayStatus, type WaitingMember } from './displayApi'

const PUBLISH_DEBOUNCE_MS = 300
const STATUS_POLL_MS = 1500

/**
 * What the customer sees (mirrors backend DisplayDtos.CartView). Never the member's points: the
 * screen faces the shop.
 */
interface CartView {
  /** IDLE: no sale open, so the display shows its posters. */
  stage: 'IDLE' | 'CART' | 'THANK_YOU'
  lines: { quantity: number; name: string; detail: string | null; price: number; total: number; free: boolean }[]
  itemCount: number
  subtotal: number
  discount: number
  total: number
  memberName: string | null
}

function toView(cart: Cart, stage: CartView['stage'] = cart.open ? 'CART' : 'IDLE'): CartView {
  const subtotal = cartSubtotalSen(cart)
  const total = cartTotalSen(cart)
  return {
    stage,
    // In the same order as the till: charged first, free items last.
    lines: sortedLines(cart.lines).map((line) => {
      const free = Boolean(line.promotion) || lineSubtotalSen(line) === 0
      const discounted = !free && lineTotalSen(line) < lineSubtotalSen(line) ? line.discounts[0]?.name ?? null : null
      const options = line.modifiers.map((modifier) => `${modifier.quantity} ${modifier.name}`).join(', ')
      return {
        quantity: line.quantity,
        name: line.name,
        detail: [options || null, discounted].filter(Boolean).join(' · ') || null,
        price: lineSubtotalSen(line) / 100,
        total: lineTotalSen(line) / 100,
        free,
      }
    }),
    itemCount: cartItemCount(cart),
    subtotal: subtotal / 100,
    discount: (subtotal - total) / 100,
    total: total / 100,
    // The screen faces the shop: first name only.
    memberName: cart.customer ? cart.customer.name.split(' ')[0] : null,
  }
}

/**
 * Keeps the paired customer display in step with the sale: publishes the cart as it changes, and
 * watches for a member who logged in or signed up on the display (`onMember` adds them to the
 * sale; they are then marked as added). Display problems never get in the way of selling, so
 * failures are ignored.
 */
export function useCustomerDisplay(displayId: number | null, cart: Cart, onMember: (member: WaitingMember) => boolean,
                                   onGone: () => void) {
  const [waitingMember, setWaitingMember] = useState<WaitingMember | null>(null)
  const view = JSON.stringify(toView(cart))
  const debouncedView = useDebouncedValue(view, PUBLISH_DEBOUNCE_MS)
  const published = useRef<string | null>(null)

  useEffect(() => {
    published.current = null
  }, [displayId])

  useEffect(() => {
    if (displayId === null || debouncedView !== view || published.current === view) return
    published.current = view
    putJson(displayPaths.cart(displayId), JSON.parse(view)).catch(() => {
      published.current = null // try again with the next change
    })
  }, [displayId, debouncedView, view])

  useEffect(() => {
    if (displayId === null) {
      setWaitingMember(null)
      return
    }
    let stopped = false
    let timer: number | undefined
    async function poll() {
      try {
        const status = await getJson<DisplayStatus>(displayPaths.display(displayId!))
        if (!stopped) setWaitingMember(status.waitingMember)
      } catch (error) {
        if (stopped) return
        if (error instanceof ApiError && error.status === 404) {
          saveDisplayId(null)
          onGone()
          return
        }
      }
      if (!stopped) timer = window.setTimeout(poll, STATUS_POLL_MS)
    }
    void poll()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [displayId, onGone])

  // A member who logged in or signed up on the display goes straight onto the sale (when the
  // till can take them now: not while it is taking payment).
  const onMemberRef = useRef(onMember)
  useEffect(() => {
    onMemberRef.current = onMember
  })
  useEffect(() => {
    if (displayId === null || waitingMember === null || !onMemberRef.current(waitingMember)) return
    setWaitingMember(null)
    postJson(displayPaths.confirm(displayId), { customerId: waitingMember.customerId }).catch(() => {
      // Still waiting on the server: the next poll offers them again, and adding them is harmless.
    })
  }, [displayId, waitingMember])

  /** "Thank you" on the display once the sale is paid. */
  const showThankYou = useCallback((paidCart: Cart) => {
    if (displayId === null) return
    const thanks = JSON.stringify(toView(paidCart, 'THANK_YOU'))
    published.current = thanks
    putJson(displayPaths.cart(displayId), JSON.parse(thanks)).catch(() => {})
  }, [displayId])

  return { showThankYou }
}
