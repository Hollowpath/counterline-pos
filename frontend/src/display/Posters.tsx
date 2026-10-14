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

const ROTATE_MS = 6000

const POSTER_TONE: Record<string, string> = {
  teal: 'bg-linear-to-br from-indigo-600 via-violet-600 to-fuchsia-600',
  berry: 'bg-linear-to-br from-red-500 via-red-700 to-fuchsia-700',
  amber: 'bg-linear-to-br from-orange-400 via-orange-500 to-red-500',
}

export function Posters({ outletName }: { outletName: string }) {
  const [index, setIndex] = useState(0)

  useEffect(() => {
    const timer = window.setInterval(() => setIndex((current) => (current + 1) % POSTERS.length), ROTATE_MS)
    return () => window.clearInterval(timer)
  }, [])

  const poster = POSTERS[index]
  return (
    <div
      className={`relative flex flex-1 flex-col justify-center gap-5 overflow-hidden p-[clamp(2rem,6vw,5rem)] text-white transition-[background] duration-[600ms] ${
        POSTER_TONE[poster.tone]}`}
      aria-label="Posters">
      <span className="pointer-events-none absolute -top-24 -right-24 size-96 rounded-full bg-white/10 blur-2xl" aria-hidden="true" />
      <span className="pointer-events-none absolute -bottom-32 -left-16 size-80 rounded-full bg-black/10 blur-3xl" aria-hidden="true" />
      <p className="relative w-fit rounded-full bg-white/15 px-4 py-1.5 text-sm font-semibold tracking-wider uppercase ring-1 ring-white/25 backdrop-blur">{outletName}</p>
      <h2 className="relative text-[length:clamp(2.5rem,7vw,5rem)] leading-[1.02] font-black tracking-tight drop-shadow-sm">{poster.title}</h2>
      <p className="relative max-w-[40rem] text-[length:clamp(1.2rem,2.5vw,1.8rem)] font-medium text-white/90">{poster.text}</p>
      <div className="relative mt-4 flex gap-2" aria-hidden="true">
        {POSTERS.map((entry, i) => (
          <span key={entry.title} className={`h-2 rounded-full transition-all duration-500 ${i === index ? 'w-8 bg-white' : 'w-2 bg-white/40'}`} />
        ))}
      </div>
    </div>
  )
}
