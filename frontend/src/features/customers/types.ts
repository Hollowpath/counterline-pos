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
}

export interface CustomerSummary {
  id: number
  fullName: string
  phone: string | null
  email: string | null
  referenceId: string | null
}

export interface CustomerAnalytics {
  orderCount: number
  orderTotal: number
  orderAverage: number
  lastOrderAt: string | null
  mostOrderedItems: { itemName: string; quantity: number }[]
}

export const customerPaths = {
  search: (field: SearchField, query: string, page: number) => {
    const params = new URLSearchParams({ field, page: String(page), size: '20' })
    if (query) params.set('q', query)
    return `/api/v1/customers?${params}`
  },
  customer: (id: number) => `/api/v1/customers/${id}`,
  analytics: (id: number) => `/api/v1/customers/${id}/analytics`,
}
