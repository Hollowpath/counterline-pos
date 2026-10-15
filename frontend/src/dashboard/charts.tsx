/**
 * Small, dependency-free charts for the dashboard: drawn with SVG and CSS so they scale from a phone
 * to a desktop. Every chart here shows one series, so it wears one colour (the POS's indigo), and
 * names it in its title rather than a legend. Each has a text alternative: an aria-label, the peak
 * labelled directly, and the rest in a tooltip on pointing or touching (or, for bars, beside them).
 */

import { useState, type PointerEvent, type ReactNode } from 'react'

export interface ChartPoint {
  label: string
  value: number
  /** Shown in the tooltip, e.g. "RM1,240.00 · 38 orders". */
  title: string
}

const TIP_PLACE = { start: 'translate-x-0', middle: '-translate-x-1/2', end: '-translate-x-full' } as const
const tip = 'pointer-events-none absolute rounded-lg bg-zinc-900 px-2.5 py-1 text-xs font-medium whitespace-nowrap text-white shadow-lg'

/** Which point a pointer is over, across the chart's width (so a finger needn't land on a mark). */
function pointAt(event: PointerEvent<HTMLElement>, count: number): number {
  const box = event.currentTarget.getBoundingClientRect()
  const ratio = box.width > 0 ? (event.clientX - box.left) / box.width : 0
  return Math.min(count - 1, Math.max(0, Math.floor(ratio * count)))
}

/** The best point, for its direct label ("Best day: 12 Oct · RM1,240.00"); none when nothing sold. */
function peak(points: ChartPoint[]): ChartPoint | null {
  const best = points.reduce<ChartPoint | null>((top, point) => (top === null || point.value > top.value ? point : top), null)
  return best && best.value > 0 ? best : null
}

/** A chart's caption under it: the peak, labelled directly. */
function Peak({ label, point }: { label: string; point: ChartPoint | null }) {
  if (!point) return null
  return (
    <p className="mt-3 text-xs text-zinc-500">
      {label}: <strong className="font-semibold text-zinc-800">{point.label}</strong> · {point.title}
    </p>
  )
}

/** A trend over time: an area under a line, with the first, middle and last dates below. Pointing
 * at (or touching) the chart marks the nearest day and shows its figures. */
export function TrendChart({ points, label, format, peakLabel }: {
  points: ChartPoint[]
  label: string
  format: (value: number) => string
  /** The caption naming the best point, e.g. "Best day". */
  peakLabel: string
}) {
  const [active, setActive] = useState<number | null>(null)
  const width = 100
  const height = 40
  const max = Math.max(...points.map((point) => point.value), 0)
  const top = max > 0 ? max * 1.1 : 1
  const x = (index: number) => (points.length === 1 ? width / 2 : (index / (points.length - 1)) * width)
  const y = (value: number) => height - (Math.max(value, 0) / top) * height
  const line = points.map((point, index) => `${x(index)},${y(point.value)}`).join(' ')
  const area = `0,${height} ${line} ${width},${height}`
  const ticks = points.length <= 2 ? points.map((_, index) => index)
    : [0, Math.floor((points.length - 1) / 2), points.length - 1]

  const pick = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    const ratio = box.width > 0 ? (event.clientX - box.left) / box.width : 0
    setActive(Math.min(points.length - 1, Math.max(0, Math.round(ratio * (points.length - 1)))))
  }
  const shown = active === null ? null : points[active]
  const left = active === null ? 0 : x(active)
  // The label sits over the day, kept inside the chart at either end.
  const align = left < 20 ? 'start' : left > 80 ? 'end' : 'middle'

  return (
    <figure className="m-0" aria-label={label}>
      <div className="relative h-44 pt-[1.2rem]">
        <span className="absolute top-0 left-0 text-xs text-zinc-500 tabular-nums">{format(max)}</span>
        <div className="relative h-full cursor-crosshair touch-pan-y border-b border-zinc-200" onPointerMove={pick} onPointerDown={pick}
          onPointerLeave={() => setActive(null)}>
          <svg className="block size-full overflow-visible" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none"
            role="img" aria-label={label}>
            <polygon points={area} className="fill-indigo-500/10" />
            <polyline points={line} className="fill-none stroke-indigo-600 stroke-2 [stroke-linejoin:round]"
              vectorEffect="non-scaling-stroke" />
          </svg>
          {shown && active !== null && (
            <>
              <span className="pointer-events-none absolute inset-y-0 w-px bg-zinc-300" style={{ left: `${left}%` }} />
              <span
                className="pointer-events-none absolute size-3 -translate-1/2 rounded-full border-2 border-white bg-indigo-600 shadow-[0_0_0_4px_--alpha(var(--color-indigo-500)/25%)]"
                style={{ left: `${left}%`, top: `${(y(shown.value) / height) * 100}%` }}
              />
              <span className={`${tip} -top-[1.6rem] ${TIP_PLACE[align]}`} style={{ left: `${left}%` }} role="status">
                <strong>{shown.label}</strong> {shown.title}
              </span>
            </>
          )}
        </div>
      </div>
      <div className="mt-[0.35rem] flex justify-between text-xs text-zinc-500">
        {ticks.map((index) => (
          <span key={index}>{points[index].label}</span>
        ))}
      </div>
      <Peak label={peakLabel} point={peak(points)} />
    </figure>
  )
}

/** Columns, e.g. sales by hour. Every `labelEvery`-th label is shown under its column; pointing at
 * (or touching) a column shows its figures. */
export function ColumnChart({ points, label, format, peakLabel, labelEvery = 1 }: {
  points: ChartPoint[]
  label: string
  format: (value: number) => string
  /** The caption naming the best column, e.g. "Busiest". */
  peakLabel: string
  labelEvery?: number
}) {
  const [active, setActive] = useState<number | null>(null)
  const max = Math.max(...points.map((point) => point.value), 0)
  const shown = active === null ? null : points[active]
  const centre = active === null ? 0 : ((active + 0.5) / points.length) * 100
  const align = centre < 20 ? 'start' : centre > 80 ? 'end' : 'middle'
  return (
    <figure className="m-0" aria-label={label}>
      <div className="relative pt-[1.2rem]">
        <span className="absolute top-0 left-0 text-xs text-zinc-500 tabular-nums">{format(max)}</span>
        <div
          className="relative flex h-36 cursor-crosshair touch-pan-y items-end gap-0.5 border-b border-zinc-200"
          role="img"
          aria-label={label}
          onPointerMove={(event) => setActive(pointAt(event, points.length))}
          onPointerDown={(event) => setActive(pointAt(event, points.length))}
          onPointerLeave={() => setActive(null)}
        >
          {points.map((point, index) => (
            <span key={point.label} className="flex h-full flex-1 items-end">
              <span
                className={`min-h-px w-full rounded-t transition-colors ${index === active ? 'bg-indigo-800' : 'bg-indigo-600'}`}
                style={{ height: `${max > 0 ? (point.value / max) * 100 : 0}%` }}
              />
            </span>
          ))}
          {shown && (
            <span className={`${tip} -top-[1.6rem] ${TIP_PLACE[align]}`} style={{ left: `${centre}%` }} role="status">
              <strong>{shown.label}</strong> {shown.title}
            </span>
          )}
        </div>
      </div>
      <div className="mt-1.5 flex gap-0.5 text-[0.7rem] text-zinc-500">
        {points.map((point, index) => (
          <span key={point.label} className="flex-1 overflow-visible whitespace-nowrap">{index % labelEvery === 0 ? point.label : ''}</span>
        ))}
      </div>
      <Peak label={peakLabel} point={peak(points)} />
    </figure>
  )
}

export interface BarRow {
  label: string
  value: number
  /** The value as shown, e.g. "RM1,240.00". */
  shown: ReactNode
  /** Under the label, e.g. "38 orders". */
  sub?: string
}

/** Horizontal bars with their labels and values beside them: outlets, top items, folders. */
export function BarList({ rows, label, empty = 'No sales yet.' }: { rows: BarRow[]; label: string; empty?: string }) {
  const max = Math.max(...rows.map((row) => row.value), 0)
  if (rows.length === 0) return <p className="text-sm text-zinc-500">{empty}</p>
  return (
    <ul className="grid list-none gap-3" aria-label={label}>
      {rows.map((row) => (
        <li key={row.label} className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1.5 text-sm">
          <span className="truncate font-medium text-zinc-700">
            {row.label}
            {row.sub && <span className="ml-[0.4rem] text-xs text-zinc-500">{row.sub}</span>}
          </span>
          <span className="font-semibold text-zinc-900 tabular-nums">{row.shown}</span>
          <span className="col-span-full h-2 rounded-full bg-zinc-100" aria-hidden="true">
            <span className="block h-full min-w-[2px] rounded-full bg-indigo-600" style={{ width: `${max > 0 ? (row.value / max) * 100 : 0}%` }} />
          </span>
        </li>
      ))}
    </ul>
  )
}
