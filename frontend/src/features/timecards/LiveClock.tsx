import { useEffect, useState } from 'react'

const time = new Intl.DateTimeFormat('en-MY', { hour: 'numeric', minute: '2-digit', second: '2-digit', hour12: true })
const day = new Intl.DateTimeFormat('en-MY', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })

/**
 * A digital wall clock, in the terminal's time zone (the outlet's): the time staff clock in or out
 * at, ticking each second.
 */
export function LiveClock() {
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    // Ticks on the second, so it changes when a wall clock would.
    let timer = 0
    const tick = () => {
      const current = new Date()
      setNow(current)
      timer = window.setTimeout(tick, 1000 - current.getMilliseconds())
    }
    timer = window.setTimeout(tick, 1000 - new Date().getMilliseconds())
    return () => window.clearTimeout(timer)
  }, [])

  return (
    <div className="mb-5 rounded-2xl bg-linear-to-br from-zinc-800 to-zinc-900 px-4 py-5 text-center text-white shadow-inner">
      <time className="block text-[length:clamp(2.4rem,9vw,3.4rem)] leading-[1.1] font-bold tracking-tight tabular-nums" dateTime={now.toISOString()} aria-label={`Time now: ${time.format(now)}`}>
        {time.format(now).toUpperCase()}
      </time>
      <p className="mt-1 text-sm font-medium text-zinc-300">{day.format(now)}</p>
    </div>
  )
}
