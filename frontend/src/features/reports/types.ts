// Mirrors backend ReportDtos.

export interface PaymentMethodTotal {
  method: string
  paymentCount: number
  collected: number
  refunded: number
  net: number
}

export interface StatusTotal {
  status: 'Paid' | 'Refunded' | 'Open' | 'Voided'
  count: number
  amount: number
}

/** Every kiosk sale is Retail with 1 pax; `perPaxAverage` is cut to the sen, as printed. */
export interface DiningOptionTotal {
  name: string
  orderCount: number
  amount: number
  paxCount: number
  perPaxAverage: number
}

export interface SalesSummary {
  from: string
  to: string
  orderCount: number
  grossSales: number
  discounts: number
  refundCount: number
  refunds: number
  netSales: number
  paymentMethods: PaymentMethodTotal[]
  totalCollected: number
  orderStatuses: StatusTotal[]
  diningOptions: DiningOptionTotal[]
}

export interface PaymentMethodsReport {
  methods: PaymentMethodTotal[]
  total: number
}

export interface ItemTotal {
  itemName: string
  quantity: number
  amount: number
}

export interface HourlySales {
  /** `net` = sales − refunds made in that hour. */
  hours: { hour: number; orderCount: number; sales: number; refunds: number; net: number }[]
}

export interface ItemSales {
  items: ItemTotal[]
}

/** `label`: "Unit Price", "Unit Value" (a free promotional item, at its value) or "Sample". */
export interface PriceTotal {
  label: string
  unitPrice: number
  quantity: number
  amount: number
}

export interface CategorySales {
  categories: {
    categoryName: string | null
    quantity: number
    amount: number
    items: (ItemTotal & { prices: PriceTotal[] })[]
  }[]
}

export interface DiscountsReport {
  discounts: { name: string; count: number; amount: number }[]
}

export interface ModifiersReport {
  options: { groupName: string; optionName: string; quantity: number; amount: number }[]
}

export interface LoyaltyPointsReport {
  issued: number
  redeemed: number
  /** Taken back / given back by refunds. */
  issuedReversed: number
  redeemedReturned: number
  redemptions: { name: string; count: number; points: number }[]
}

export interface Timecards {
  entries: { staffName: string; type: 'IN' | 'OUT'; at: string }[]
}

export type ReportName =
  | 'sales-summary'
  | 'hourly-sales'
  | 'payment-methods'
  | 'item-sales'
  | 'category-sales'
  | 'discounts'
  | 'modifiers'
  | 'loyalty-points'
  | 'timecards'

export const reportPath = (name: ReportName, from: string, to: string) =>
  `/api/v1/reports/${name}?${new URLSearchParams({ from, to })}`
