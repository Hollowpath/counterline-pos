import type { Order } from '../checkout/types'

export type ReceiptStatus = 'Paid' | 'Unpaid' | 'Voided' | 'Refunded'

/** What the receipt says the order is: anything but a paid sale is marked across the top. */
export function receiptStatus(order: Order): ReceiptStatus {
  if (order.refund) return 'Refunded'
  if (order.status === 'VOIDED') return 'Voided'
  if (order.status === 'OPEN') return 'Unpaid'
  return 'Paid'
}
