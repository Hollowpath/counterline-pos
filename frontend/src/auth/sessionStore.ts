import type { TerminalOutlet } from './terminalStore'
import type { Staff } from './types'

export interface StoredSession {
  token: string
  expiresAt: string
  staff: Staff
  outlet: TerminalOutlet
}

const KEY = 'counterline.session'

/*
 * sessionStorage: survives a page reload but not closing the tab, and is not sent with requests
 * (unlike a cookie). Storage can be unavailable (private mode, blocked site data), so every
 * access is guarded and the app simply falls back to logging in again.
 */

export function loadSession(now: Date = new Date()): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const session = JSON.parse(raw) as StoredSession
    // A session from before outlets existed has none: log in again.
    return new Date(session.expiresAt) > now && session.outlet ? session : null
  } catch {
    return null
  }
}

export function saveSession(session: StoredSession) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(session))
  } catch {
    // Session just won't survive a reload.
  }
}

export function clearSession() {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // Nothing stored.
  }
}
