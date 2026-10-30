// Mirrors the backend's dashboard DTOs.

import type { Outlet } from '../auth/types'

/** Head office (every outlet) or an outlet manager (their own). */
export interface DashboardSession {
  token: string
  expiresAt: string
  name: string
  role: 'ADMIN' | 'MANAGER'
  /** The manager's outlet; null for head office. */
  outlet: Outlet | null
}

export interface RecentOrder {
  orderNumber: string
  paidAt: string
  total: number
  staffName: string
  items: string | null
}

export interface OutletLive {
  outletId: number
  code: string
  name: string
  registerOpen: boolean
  openOrders: number
  orderCount: number
  grossSales: number
  netSales: number
  lastSaleAt: string | null
  recentOrders: RecentOrder[]
}

export interface LiveSales {
  date: string
  orderCount: number
  netSales: number
  outlets: OutletLive[]
  generatedAt: string
}

export interface AdminItem {
  id: number
  name: string
  price: number
  active: boolean
  /** The promotion that makes a promotional item free, if any. */
  promotion: string | null
  /**
   * A seasonal flavour (it earns the seasonal-flavour promotion's free item) from `seasonalFrom`
   * (null: now) to `seasonalUntil`; `seasonalNow`: today is one of those days.
   */
  seasonal: boolean
  seasonalFrom: string | null
  seasonalUntil: string | null
  seasonalNow: boolean
}

export interface AdminCategory {
  id: number
  name: string
  tab: string | null
  /** The cell on the tab's 5 × 5 grid; null off the grid. */
  row: number | null
  col: number | null
  /** Its items count towards "Buy 3" promotions. */
  countsForPromotions: boolean
  items: AdminItem[]
}

export interface AdminTab {
  id: number
  name: string
  sortOrder: number
  folders: number
}

export interface AdminModifierOption {
  id: number
  name: string
  price: number
  active: boolean
}

export interface AdminModifierGroup {
  id: number
  itemId: number
  name: string
  chooseExactly: number
  options: AdminModifierOption[]
}

/** DELETED, or DEACTIVATED when there is sales history to keep. */
export interface Removal {
  outcome: 'DELETED' | 'DEACTIVATED'
}

export interface AdminDiscount {
  id: number
  name: string
  kind: 'PERCENT' | 'AMOUNT'
  value: number
  scope: 'ITEM' | 'ORDER' | 'BOTH'
  requiresApproval: boolean
  /** Can't be given on a member's sale (the staff discount). */
  notForMembers: boolean
  /** A paper voucher keyed in at the till: its sale gives no Buy 3 Free 1, seasonal or birthday free items. */
  countsAsVoucher: boolean
  active: boolean
}

/** An iPad set up as a till. */
export interface TillDevice {
  id: number
  label: string
  outletId: number
  outletName: string
  setUpBy: string
  setUpAt: string
  lastLoginAt: string | null
}

/** A customer display, connected or waiting for its access code. */
export interface CustomerDisplayRow {
  id: number
  outletId: number
  outletName: string
  connected: boolean
  createdAt: string
  connectedAt: string | null
  lastSeenAt: string | null
}

/** An outlet's PIN pads, each locked on its own by too many wrong PINs in a row. */
export type PinPad = 'TILL' | 'CLOCK' | 'SETUP'

/** A PIN pad locked now (mirrors PinLockService.PinLockResponse). */
export interface PinLock {
  outletId: number
  outletName: string
  pad: PinPad
  /** "PIN login", "Clock In/Out" or "iPad set-up". */
  padName: string
  lockedUntil: string
}

/** Headline figures for a period (mirrors SalesOverviewService.Figures). */
export interface SalesFigures {
  netSales: number
  orders: number
  averageOrder: number
  /** Units charged for (free items not counted). */
  itemsSold: number
  /** Orders with a member on them. */
  memberOrders: number
  /** Members who signed up in the period and bought at these outlets in it. */
  newMembers: number
  /** Units the promotions gave free, and what they would have cost. */
  freeItems: number
  freeItemsValue: number
  /** What discounts and vouchers took off. */
  discounts: number
  /** Orders refunded in the period, and what was paid back. */
  refundCount: number
  refunds: number
}

/** A promotion's free items (count: units) or a discount's uses (count: times applied). */
export interface GivenTotal {
  name: string
  count: number
  amount: number
}

/** The dashboard's charts for a period (mirrors SalesOverviewService.Overview). */
export interface SalesOverview {
  from: string
  to: string
  outletId: number | null
  current: SalesFigures
  /** The period just before, as long. */
  previous: SalesFigures
  days: { date: string; orders: number; netSales: number }[]
  outlets: { outletId: number; name: string; orders: number; netSales: number }[]
  /** 0–23, every hour. */
  hours: { hour: number; orders: number; sales: number }[]
  topItems: { name: string; quantity: number; sales: number }[]
  categories: { name: string; amount: number }[]
  paymentMethods: { name: string; amount: number }[]
  promotions: GivenTotal[]
  discounts: GivenTotal[]
  generatedAt: string
}

export const dashboardPaths = {
  login: '/api/v1/dashboard/auth/login',
  outlets: '/api/v1/dashboard/outlets',
  live: '/api/v1/dashboard/live',
  overview: (from: string, to: string, outletId: number | null) =>
    `/api/v1/dashboard/overview?${new URLSearchParams({ from, to, ...(outletId === null ? {} : { outletId: String(outletId) }) })}`,
  report: (outletId: number, name: string, from: string, to: string) =>
    `/api/v1/dashboard/outlets/${outletId}/reports/${name}?${new URLSearchParams({ from, to })}`,
  closeShift: (outletId: number, staffId: number) => `/api/v1/dashboard/outlets/${outletId}/timecards/${staffId}/close`,
  items: '/api/v1/dashboard/catalog/items',
  item: (id: number) => `/api/v1/dashboard/catalog/items/${id}`,
  folders: '/api/v1/dashboard/catalog/folders',
  folder: (id: number) => `/api/v1/dashboard/catalog/folders/${id}`,
  folderItemsOrder: (id: number) => `/api/v1/dashboard/catalog/folders/${id}/items/order`,
  tabs: '/api/v1/dashboard/catalog/tabs',
  tabsOrder: '/api/v1/dashboard/catalog/tabs/order',
  tab: (id: number) => `/api/v1/dashboard/catalog/tabs/${id}`,
  modifierGroups: (itemId: number) => `/api/v1/dashboard/catalog/items/${itemId}/modifier-groups`,
  modifierGroupsOrder: (itemId: number) => `/api/v1/dashboard/catalog/items/${itemId}/modifier-groups/order`,
  modifierGroup: (id: number) => `/api/v1/dashboard/catalog/modifier-groups/${id}`,
  modifierOptions: (groupId: number) => `/api/v1/dashboard/catalog/modifier-groups/${groupId}/options`,
  modifierOptionsOrder: (groupId: number) => `/api/v1/dashboard/catalog/modifier-groups/${groupId}/options/order`,
  modifierOption: (id: number) => `/api/v1/dashboard/catalog/modifier-options/${id}`,
  discounts: '/api/v1/dashboard/discounts',
  discount: (id: number) => `/api/v1/dashboard/discounts/${id}`,
  vouchers: '/api/v1/dashboard/vouchers',
  voucherBatch: (id: number) => `/api/v1/dashboard/vouchers/${id}`,
  points: (customerId: number) => `/api/v1/dashboard/customers/${customerId}/points`,
  owedItems: (customerId: number) => `/api/v1/dashboard/customers/${customerId}/owed-items`,
  promotions: '/api/v1/dashboard/promotions',
  devices: '/api/v1/dashboard/devices',
  deviceReset: (id: number) => `/api/v1/dashboard/devices/${id}/reset`,
  displays: '/api/v1/dashboard/displays',
  displayDisconnect: (id: number) => `/api/v1/dashboard/displays/${id}/disconnect`,
  pinLocks: '/api/v1/dashboard/pin-locks',
  pinUnlock: (outletId: number, pad: PinPad) => `/api/v1/dashboard/pin-locks/${outletId}/${pad}/unlock`,
  adminOutlets: '/api/v1/dashboard/admin/outlets',
  adminOutlet: (id: number) => `/api/v1/dashboard/admin/outlets/${id}`,
  adminOutletAction: (id: number, action: 'deactivate' | 'reactivate') => `/api/v1/dashboard/admin/outlets/${id}/${action}`,
  adminStaff: (outletId: number | null) => `/api/v1/dashboard/admin/staff${outletId === null ? '' : `?outletId=${outletId}`}`,
  adminStaffMember: (id: number) => `/api/v1/dashboard/admin/staff/${id}`,
  adminStaffAction: (id: number, action: 'deactivate' | 'reactivate') => `/api/v1/dashboard/admin/staff/${id}/${action}`,
  adminStaffLogin: (id: number) => `/api/v1/dashboard/admin/staff/${id}/dashboard-login`,
  adminStaffPin: (id: number) => `/api/v1/dashboard/admin/staff/${id}/pin`,
}

/** A result shown in the page's banner: it worked (`ok`) or it did not (`error`). */
export interface Notice {
  tone: 'ok' | 'error'
  text: string
}

/** An outlet as head office manages it, deactivated ones included. */
export interface AdminOutlet {
  id: number
  code: string
  name: string
  address: string | null
  active: boolean
}

/** A staff member as head office manages them: never a PIN, password or hash. `email` is a manager's dashboard login. */
export interface AdminStaff {
  id: number
  fullName: string
  role: 'STAFF' | 'MANAGER'
  outletId: number
  active: boolean
  email: string | null
}
