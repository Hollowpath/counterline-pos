import { useEffect, useRef, useState } from 'react'
import type { Lockout } from '../api/http'
import { PIN_LENGTH } from './types'

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'clear', '0'] as const
type Key = (typeof KEYS)[number]

/** While locked, how often the pad asks whether a manager has unlocked it. */
const LOCK_CHECK_MS = 5000

/** "4:05" */
function minutesAndSeconds(totalSeconds: number): string {
  const seconds = Math.max(0, totalSeconds)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

interface PinEntryProps {
  /**
   * Called once the sixth digit is entered. Resolve to an error message to show it and clear the
   * PIN, or to a lock-out to count it down; resolve to nothing on success (the caller usually
   * unmounts this component).
   */
  onSubmit: (pin: string) => Promise<string | Lockout | void>
  /** Shown when there is no error, e.g. "Your session has ended". */
  notice?: string
  /**
   * Asks the server how many seconds this pad is still locked for (0: not locked). Checked on
   * opening (a lock outlives a reload) and every few seconds while locked, so a manager's unlock on
   * the dashboard frees the pad at once.
   */
  lockStatus?: () => Promise<number>
  /** The customer display's pairing screen: the whole keypad fits the screen, landscape or portrait. */
  fitScreen?: boolean
}

/**
 * Six PIN dots and a keypad, with hardware-keyboard support. While the pad
 * is locked (too many wrong PINs in a row), the keys do nothing and the time left counts down.
 */
export function PinEntry({ onSubmit, notice, lockStatus, fitScreen = false }: PinEntryProps) {
  const [pin, setPin] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [locked, setLocked] = useState<{ until: number } | null>(null)
  const [now, setNow] = useState(() => Date.now())

  // The latest check, so a parent re-render doesn't restart the polling.
  const lockStatusRef = useRef(lockStatus)
  useEffect(() => {
    lockStatusRef.current = lockStatus
  })

  /** Locks the pad for `seconds` (a manager can unlock the outlets' pads), or frees it at 0. */
  function lockFor(seconds: number) {
    const time = Date.now()
    setNow(time)
    setLocked(seconds > 0 ? { until: time + seconds * 1000 } : null)
  }

  // A lock from before this screen opened (e.g. the page was reloaded).
  useEffect(() => {
    let current = true
    lockStatusRef.current?.()
      .then((seconds) => {
        if (current && seconds > 0) lockFor(seconds)
      })
      .catch(() => {})
    return () => {
      current = false
    }
  }, [])

  const isLocked = locked !== null
  useEffect(() => {
    if (!isLocked) return
    const tick = window.setInterval(() => {
      const time = Date.now()
      setNow(time)
      setLocked((current) => (current && time >= current.until ? null : current))
    }, 250)
    // Unlocked on the dashboard: free the pad without waiting out the time.
    const check = window.setInterval(() => {
      lockStatusRef.current?.()
        .then((seconds) => {
          if (seconds === 0) setLocked(null)
        })
        .catch(() => {})
    }, LOCK_CHECK_MS)
    return () => {
      window.clearInterval(tick)
      window.clearInterval(check)
    }
  }, [isLocked])

  async function submit(fullPin: string) {
    setSubmitting(true)
    const failure = await onSubmit(fullPin)
    if (failure) {
      if (typeof failure === 'string') {
        setError(failure)
        setLocked(null)
      } else {
        setError(null)
        lockFor(failure.seconds)
      }
      setPin('')
      setSubmitting(false)
    }
  }

  function press(key: Key) {
    if (submitting || locked) return
    if (key === 'clear') {
      setPin('')
      return
    }
    if (pin.length >= PIN_LENGTH) return
    const next = pin + key
    setError(null)
    setPin(next)
    if (next.length === PIN_LENGTH) void submit(next)
  }

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (/^\d$/.test(event.key)) press(event.key as Key)
      else if (event.key === 'Escape') press('clear')
      else if (event.key === 'Backspace' && !submitting && !locked) setPin((current) => current.slice(0, -1))
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  })

  // On the display's pairing screen the message is grey and never wider than the screen's text.
  const message = `min-h-6 text-center text-sm ${fitScreen ? 'my-2 max-w-[26rem] text-zinc-500' : 'my-4 font-medium text-red-600'}`
  return (
    <div className={`flex flex-col items-center ${fitScreen ? 'w-[min(22rem,100%)]' : 'w-full'}`}>
      <div className="flex gap-3.5 rounded-full bg-zinc-100 px-5 py-3 ring-1 ring-zinc-200/80 ring-inset" role="img" aria-label={`${pin.length} of ${PIN_LENGTH} digits entered`}>
        {Array.from({ length: PIN_LENGTH }, (_, index) => (
          <span key={index} className={`size-3.5 rounded-full transition duration-150 ${index < pin.length ? 'scale-110 bg-indigo-600 shadow-[0_0_0_4px_var(--color-indigo-100)]' : 'bg-zinc-300'}`} />
        ))}
      </div>

      {locked ? (
        // A timer, not an alert: it changes every second.
        <p className={message} role="timer">
          Locked · <strong className="tabular-nums">{minutesAndSeconds(Math.ceil((locked.until - now) / 1000))}</strong>
        </p>
      ) : (
        <p className={message} role="alert">
          {error ?? notice ?? ''}
        </p>
      )}

      <div className="grid w-full grid-cols-3 gap-3 max-md:gap-2.5">
        {KEYS.map((key) => (
          <button
            key={key}
            type="button"
            className={[
              'rounded-2xl font-semibold text-zinc-800 tabular-nums transition duration-100 select-none',
              'enabled:hover:bg-zinc-100 enabled:active:scale-95 enabled:active:bg-indigo-50 enabled:active:text-indigo-700',
              fitScreen ? 'h-[min(4.5rem,calc((100dvh-12rem)/4))]' : 'aspect-[4/3]',
              key === 'clear'
                ? 'text-sm text-zinc-500 uppercase tracking-wider'
                : 'bg-white text-[1.65rem] shadow-sm ring-1 ring-zinc-200 ring-inset',
            ].join(' ')}
            disabled={submitting || isLocked}
            onClick={() => press(key)}
          >
            {key === 'clear' ? 'Clear' : key}
          </button>
        ))}
      </div>
    </div>
  )
}
