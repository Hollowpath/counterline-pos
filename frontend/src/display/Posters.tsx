import { useEffect, useState } from 'react'

/**
 * Idle-screen posters, full screen while no sale is open on the till.
 *
 * A promotion's poster is shown only while the server says that promotion runs (its key is in `keys`);
 * the wording is here. Without an answer (not yet, failed, expired) only the neutral posters show,
 * which promise no particular reward.
 */
interface Poster {
  title: string
  text: string
  tone: 'teal' | 'berry' | 'amber'
}

const PROMOTION_POSTERS: Record<string, Poster> = {
  BUY_THREE: {
    title: 'Buy 3, get 1 free',
    text: 'Members get a free item for every 3 qualifying items. Not with vouchers or discounts. Terms apply.',
    tone: 'teal',
  },
  SEASONAL: {
    title: 'Seasonal flavours are here',
    text: 'Members get one more free item for every 3 items with a seasonal flavour among them. Not with vouchers or discounts. Terms apply.',
    tone: 'berry',
  },
  MEMBER_POINTS: {
    title: 'Become a member',
    text: 'Members earn points on every purchase and can redeem them for free items. Ask the cashier to join. Terms apply.',
    tone: 'amber',
  },
  BIRTHDAY: {
    title: 'Birthday month treat',
    text: 'Members get a free birthday reward with every 3 qualifying items in their birthday month, up to a limit per receipt. Not with vouchers or discounts. Terms apply.',
    tone: 'berry',
  },
  SPEND_MEMBERS: {
    title: 'Free gift for members',
    text: 'Members get a free gift when a sale reaches the spend needed. Ask the cashier how much. Terms apply.',
    tone: 'teal',
  },
  SPEND_ANYONE: {
    title: 'Free gift with your spend',
    text: 'Spend enough in one sale and take home a free gift, subject to limits. Ask the cashier how much. Terms apply.',
    tone: 'amber',
  },
}

const NEUTRAL_POSTERS: Poster[] = [
  { title: 'Become a member', text: 'Ask the cashier about membership and today’s rewards. Terms apply.', tone: 'amber' },
  { title: 'Welcome', text: 'Thank you for visiting. Ask us about today’s rewards.', tone: 'teal' },
]

/** How long each poster stays before the next slides in. */
export const ROTATE_MS = 10_000

const POSTER_TONE: Record<string, string> = {
  teal: 'bg-linear-to-br from-indigo-600 via-violet-600 to-fuchsia-600',
  berry: 'bg-linear-to-br from-red-500 via-red-700 to-fuchsia-700',
  amber: 'bg-linear-to-br from-orange-400 via-orange-500 to-red-500',
}

const PADDING = 'p-[clamp(2rem,6vw,5rem)]'

/** The server's keys that have a poster, in its order and without repeats; keys not known here are ignored. */
function postersFor(keys: readonly string[] | null): Poster[] {
  const known = [...new Set(keys ?? [])].flatMap((key) => (Object.hasOwn(PROMOTION_POSTERS, key) ? [PROMOTION_POSTERS[key]] : []))
  return known.length > 0 ? known : NEUTRAL_POSTERS
}

/** `keys`: the posters the server allows now (null: no valid answer, so only neutral ones). */
export function Posters({ outletName, keys = null }: { outletName: string; keys?: readonly string[] | null }) {
  const posters = postersFor(keys)
  // A new set of posters starts over from its first, so the one shown is always one of them.
  return <Rotation key={posters.map((poster) => poster.title).join('|')} outletName={outletName} posters={posters} />
}

function Rotation({ outletName, posters }: { outletName: string; posters: Poster[] }) {
  // How many times the posters have moved on: the poster shown is turns % posters.length.
  const [turns, setTurns] = useState(0)

  useEffect(() => {
    if (posters.length < 2) return
    const timer = window.setInterval(() => setTurns((current) => current + 1), ROTATE_MS)
    return () => window.clearInterval(timer)
  }, [posters.length])

  const index = turns % posters.length
  // Each move slides the last poster out to the left as the next comes in from the right.
  const slides = turns === 0
    ? [{ turn: 0, motion: '' }]
    : [{ turn: turns - 1, motion: 'animate-slide-out-left' }, { turn: turns, motion: 'animate-slide-in-right' }]
  return (
    <div className="relative flex-1 overflow-hidden text-white" aria-label="Posters">
      {slides.map(({ turn, motion }) => {
        const poster = posters[turn % posters.length]
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
        {posters.map((entry, i) => (
          <span key={entry.title} className={`h-2 rounded-full transition-all duration-500 ${i === index ? 'w-8 bg-white' : 'w-2 bg-white/40'}`} />
        ))}
      </div>
    </div>
  )
}
