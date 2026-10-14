import { toSen } from '../../lib/money'
import type { CatalogItem, PromotionInfo } from '../catalog/types'
import type { EditableDiscount, EditableOrder } from '../checkout/types'
import { discountAmountsSen, sumSen, type DiscountRule } from './pricing'

/** An option picked per unit of the line, e.g. 3 × Caramel in a gift box. */
export interface CartModifier {
  optionId: number
  name: string
  quantity: number
  priceSen: number
}

export interface CartLine {
  /** Unique within the cart: the same item can appear on several lines (different options or discounts). */
  key: string
  /** Null for a custom amount. */
  itemId: number | null
  name: string
  unitPriceSen: number
  modifiers: CartModifier[]
  quantity: number
  note: string
  /** In display order — the order the server applies the cap in. */
  discounts: DiscountRule[]
  /** A promotional item: deducted in full by its promotion (most need a member on the sale). */
  promotion?: PromotionInfo | null
  /** Bought, it counts towards "Buy 3": the cart lists these first. */
  countsForPromotions?: boolean
}

/** The customer the sale is made to (optional). */
export interface CartCustomer {
  id: number
  name: string
  phone: string | null
}

export interface Cart {
  /**
   * A sale is open: New Sale was tapped, or something was added. Until then the cart shows "No
   * Sale" and the customer display its posters.
   */
  open: boolean
  lines: CartLine[]
  note: string
  customer: CartCustomer | null
  /** Discounts on the whole order (the % tab), in display order. */
  orderDiscounts: DiscountRule[]
  /** A placed order loaded back in with "Edit Order": paying or placing saves that order. */
  editing: { orderId: number; orderNumber: string } | null
}

export type CartAction =
  | { type: 'addItem'; item: CatalogItem }
  | { type: 'addLine'; line: Omit<CartLine, 'key'> }
  | { type: 'updateLine'; key: string; line: Omit<CartLine, 'key'> }
  | { type: 'removeLine'; key: string }
  | { type: 'addCustomAmount'; amountSen: number }
  | { type: 'setOrderDiscounts'; discounts: DiscountRule[] }
  | { type: 'setNote'; note: string }
  | { type: 'setCustomer'; customer: CartCustomer | null }
  | { type: 'loadOrder'; order: EditableOrder }
  | { type: 'newSale' }
  | { type: 'clear' }

export const emptyCart: Cart = { open: false, lines: [], note: '', customer: null, orderDiscounts: [], editing: null }

let nextKey = 1
const newKey = () => `line-${nextKey++}`

/** A plain line: no options, note or discounts. Tapping the same item again adds to it. */
function isPlain(line: CartLine) {
  return line.modifiers.length === 0 && line.note === '' && line.discounts.length === 0
}

export function cartReducer(cart: Cart, action: CartAction): Cart {
  const changed = changeCart(cart, action)
  // Anything put in the cart opens the sale; clearing it closes it.
  return action.type === 'clear' || changed.open ? changed : { ...changed, open: true }
}

function changeCart(cart: Cart, action: CartAction): Cart {
  switch (action.type) {
    case 'addItem': {
      const { item } = action
      const existing = cart.lines.find((line) => line.itemId === item.id && isPlain(line))
      if (existing) {
        return {
          ...cart,
          lines: cart.lines.map((line) => (line === existing ? { ...line, quantity: line.quantity + 1 } : line)),
        }
      }
      const line: CartLine = {
        key: newKey(), itemId: item.id, name: item.name, unitPriceSen: toSen(item.price),
        modifiers: [], quantity: 1, note: '', discounts: [], promotion: item.promotion ?? null,
        countsForPromotions: item.countsForPromotions ?? false,
      }
      return { ...cart, lines: [...cart.lines, line] }
    }
    case 'addLine':
      return { ...cart, lines: [...cart.lines, { ...action.line, key: newKey() }] }
    case 'updateLine':
      return { ...cart, lines: cart.lines.map((line) => (line.key === action.key ? { ...action.line, key: line.key } : line)) }
    case 'removeLine':
      return { ...cart, lines: cart.lines.filter((line) => line.key !== action.key) }
    case 'addCustomAmount': {
      const line: CartLine = {
        key: newKey(), itemId: null, name: 'Custom Amount', unitPriceSen: action.amountSen,
        modifiers: [], quantity: 1, note: '', discounts: [],
      }
      return { ...cart, lines: [...cart.lines, line] }
    }
    case 'setOrderDiscounts':
      return { ...cart, orderDiscounts: action.discounts }
    case 'setNote':
      return { ...cart, note: action.note }
    case 'setCustomer':
      return { ...cart, customer: action.customer }
    case 'loadOrder':
      return fromEditableOrder(action.order)
    case 'newSale':
      return { ...cart, open: true }
    case 'clear':
      return emptyCart
  }
}

function toRule(discount: EditableDiscount): DiscountRule {
  return {
    id: discount.id, name: discount.name, kind: discount.kind, value: discount.value,
    requiresApproval: discount.requiresApproval, notForMembers: discount.notForMembers, countsAsVoucher: discount.countsAsVoucher,
    ...(discount.voucherCode ? { voucherCode: discount.voucherCode } : {}),
  }
}

/** A placed order back in the cart ("Edit Order"). */
function fromEditableOrder(order: EditableOrder): Cart {
  return {
    open: true,
    lines: order.lines.map((line) => ({
      key: newKey(),
      itemId: line.itemId,
      name: line.name,
      unitPriceSen: toSen(line.unitPrice),
      modifiers: line.modifiers.map((modifier) => ({
        optionId: modifier.optionId, name: modifier.name, quantity: modifier.quantity, priceSen: toSen(modifier.price),
      })),
      quantity: line.quantity,
      note: line.note ?? '',
      discounts: line.discounts.map(toRule),
      promotion: line.promotion,
      countsForPromotions: line.countsForPromotions,
    })),
    note: order.note ?? '',
    customer: order.customer && { id: order.customer.id, name: order.customer.name, phone: order.customer.phone },
    orderDiscounts: order.orderDiscounts.map(toRule),
    editing: { orderId: order.id, orderNumber: order.orderNumber },
  }
}

// ---- Order of lines: what is charged first, free items last ----

/**
 * Where a line is listed, so the staff can count at a glance: 0 = items bought that count towards
 * "Buy 3", 1 = other charged items (recycle bags, custom amounts), 2 = free items (promotional or
 * samples).
 */
export function lineGroup(line: Omit<CartLine, 'key'>): 0 | 1 | 2 {
  if (line.promotion || lineSubtotalSen(line) === 0) return 2
  return line.countsForPromotions ? 0 : 1
}

/** The lines as listed, in the order added within each group. */
export function sortedLines<T extends Omit<CartLine, 'key'>>(lines: T[]): T[] {
  return [...lines].sort((a, b) => lineGroup(a) - lineGroup(b))
}

// ---- Amounts (same formulas as the server's SaleOrderLine / SaleOrder) ----

export function modifierUnitSen(line: Pick<CartLine, 'modifiers'>): number {
  return line.modifiers.reduce((sum, modifier) => sum + modifier.priceSen * modifier.quantity, 0)
}

/** (unit price + options) × quantity, before discounts. */
export function lineSubtotalSen(line: Omit<CartLine, 'key'>): number {
  return (line.unitPriceSen + modifierUnitSen(line)) * line.quantity
}

export function lineDiscountAmountsSen(line: Omit<CartLine, 'key'>): number[] {
  return discountAmountsSen(lineSubtotalSen(line), line.discounts)
}

/** A promotional line is free: its promotion takes off the whole line (as the server records it). */
export function linePromotionSen(line: Omit<CartLine, 'key'>): number {
  return line.promotion ? lineSubtotalSen(line) : 0
}

export function lineTotalSen(line: Omit<CartLine, 'key'>): number {
  return lineSubtotalSen(line) - sumSen(lineDiscountAmountsSen(line)) - linePromotionSen(line)
}

export function hasPromotionalItems(cart: Cart): boolean {
  return cart.lines.some((line) => line.promotion)
}

/** Promotional items that need a member on the sale (all but the free recycle bags). */
export function needsMember(cart: Cart): boolean {
  return cart.lines.some((line) => line.promotion && line.promotion.membersOnly !== false)
}

/** A discount in the sale that can't be given with a member (the staff discount), if any. */
export function discountNotForMembers(cart: Cart): DiscountRule | null {
  return [...cart.orderDiscounts, ...cart.lines.flatMap((line) => line.discounts)].find((discount) => discount.notForMembers)
    ?? null
}

/** A discount in the sale needs a manager's approval (a department comp). */
export function needsDiscountApproval(cart: Cart): boolean {
  return [...cart.orderDiscounts, ...cart.lines.flatMap((line) => line.discounts)].some((discount) => discount.requiresApproval)
}
/**
 * A sale takes one discount, on a line or on the whole order. Describes the discount already in
 * the sale (other than on line `exceptLineKey`), e.g. "Staff Discount on Tin Cheese", or null.
 */
export function discountInSale(cart: Cart, exceptLineKey?: string): string | null {
  const line = cart.lines.find((l) => l.key !== exceptLineKey && l.discounts.length > 0)
  if (line) return `${line.discounts[0].name} on ${line.name}`
  if (cart.orderDiscounts.length > 0) return `${cart.orderDiscounts[0].name} on the order`
  return null
}

export function cartItemCount(cart: Cart): number {
  return cart.lines.reduce((count, line) => count + line.quantity, 0)
}

/** Gross: all lines before any discount. */
export function cartSubtotalSen(cart: Cart): number {
  return sumSen(cart.lines.map(lineSubtotalSen))
}

/** Order discounts apply to the total after line discounts. */
export function orderDiscountAmountsSen(cart: Cart): number[] {
  return discountAmountsSen(sumSen(cart.lines.map(lineTotalSen)), cart.orderDiscounts)
}

/** What is due. */
export function cartTotalSen(cart: Cart): number {
  return sumSen(cart.lines.map(lineTotalSen)) - sumSen(orderDiscountAmountsSen(cart))
}

