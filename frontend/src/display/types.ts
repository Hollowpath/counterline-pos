// Mirrors backend DisplayDtos (the display's side).

export interface CartLineView {
  quantity: number
  name: string
  detail: string | null
  /** Before discounts and free items. */
  price: number
  /** What is charged. */
  total: number
  /** A promotional or sample item. */
  free: boolean
}

/**
 * The POS's cart as the customer sees it (never the member's points: the screen faces the shop).
 * IDLE: no sale open on the till, so the display shows its posters.
 */
export interface CartView {
  stage: 'IDLE' | 'CART' | 'THANK_YOU'
  lines: CartLineView[]
  itemCount: number
  subtotal: number
  discount: number
  total: number
  /** First name of the member on the sale. */
  memberName: string | null
}

/** WAITING: logged in or signed up here, not on the sale yet; CONFIRMED: the till added them. */
export type MemberStatus = 'NONE' | 'WAITING' | 'CONFIRMED'

export interface DisplayState {
  version: number
  outletName: string
  cart: CartView | null
  member: { status: MemberStatus; firstName: string | null }
}

export interface DisplayConnection {
  token: string
  expiresAt: string
  displayId: number
  outletName: string
}

export interface SignUpRequest {
  phone: string
  firstName: string
  lastName: string
  email: string | null
  gender: 'MALE' | 'FEMALE' | null
  dateOfBirth: string | null
  marketingEmail: boolean
  marketingSms: boolean
  marketingCall: boolean
}

export const displayPaths = {
  connect: '/api/v1/display/connect',
  state: (since: number) => `/api/v1/display/state?since=${since}`,
  memberLogin: '/api/v1/display/member-login',
  members: '/api/v1/display/members',
  member: '/api/v1/display/member',
}
