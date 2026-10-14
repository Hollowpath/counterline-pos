import { useState } from 'react'
import { formatRM } from '../../lib/money'

const MAX_SEN = 99_999_999 // RM999,999.99, within the server's limit

/**
 * The keypad tab: a custom amount typed cash-register style (1, 2, 5, 0 → RM12.50);
 * "+" adds it to the sale as a "Custom Amount" line.
 */
const padKey = 'min-h-15 rounded-2xl bg-white text-[1.4rem] font-semibold text-zinc-800 shadow-sm ring-1 ring-zinc-900/5 transition tabular-nums hover:bg-zinc-50 active:scale-95 active:bg-indigo-50'

export function CustomAmountPad({ onAdd }: { onAdd: (amountSen: number) => void }) {
  const [sen, setSen] = useState(0)

  function press(digits: string) {
    const next = Number(`${sen}${digits}`)
    if (next <= MAX_SEN) setSen(next)
  }

  return (
    <div className="mx-auto grid max-w-[28rem] grid-cols-[minmax(0,1fr)] gap-3 rounded-3xl bg-white/60 p-4 shadow-sm ring-1 ring-zinc-900/5 max-md:p-3">
      <output className="rounded-2xl bg-zinc-100 px-5 py-4 text-right text-[2.1rem] font-bold tracking-tight text-zinc-900 tabular-nums shadow-inner ring-1 ring-zinc-200 ring-inset" aria-label="Amount entered">
        {formatRM(sen)}
      </output>
      <div className="grid grid-cols-4 gap-2">
        {['1', '2', '3', '4', '5', '6', '7', '8', '9', '00', '0'].map((key) => (
          <button key={key} type="button" className={padKey} onClick={() => press(key)}>
            {key}
          </button>
        ))}
        <button type="button" className={padKey} aria-label="Delete digit" onClick={() => setSen(Math.floor(sen / 10))}>
          Del
        </button>
        <button
          type="button"
          className="min-h-15 rounded-2xl bg-linear-to-b from-indigo-500 to-indigo-700 text-[2.2rem] font-light text-white shadow-lg shadow-indigo-600/30 transition [grid-column:4] [grid-row:1/span_4] enabled:hover:brightness-110 enabled:active:scale-[0.97]"
          aria-label="Add custom amount"
          disabled={sen === 0}
          onClick={() => {
            onAdd(sen)
            setSen(0)
          }}
        >
          +
        </button>
      </div>
    </div>
  )
}
