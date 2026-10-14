import { useEffect, useState } from 'react'
import { ApiError, getJson } from '../api/http'
import { displayPaths, type DisplayState } from './types'

/** How often the display asks for changes; an unchanged answer is an empty 204. */
export const POLL_MS = 1000

/**
 * The cart and member status, polled from the server. `onDisconnected` is called when the
 * display's token is no longer accepted (the POS disconnected it).
 */
export function useDisplayState(onDisconnected: () => void): DisplayState | null {
  const [state, setState] = useState<DisplayState | null>(null)

  useEffect(() => {
    let version = -1
    let timer: number | undefined
    let stopped = false

    async function poll() {
      try {
        const next = await getJson<DisplayState | undefined>(displayPaths.state(version))
        if (stopped) return
        if (next) {
          version = next.version
          setState(next)
        }
      } catch (error) {
        if (stopped) return
        if (error instanceof ApiError && error.status === 401) {
          onDisconnected()
          return
        }
        // Network hiccup: keep showing what we have and try again.
      }
      timer = window.setTimeout(poll, POLL_MS)
    }
    void poll()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [onDisconnected])

  return state
}
