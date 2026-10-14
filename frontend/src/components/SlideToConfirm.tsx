import { useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react'

/** How far along the track (0–1) the handle must be let go to count. */
const CONFIRM_AT = 0.9
/** One arrow key press moves the handle this far (0–1); reaching the end confirms. */
const KEY_STEP = 0.25

type Tone = 'success' | 'danger'

/** The colour of each tone, from Tailwind's palette. */
const TONE_COLOR: Record<Tone, string> = { success: 'var(--color-green-500)', danger: 'var(--color-red-500)' }

/**
 * "Slide to confirm", as on a phone's lock screen: the action happens only when the handle is
 * dragged all the way across, so a stray tap can't trigger it. Let go early and it slides back.
 * With a keyboard, the arrow keys move it (End goes all the way).
 */
export function SlideToConfirm({ label, onConfirm, disabled = false, colors }: {
  /** e.g. "Slide to Clock In" */
  label: string
  onConfirm: () => void
  disabled?: boolean
  /**
   * The track's colour now, and the colour the slide fills it with (e.g. red to green for Clock
   * In). Plain without.
   */
  colors?: { from: Tone; to: Tone }
}) {
  const track = useRef<HTMLDivElement>(null)
  const handle = useRef<HTMLDivElement>(null)
  const drag = useRef<{ startX: number; startProgress: number } | null>(null)
  const [progress, setProgress] = useState(0)
  const [dragging, setDragging] = useState(false)
  const [done, setDone] = useState(false)

  /** How far the handle can travel, in pixels. */
  function travel() {
    const width = track.current?.clientWidth ?? 0
    const handleWidth = handle.current?.offsetWidth ?? 0
    return Math.max(1, width - handleWidth - 8)
  }

  function confirm() {
    setProgress(1)
    setDone(true)
    onConfirm()
  }

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    if (disabled || done) return
    event.currentTarget.setPointerCapture?.(event.pointerId)
    drag.current = { startX: event.clientX, startProgress: progress }
    setDragging(true)
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    if (!drag.current) return
    const moved = (event.clientX - drag.current.startX) / travel()
    setProgress(Math.min(1, Math.max(0, drag.current.startProgress + moved)))
  }

  function onPointerUp() {
    if (!drag.current) return
    drag.current = null
    setDragging(false)
    if (progress >= CONFIRM_AT) confirm()
    else setProgress(0)
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (disabled || done) return
    const moves: Record<string, number> = {
      ArrowRight: progress + KEY_STEP, ArrowUp: progress + KEY_STEP,
      ArrowLeft: progress - KEY_STEP, ArrowDown: progress - KEY_STEP,
      Home: 0, End: 1,
    }
    if (!(event.key in moves)) return
    event.preventDefault()
    const next = Math.min(1, Math.max(0, moves[event.key]))
    if (next >= 1) confirm()
    else setProgress(next)
  }

  const offset = `calc(${progress} * (100% - var(--slide-handle) - 8px))`
  const tone = colors
    ? { '--slide-from': TONE_COLOR[colors.from], '--slide-to': TONE_COLOR[colors.to] } as CSSProperties
    : undefined
  return (
    <div
      ref={track}
      className={[
        'relative h-[calc(var(--slide-handle)+8px)] overflow-hidden rounded-full border shadow-inner select-none [--slide-handle:3.25rem]',
        colors ? 'border-transparent bg-(--slide-from)' : 'border-zinc-200 bg-zinc-100',
        disabled ? 'opacity-60' : '',
      ].join(' ')}
      data-from={colors?.from}
      style={tone}
    >
      <div
        className={`absolute inset-y-0 left-0 rounded-full ${colors ? 'bg-(--slide-to)' : 'bg-indigo-600/12'} ${
          dragging ? 'transition-none' : 'transition-[width] duration-[250ms] ease-[ease]'}`}
        style={{ width: `calc(${offset} + var(--slide-handle) + 4px)`, opacity: progress > 0 ? 1 : 0 }}
      />
      <span
        className={[
          'pointer-events-none absolute inset-0 flex items-center justify-center pl-[calc(var(--slide-handle)+8px)]',
          "text-base font-bold tracking-wide after:ml-[0.4rem] after:whitespace-pre after:opacity-70 after:content-['__›››']",
          colors ? 'text-white after:text-white' : 'text-zinc-900 after:text-zinc-500',
        ].join(' ')}
        style={{ opacity: 1 - progress }}
        aria-hidden="true"
      >
        {label}
      </span>
      <div
        ref={handle}
        className={[
          'absolute top-[3px] flex size-(--slide-handle) touch-none items-center justify-center rounded-full',
          'bg-white text-[1.8rem] leading-none text-zinc-700 shadow-lg ring-1 ring-black/5',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600',
          dragging ? 'cursor-grabbing transition-none' : 'transition-[left] duration-[250ms] ease-[ease]',
          disabled ? 'cursor-default' : dragging ? '' : 'cursor-grab',
        ].join(' ')}
        style={{ left: `calc(4px + ${offset})` }}
        role="slider"
        tabIndex={disabled ? -1 : 0}
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(progress * 100)}
        aria-disabled={disabled || undefined}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onKeyDown={onKeyDown}
      >
        <span aria-hidden="true">›</span>
      </div>
    </div>
  )
}
