// Mirrors backend OrderDtos.

import type { PromotionInfo } from '../catalog/types'

export interface PaymentMethod {
  id: number
  name: string
  /** A sale takes at most one cashless method plus cash. */
  kind: 'CASH' | 'CASHLESS'
}

export interface PaymentInput {
  paymentMethodId: number
  amount: number
  /** Required for cashless methods. */
  reference: string | null
  /** Cash only: what the customer handed over. */
  cashTendered: number | null
}

export interface OrderPayment {
  id: number
  method: string
  kind: PaymentMethod['kind']
  amount: number
  reference: string | null
  cashTendered: number | null
  change: number | null
  createdAt: string
}

/** A catalogue item (with options per unit) or a custom amount — never both. */
export type CheckoutLine = {
  quantity: number
  note: string | null
  discountIds: number[]
} & (
  | { itemId: number; modifiers: { optionId: number; quantity: number }[]; customAmount?: never }
  | { customAmount: number; itemId?: never; modifiers?: never }
)

export interface CheckoutRequest {
  clientRequestId: string
  mode: 'PAY' | 'PLACE_ORDER'
  customerId: number | null
  note: string | null
  lines: CheckoutLine[]
  /** Order-level discounts (the % tab). */
  discountIds: number[]
  payments: PaymentInput[]
  /** A manager's special-access token, when a discount needs approval. */
  approvalToken?: string | null
  /** A physical voucher's code: its value comes off the order, as the sale's one discount. */
  voucherCode?: string | null
}

export interface OrderCustomer {
  id: number
  name: string
  phone: string | null
  email: string | null
}

/** A discount on a placed order being edited (a voucher's carries its code). */
export interface EditableDiscount {
  id: number
  name: string
  kind: 'PERCENT' | 'AMOUNT'
  value: number
  requiresApproval: boolean
  notForMembers: boolean
  /** A voucher, or counts as one: the sale gives no Buy 3 Free 1, seasonal or birthday free items. */
  countsAsVoucher: boolean
  voucherCode: string | null
}

/** A placed (unpaid) order as a cart, for "Edit Order"; at today's prices. */
export interface EditableOrder {
  id: number
  orderNumber: string
  note: string | null
  customer: OrderCustomer | null
  lines: {
    /** Null for a custom amount (then unitPrice is the amount keyed in). */
    itemId: number | null
    name: string
    unitPrice: number
    quantity: number
    note: string | null
    modifiers: { optionId: number; name: string; quantity: number; price: number }[]
    discounts: EditableDiscount[]
    promotion: PromotionInfo | null
    countsForPromotions: boolean
  }[]
  orderDiscounts: EditableDiscount[]
}

export type OrderStatus = 'OPEN' | 'CLOSED' | 'VOIDED'

export interface AppliedDiscount {
  name: string
  amount: number
  /** A promotion that made a promotional line free, rather than a discount. */
  promotion?: boolean
  /** Who approved a department comp. */
  detail?: string | null
}

/** The receipt's LOYALTY PROGRAM block. */
export interface LoyaltyPoints {
  before: number
  earned: number
  redeemed: number
  after: number
}

/** The cart priced by the server; `problems` say why it can't be paid yet. */
export interface Quote {
  subtotal: number
  discountTotal: number
  total: number
  problems: string[]
  loyalty: LoyaltyPoints | null
}

export interface OrderLine {
  lineNumber: number
  /** Null for a custom amount. */
  itemId: number | null
  itemName: string
  note: string | null
  unitPrice: number
  modifierUnitPrice: number
  quantity: number
  modifiers: { groupName: string; optionName: string; quantity: number; unitPrice: number }[]
  lineSubtotal: number
  discounts: AppliedDiscount[]
  discountTotal: number
  lineTotal: number
}

export interface Order {
  id: number
  orderNumber: string
  receiptCode: string
  status: OrderStatus
  subtotal: number
  discountTotal: number
  total: number
  amountPaid: number
  note: string | null
  staffName: string
  customer: OrderCustomer | null
  createdAt: string
  /** When it was paid (null while unpaid): the day it is reported on, and the date on its receipt. */
  closedAt: string | null
  lines: OrderLine[]
  orderDiscounts: AppliedDiscount[]
  payments: OrderPayment[]
  voided: { reason: string; voidedBy: string; voidedAt: string } | null
  refund: { amount: number; reason: string; refundedBy: string; refundedAt: string } | null
  /** The member's points around this sale, once it is paid. */
  loyalty?: LoyaltyPoints | null
}

/** What the cart shows as soon as a member is added (mirrors MemberPerksController). */
export interface MemberPerks {
  points: number
  /** Points per Member FOC item; null when no redemption runs. */
  redemptionCost: number | null
  birthdayMonth: boolean
  /** A new member's free gift, while it is still theirs to take. */
  newMemberGifts: { promotion: string; items: string[]; availableUntil: string }[]
  /** Free items the staff forgot last time, to give on this sale. */
  owedItems?: { promotion: string; quantity: number; items: string[]; reason: string }[]
}

export const checkoutPaths = {
  orders: '/api/v1/orders',
  quote: '/api/v1/orders/quote',
  order: (orderId: number) => `/api/v1/orders/${orderId}`,
  editableOrder: (orderId: number) => `/api/v1/orders/${orderId}/cart`,
  paymentMethods: '/api/v1/payment-methods',
  memberPerks: (customerId: number) => `/api/v1/customers/${customerId}/perks`,
}
