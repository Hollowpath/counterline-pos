import { createContext, useContext } from 'react'
import type { SalesSummary } from '../reports/types'

// Mirrors backend RegisterDtos.

export interface Movement {
  id: number
  direction: 'IN' | 'OUT'
  amount: number
  reason: string
  staffName: string
  createdAt: string
}

export interface Session {
  id: number
  version: number
  openedAt: string
  openedBy: string
  closedAt: string | null
  closedBy: string | null
  closingNote: string | null
  movements: Movement[]
  payIns: number
  payOuts: number
  sales: SalesSummary
}

export interface RegisterStatus {
  open: boolean
  session: Session | null
  lastClosed: Session | null
  /** Placed, unpaid orders: they must be paid or voided before the register can close. */
  openOrders: number
}

export const registerPath = '/api/v1/register'

/**
 * Whether the register is open, for screens that take payment or place orders. `open` is null
 * while unknown (loading or unreachable): the server still refuses both when it is closed.
 * `openOrders`: the outlet's placed, unpaid orders, counted on Past Orders.
 */
export interface RegisterState {
  open: boolean | null
  openOrders: number
  refresh: () => void
}

export const RegisterContext = createContext<RegisterState>({ open: null, openOrders: 0, refresh: () => {} })

export function useRegister(): RegisterState {
  return useContext(RegisterContext)
}

export const REGISTER_CLOSED_MESSAGE = 'Register closed'
