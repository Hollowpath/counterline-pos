import { postJson } from '../../api/http'
import type { LoginResponse } from '../../auth/types'
import type { Order, OrderStatus } from '../checkout/types'

// Mirrors backend OrderDtos.OrderSummary.

export interface OrderSummary {
  id: number
  orderNumber: string
  receiptCode: string
  status: OrderStatus
  refunded: boolean
  total: number
  itemSummary: string
  customerName: string | null
  createdAt: string
}


export const PAGE_SIZE = 20

export const ordersPaths = {
  search: (status: OrderStatus, query: string, page: number) => {
    const params = new URLSearchParams({ status, page: String(page), size: String(PAGE_SIZE) })
    if (query) params.set('q', query)
    return `/api/v1/orders?${params}`
  },
  order: (orderId: number) => `/api/v1/orders/${orderId}`,
}

/** Manager approval at this terminal; returns a short-lived manager token for one action. */
export function requestSpecialAccess(pin: string): Promise<LoginResponse> {
  return postJson<LoginResponse>('/api/v1/auth/special-access', { pin })
}

/** `token`: the approving manager's special-access token, or undefined to use the session. */
export function voidOrder(orderId: number, reason: string, token?: string): Promise<Order> {
  return postJson<Order>(`/api/v1/orders/${orderId}/void`, { reason }, { token })
}

export function refundOrder(orderId: number, reason: string, token?: string): Promise<Order> {
  return postJson<Order>(`/api/v1/orders/${orderId}/refund`, { reason }, { token })
}
