import { useEffect, useRef, useState } from 'react'

/**
 * Counts down from `seconds` while `running`, then calls `onElapsed` once. Returns the seconds left,
 * for showing "Closing in 4s". Restarts whenever `running` turns true again, or `restartKey` changes.
 */
export function useCountdown(seconds: number, running: boolean, onElapsed: () => void, restartKey = 0): number {
  // The count belongs to the duration it was started for: a new duration shows in full on the very
  // render that receives it, not one render later when the effect restarts the timer.
  const [count, setCount] = useState({ seconds, restartKey, left: seconds })
  // The latest callback, so a parent re-render doesn't restart the countdown.
  const onElapsedRef = useRef(onElapsed)
  useEffect(() => {
    onElapsedRef.current = onElapsed
  })

  useEffect(() => {
    if (!running) return
    setCount({ seconds, restartKey, left: seconds })
    let remaining = seconds
    const timer = window.setInterval(() => {
      remaining -= 1
      setCount({ seconds, restartKey, left: remaining })
      if (remaining <= 0) {
        window.clearInterval(timer)
        onElapsedRef.current()
      }
    }, 1000)
    return () => window.clearInterval(timer)
  }, [seconds, running, restartKey])

  return count.seconds === seconds && count.restartKey === restartKey ? count.left : seconds
}
