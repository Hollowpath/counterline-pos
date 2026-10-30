import { useEffect, useState } from 'react'
import { getJson } from '../api/http'
import { displayPaths, type DisplayPosters } from './types'

/** No answer is shown for longer than this after it arrived, whatever its own expiry says (a skewed clock can't stretch it). */
export const POSTERS_MAX_AGE_MS = 5 * 60_000
/** After a failed ask, how long before the next one. */
export const POSTERS_RETRY_MS = 30_000
/** The next ask goes out this long before the answer expires, so the screen never waits for it. */
export const POSTERS_REFRESH_EARLY_MS = 5_000
/** Never asking faster than this, whatever the answer's expiry. */
const MIN_REFRESH_MS = 5_000

interface Answer {
  keys: string[]
  /** Local time (ms) after which the answer must not be shown. */
  expiresAt: number
}

/**
 * Which promotional posters the server says may be shown, while the display is idle (null: none known,
 * so the posters are neutral). Asked when the display goes idle and again before each answer expires;
 * an answer is dropped when it expires, so a display that can't reach the server stops advertising.
 */
export function useDisplayPosters(idle: boolean): string[] | null {
  const [answer, setAnswer] = useState<Answer | null>(null)

  useEffect(() => {
    if (!answer) return
    const timer = window.setTimeout(() => setAnswer(null), Math.max(0, answer.expiresAt - Date.now()))
    return () => window.clearTimeout(timer)
  }, [answer])

  useEffect(() => {
    if (!idle) return
    const controller = new AbortController()
    let timer: number | undefined

    async function load() {
      let wait = POSTERS_RETRY_MS
      try {
        const next = await getJson<DisplayPosters>(displayPaths.posters, controller.signal)
        // A display that has gone busy (or away) since asking takes nothing from the answer.
        if (controller.signal.aborted) return
        const received = Date.now()
        const validUntil = Date.parse(next.validUntil)
        if (!Array.isArray(next.posters) || Number.isNaN(validUntil)) throw new Error('Unexpected answer')
        const expiresAt = Math.min(validUntil, received + POSTERS_MAX_AGE_MS)
        // An answer that is already out of date is not used (nor is the one before it kept).
        setAnswer(expiresAt > received ? { keys: next.posters.filter((key): key is string => typeof key === 'string'), expiresAt } : null)
        // Just before it expires; an answer with little left is asked for again as it ends.
        const remaining = expiresAt - received
        wait = remaining > 0 ? Math.max(remaining - POSTERS_REFRESH_EARLY_MS, Math.min(remaining, MIN_REFRESH_MS)) : MIN_REFRESH_MS
      } catch {
        if (controller.signal.aborted) return
      }
      timer = window.setTimeout(() => void load(), wait)
    }
    void load()
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [idle])

  return answer?.keys ?? null
}
