import type { DisplayConnection } from './types'

const KEY = 'counterline.display'

/*
 * The display's pairing outlives closing the app (localStorage). If storage is unavailable, the
 * display simply asks for an access code again.
 */
export function loadConnection(now: Date = new Date()): DisplayConnection | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const connection = JSON.parse(raw) as DisplayConnection
    return new Date(connection.expiresAt) > now ? connection : null
  } catch {
    return null
  }
}

export function saveConnection(connection: DisplayConnection) {
  try {
    localStorage.setItem(KEY, JSON.stringify(connection))
  } catch {
    // Pairing just won't survive a reload.
  }
}

export function clearConnection() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // Nothing stored.
  }
}
