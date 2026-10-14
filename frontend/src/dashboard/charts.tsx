/**
 * Small, dependency-free charts for the dashboard: drawn with SVG and CSS so they scale from a phone
 * to a desktop. Each has a text alternative (aria-label, and the values in tooltips or beside it).
 */

import { useState, type PointerEvent } from 'react'

/** The charts' colours, in order: the POS's teal first. */
const CHART_COLOURS = ['#4f46e5', '#d946ef', '#f97316', '#22c55e', '#0ea5e9', '#ef4444', '#8b5cf6', '#71717a']

export interface ChartPoint {
  label: string
  value: number
  /** Shown in the tooltip, e.g. "RM1,240.00 · 38 orders". */
  title: string
}

const TIP_PLACE = { start: 'translate-x-0', middle: '-translate-x-1/2', end: '-translate-x-full' } as const

/** A trend over time: an area under a line, with the first, middle and last dates below. Pointing
 * at (or touching) the chart marks the nearest day and shows its figures. */
export function TrendChart({ points, label, format }: { points: ChartPoint[]; label: string; format: (value: number) => string }) {
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
        <span className="absolute top-0 left-0 text-xs text-zinc-500">{format(max)}</span>
        <div className="relative h-full cursor-crosshair touch-pan-y" onPointerMove={pick} onPointerDown={pick}
          onPointerLeave={() => setActive(null)}>
          <svg className="block size-full overflow-visible" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none"
            role="img" aria-label={label}>
            <polygon points={area} className="fill-indigo-500/12" />
            <polyline points={line} className="fill-none stroke-indigo-600 stroke-[2.5] [stroke-linejoin:round]"
              vectorEffect="non-scaling-stroke" />
          </svg>
          {shown && active !== null && (
            <>
              <span className="pointer-events-none absolute inset-y-0 w-0 border-l border-dashed border-zinc-300" style={{ left: `${left}%` }} />
              <span
                className="pointer-events-none absolute size-3 -translate-1/2 rounded-full border-2 border-white bg-indigo-600 shadow-[0_0_0_4px_--alpha(var(--color-indigo-500)/25%)]"
                style={{ left: `${left}%`, top: `${(y(shown.value) / height) * 100}%` }}
              />
              <span
                className={`pointer-events-none absolute -top-[1.6rem] rounded-lg bg-zinc-900 px-2.5 py-1 text-xs font-medium whitespace-nowrap text-white shadow-lg ${TIP_PLACE[align]}`}
                style={{ left: `${left}%` }}
                role="status"
              >
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
    </figure>
  )
}

/** Columns, e.g. sales by hour. Every `labelEvery`-th label is shown under its column. */
export function ColumnChart({ points, label, labelEvery = 1 }: { points: ChartPoint[]; label: string; labelEvery?: number }) {
  const max = Math.max(...points.map((point) => point.value), 0)
  return (
    <figure className="m-0" role="img" aria-label={label}>
      <div className="flex h-36 items-end gap-1 border-b border-zinc-200">
        {points.map((point) => (
          <div key={point.label} className="group flex h-full flex-1 items-end" title={`${point.label}: ${point.title}`}>
            <span className="min-h-px w-full rounded-t-md bg-linear-to-t from-indigo-600 to-violet-400 transition group-hover:from-fuchsia-600 group-hover:to-fuchsia-400"
              style={{ height: `${max > 0 ? (point.value / max) * 100 : 0}%` }} />
          </div>
        ))}
      </div>
      <div className="mt-1.5 flex gap-1 text-[0.7rem] text-zinc-400">
        {points.map((point, index) => (
          <span key={point.label} className="flex-1 overflow-visible whitespace-nowrap">{index % labelEvery === 0 ? point.label : ''}</span>
        ))}
      </div>
    </figure>
  )
}

export interface BarRow {
  label: string
  value: number
  /** The value as shown, e.g. "RM1,240.00". */
  shown: string
  /** Under the label, e.g. "38 orders". */
  sub?: string
}

/** Horizontal bars with their labels and values: outlets, top items, folders. */
export function BarList({ rows, label, empty = 'No sales yet.' }: { rows: BarRow[]; label: string; empty?: string }) {
  const max = Math.max(...rows.map((row) => row.value), 0)
  if (rows.length === 0) return <p className="text-sm text-zinc-500">{empty}</p>
  return (
    <ul className="grid list-none gap-3" aria-label={label}>
      {rows.map((row, index) => (
        <li key={row.label} className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 gap-y-1.5 text-sm">
          <span className="truncate font-medium text-zinc-700">
            {row.label}
            {row.sub && <span className="ml-[0.4rem] text-xs text-zinc-500">{row.sub}</span>}
          </span>
          <span className="font-semibold tabular-nums">{row.shown}</span>
          <span className="col-span-full h-2 rounded-full bg-zinc-100" aria-hidden="true">
            <span
              className="block h-full min-w-[2px] rounded-full"
              style={{ width: `${max > 0 ? (row.value / max) * 100 : 0}%`, background: CHART_COLOURS[index % CHART_COLOURS.length] }}
            />
          </span>
        </li>
      ))}
    </ul>
  )
}

/** Shares of a whole, e.g. payment methods: a ring with its legend. */
export function Donut({ rows, label, centre }: { rows: BarRow[]; label: string; centre: string }) {
  const positive = rows.filter((row) => row.value > 0)
  const total = positive.reduce((sum, row) => sum + row.value, 0)
  if (total === 0) return <p className="text-sm text-zinc-500">No payments yet.</p>
  const radius = 15.9155 // circumference 100, so dash lengths are percentages
  let offset = 25 // start at 12 o'clock
  return (
    <div className="flex flex-wrap items-center gap-4">
      <svg className="size-38 flex-none" viewBox="0 0 42 42" role="img" aria-label={label}>
        <circle cx="21" cy="21" r={radius} fill="none" />
        {positive.map((row, index) => {
          const share = (row.value / total) * 100
          const segment = (
            <circle
              key={row.label}
              cx="21"
              cy="21"
              r={radius}
              fill="none"
              stroke={CHART_COLOURS[index % CHART_COLOURS.length]}
              strokeWidth="6"
              strokeDasharray={`${share} ${100 - share}`}
              strokeDashoffset={offset}
            >
              <title>{`${row.label}: ${row.shown} (${Math.round(share)}%)`}</title>
            </circle>
          )
          offset -= share
          return segment
        })}
        <text x="21" y="22.5" textAnchor="middle" className="fill-zinc-900 text-[4px] font-bold">{centre}</text>
      </svg>
      <ul className="grid min-w-48 flex-1 list-none gap-2 text-sm">
        {positive.map((row, index) => (
          <li key={row.label} className="grid grid-cols-[auto_1fr_auto] items-center gap-2">
            <span className="size-3 rounded-full ring-2 ring-white" style={{ background: CHART_COLOURS[index % CHART_COLOURS.length] }} />
            <span>{row.label}</span>
            <span className="text-zinc-500 tabular-nums">
              {row.shown} · {Math.round((row.value / total) * 100)}%
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
