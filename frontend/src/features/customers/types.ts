// Mirrors backend CustomerDtos and OrderDtos.CustomerAnalytics.

export type Gender = 'MALE' | 'FEMALE'
export type SearchField = 'REFERENCE' | 'NAME' | 'PHONE' | 'EMAIL'

export interface CustomerFields {
  referenceId: string | null
  firstName: string
  lastName: string | null
  companyName: string | null
  dateOfBirth: string | null
  gender: Gender | null
  email: string | null
  phone: string | null
  marketingEmail: boolean
  marketingSms: boolean
  marketingCall: boolean
  streetAddress: string | null
  aptSuite: string | null
  city: string | null
  postalCode: string | null
  country: string | null
  notes: string | null
}

export interface Customer extends CustomerFields {
  id: number
  version: number
  fullName: string
  createdAt: string
  updatedAt: string
  /** False once a manager has deactivated the member (absent: active). */
  active?: boolean
}

export interface CustomerSummary {
  id: number
  fullName: string
  phone: string | null
  email: string | null
  referenceId: string | null
  /** False for a deactivated member, listed only on the dashboard (absent: active). */
  active?: boolean
}

export interface CustomerAnalytics {
  orderCount: number
  orderTotal: number
  orderAverage: number
  lastOrderAt: string | null
  mostOrderedItems: { itemName: string; quantity: number }[]
}

/** A till finds someone, it does not list everyone: this much is needed before it asks (as the server requires). */
export const MIN_SEARCH_CHARS = 3
export const MIN_SEARCH_PHONE_DIGITS = 4

export function searchIsMeaningful(field: SearchField, query: string): boolean {
  if (field === 'REFERENCE') return query.length > 0
  if (field === 'PHONE') return query.replace(/\D/g, '').length >= MIN_SEARCH_PHONE_DIGITS
  return query.length >= MIN_SEARCH_CHARS
}

export const customerPaths = {
  search: (field: SearchField, query: string, page: number) => {
    const params = new URLSearchParams({ field, page: String(page), size: '20' })
    if (query) params.set('q', query)
    return `/api/v1/customers?${params}`
  },
  customer: (id: number) => `/api/v1/customers/${id}`,
  deactivate: (id: number) => `/api/v1/customers/${id}/deactivate`,
  reactivate: (id: number) => `/api/v1/customers/${id}/reactivate`,
  analytics: (id: number) => `/api/v1/customers/${id}/analytics`,
}
