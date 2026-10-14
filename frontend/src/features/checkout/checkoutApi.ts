import { getJson, postJson, putJson } from '../../api/http'
import type { Cart, CartLine } from '../sell/cart'
import { newRequestId } from '../../lib/requestId'
import {
  checkoutPaths, type CheckoutLine, type CheckoutRequest, type EditableOrder, type Order, type PaymentInput, type Quote,
} from './types'

/**
 * Pay for the cart now (CLOSED order) or place it unpaid (OPEN order). Prices are the server's.
 * A cart editing a placed order saves that order instead ("Edit Order").
 * `approvalToken`: a manager's special-access token, when a discount needs approval.
 */
export function submitCheckout(
  cart: Cart,
  mode: CheckoutRequest['mode'],
  payments: PaymentInput[],
  clientRequestId: string,
  approvalToken?: string,
): Promise<Order> {
  const request = { ...toCheckoutRequest(cart, mode, payments, clientRequestId), approvalToken }
  return cart.editing
    ? putJson<Order>(checkoutPaths.order(cart.editing.orderId), request)
    : postJson<Order>(checkoutPaths.orders, request)
}

/** The cart priced by the server, with any promotion problems and the member's points; nothing is saved. */
export function quoteCart(cart: Cart): Promise<Quote> {
  const path = cart.editing ? `${checkoutPaths.quote}?editing=${cart.editing.orderId}` : checkoutPaths.quote
  return postJson<Quote>(path, toCheckoutRequest(cart, 'PAY', [], newRequestId()))
}

/** A placed order as a cart, for "Edit Order". */
export function loadEditableOrder(orderId: number): Promise<EditableOrder> {
  return getJson<EditableOrder>(checkoutPaths.editableOrder(orderId))
}

function toCheckoutRequest(
  cart: Cart,
  mode: CheckoutRequest['mode'],
  payments: PaymentInput[],
  clientRequestId: string,
): CheckoutRequest {
  return {
    clientRequestId,
    mode,
    customerId: cart.customer?.id ?? null,
    note: cart.note.trim() || null,
    lines: cart.lines.map(toCheckoutLine),
    discountIds: cart.orderDiscounts.filter((discount) => !discount.voucherCode).map((discount) => discount.id),
    voucherCode: cart.orderDiscounts.find((discount) => discount.voucherCode)?.voucherCode ?? null,
    payments,
  }
}

function toCheckoutLine(line: CartLine): CheckoutLine {
  const common = {
    quantity: line.quantity,
    note: line.note.trim() || null,
    discountIds: line.discounts.map((discount) => discount.id),
  }
  return line.itemId === null
    ? { ...common, customAmount: line.unitPriceSen / 100 }
    : {
        ...common,
        itemId: line.itemId,
        modifiers: line.modifiers.map((modifier) => ({ optionId: modifier.optionId, quantity: modifier.quantity })),
      }
}


