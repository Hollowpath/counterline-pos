import { useEffect, useRef, useState } from 'react'

/**
 * Counts down from `seconds` while `running`, then calls `onElapsed` once. Returns the seconds left,
 * for showing "Closing in 4s". Restarts whenever `running` turns true again.
 */
export function useCountdown(seconds: number, running: boolean, onElapsed: () => void): number {
  const [left, setLeft] = useState(seconds)
  // The latest callback, so a parent re-render doesn't restart the countdown.
  const onElapsedRef = useRef(onElapsed)
  useEffect(() => {
    onElapsedRef.current = onElapsed
  })

  useEffect(() => {
    if (!running) return
    setLeft(seconds)
    let remaining = seconds
    const timer = window.setInterval(() => {
      remaining -= 1
      setLeft(remaining)
      if (remaining <= 0) {
        window.clearInterval(timer)
        onElapsedRef.current()
      }
    }, 1000)
    return () => window.clearInterval(timer)
  }, [seconds, running])

  return left
}
