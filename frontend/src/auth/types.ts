// Mirrors backend AuthDtos.

export type StaffRole = 'STAFF' | 'MANAGER'

export interface Staff {
  id: number
  fullName: string
  role: StaffRole
}

export interface Outlet {
  id: number
  code: string
  name: string
  address: string | null
}

export interface LoginResponse {
  token: string
  expiresAt: string
  staff: Staff
  /** The outlet the session records sales for (the terminal's). */
  outlet: Outlet
}

/** The iPad's key (kept, and sent with every login) and label, and its outlet. */
export interface TerminalSetupResponse {
  deviceKey: string
  label: string
  outlet: Outlet
}

export const PIN_LENGTH = 6

export const authPaths = {
  login: '/api/v1/auth/login',
  me: '/api/v1/auth/me',
  outlets: '/api/v1/outlets',
  outlet: (id: number) => `/api/v1/outlets/${id}`,
  terminalSetup: '/api/v1/auth/terminal-setup',
  /** Is the login (by device key) or set-up (by outlet) PIN pad locked? */
  pinLock: '/api/v1/auth/pin-lock',
  /** A logged-in till's own PIN pads: manager approvals (TILL) or Clock In/Out (CLOCK). */
  terminalPinLock: (pad: 'TILL' | 'CLOCK') => `/api/v1/auth/pin-lock/${pad}`,
}

/** The server's answer: seconds until the pad unlocks, 0 when it isn't locked. */
export interface PinLockStatus {
  lockedForSeconds: number
}
