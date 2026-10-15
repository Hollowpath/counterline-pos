import { useEffect, useState } from 'react'

/**
 * Idle-screen posters, full screen while no sale is open on the till.
 */
const POSTERS = [
  { title: 'Buy 3, get 1 free', text: 'Any 3 stand pouches, paper cans or gift boxes earn a free baby can.', tone: 'teal' },
  { title: 'Seasonal flavours are here', text: 'Pick a seasonal flavour among your 3 and get one more free baby can.', tone: 'berry' },
  { title: 'Become a member', text: 'Members earn rewards with every purchase: a free paper can or crispy pouch along the way.', tone: 'amber' },
  { title: 'Birthday month treat', text: 'Members get a free birthday can with every 3 items in their birthday month.', tone: 'berry' },
  { title: 'Spend RM150, get a tote bag', text: 'Members get a free tote bag for every RM150. Spend RM50 for a free recycle bag.', tone: 'teal' },
]

/** How long each poster stays before the next slides in. */
export const ROTATE_MS = 10_000

const POSTER_TONE: Record<string, string> = {
  teal: 'bg-linear-to-br from-indigo-600 via-violet-600 to-fuchsia-600',
  berry: 'bg-linear-to-br from-red-500 via-red-700 to-fuchsia-700',
  amber: 'bg-linear-to-br from-orange-400 via-orange-500 to-red-500',
}

const PADDING = 'p-[clamp(2rem,6vw,5rem)]'

export function Posters({ outletName }: { outletName: string }) {
  // How many times the posters have moved on: the poster shown is turns % POSTERS.length.
  const [turns, setTurns] = useState(0)

  useEffect(() => {
    const timer = window.setInterval(() => setTurns((current) => current + 1), ROTATE_MS)
    return () => window.clearInterval(timer)
  }, [])

  const index = turns % POSTERS.length
  // Each move slides the last poster out to the left as the next comes in from the right.
  const slides = turns === 0
    ? [{ turn: 0, motion: '' }]
    : [{ turn: turns - 1, motion: 'animate-slide-out-left' }, { turn: turns, motion: 'animate-slide-in-right' }]
  return (
    <div className="relative flex-1 overflow-hidden text-white" aria-label="Posters">
      {slides.map(({ turn, motion }) => {
        const poster = POSTERS[turn % POSTERS.length]
        const leaving = turn !== turns
        return (
          <div
            key={turn}
            className={`absolute inset-0 flex flex-col justify-center gap-5 overflow-hidden ${PADDING} ${POSTER_TONE[poster.tone]} ${motion}`}
            aria-hidden={leaving || undefined}
          >
            <span className="pointer-events-none absolute -top-24 -right-24 size-96 rounded-full bg-white/10 blur-2xl" aria-hidden="true" />
            <span className="pointer-events-none absolute -bottom-32 -left-16 size-80 rounded-full bg-black/10 blur-3xl" aria-hidden="true" />
            <p className="relative w-fit rounded-full bg-white/15 px-4 py-1.5 text-sm font-semibold tracking-wider uppercase ring-1 ring-white/25 backdrop-blur">{outletName}</p>
            <h2 className="relative text-[length:clamp(2.5rem,7vw,5rem)] leading-[1.02] font-black tracking-tight drop-shadow-sm">{poster.title}</h2>
            <p className="relative max-w-[40rem] text-[length:clamp(1.2rem,2.5vw,1.8rem)] font-medium text-white/90">{poster.text}</p>
          </div>
        )
      })}
      {/* The dots stay put at the bottom while the posters slide. */}
      <div className="pointer-events-none absolute bottom-[clamp(1.5rem,4vw,3rem)] left-[clamp(2rem,6vw,5rem)] flex gap-2" aria-hidden="true">
        {POSTERS.map((entry, i) => (
          <span key={entry.title} className={`h-2 rounded-full transition-all duration-500 ${i === index ? 'w-8 bg-white' : 'w-2 bg-white/40'}`} />
        ))}
      </div>
    </div>
  )
}
