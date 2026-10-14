// The POS's side of its customer display (mirrors backend DisplayDtos).

/** Someone who logged in or signed up on the display, for the till to add to the sale. */
export interface WaitingMember {
  customerId: number
  fullName: string
  phone: string | null
  newMember: boolean
}

export interface DisplayStatus {
  id: number
  /** Shown until a display connects with it. */
  accessCode: string | null
  connected: boolean
  displaySeenAt: string | null
  waitingMember: WaitingMember | null
}

export const displayPaths = {
  displays: '/api/v1/displays',
  display: (id: number) => `/api/v1/displays/${id}`,
  cart: (id: number) => `/api/v1/displays/${id}/cart`,
  confirm: (id: number) => `/api/v1/displays/${id}/member/confirm`,
}

const KEY = 'counterline.customerDisplay'

/** The display paired with this iPad (one per till), kept across restarts. */
export function loadDisplayId(): number | null {
  try {
    const raw = localStorage.getItem(KEY)
    const id = raw === null ? NaN : Number(raw)
    return Number.isInteger(id) && id > 0 ? id : null
  } catch {
    return null
  }
}

export function saveDisplayId(id: number | null) {
  try {
    if (id === null) localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, String(id))
  } catch {
    // The till will offer to set up a display again.
  }
}
