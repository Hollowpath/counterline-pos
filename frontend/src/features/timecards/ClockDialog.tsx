import { useState } from 'react'
import { ApiError, pinLockout, postJson } from '../../api/http'
import { PinEntry } from '../../auth/PinEntry'
import { pinLockStatus } from '../../auth/pinLock'
import { Dialog } from '../../components/Dialog'
import { SlideToConfirm } from '../../components/SlideToConfirm'
import { formatTime } from '../../lib/dates'
import { useCountdown } from '../../lib/useCountdown'
import { LiveClock } from './LiveClock'
import { dialogActions } from '../../styles/ui'

type ClockType = 'IN' | 'OUT'

interface ClockStatus {
  staffName: string
  clockedIn: boolean
  /** When they last clocked in or out; null if they never have. */
  since: string | null
}

interface ClockResult {
  staffName: string
  type: ClockType
  at: string
}

/** How long the confirmation stays up before the dialog closes itself. */
const RESULT_SECONDS = 5
/** Left on the Clock In / Clock Out choice, the dialog closes so nobody else can tap it. */
const CHOICE_SECONDS = 15

type Phase =
  | { name: 'pin' }
  | { name: 'choose'; pin: string; status: ClockStatus; error?: string }
  | { name: 'done'; result: ClockResult }

/** "Clocked in since 9:05 am", "Clocked out at 6:02 pm", or "Not clocked in yet". */
function statusText({ clockedIn, since }: ClockStatus): string {
  if (since === null) return 'Not clocked in yet'
  return clockedIn ? `Clocked in since ${formatTime(since)}` : `Clocked out at ${formatTime(since)}`
}

function errorMessage(error: unknown) {
  return error instanceof ApiError ? error.message : 'Offline'
}

/**
 * Clock In/Out: a staff member enters their PIN, sees whether they are clocked in, then slides to
 * Clock In or Clock Out themselves (the slide is red while out, green while in). The confirmation
 * closes itself after a few seconds, counting down.
 */
export function ClockDialog({ onClose }: { onClose: () => void }) {
  const [phase, setPhase] = useState<Phase>({ name: 'pin' })
  const [busy, setBusy] = useState(false)
  const secondsLeft = useCountdown(
    phase.name === 'done' ? RESULT_SECONDS : CHOICE_SECONDS,
    phase.name !== 'pin',
    onClose,
  )

  async function checkPin(pin: string) {
    try {
      const status = await postJson<ClockStatus>('/api/v1/timecards/status', { pin })
      setPhase({ name: 'choose', pin, status })
    } catch (error) {
      return pinLockout(error) ?? errorMessage(error)
    }
  }

  async function clock(type: ClockType) {
    if (phase.name !== 'choose') return
    setBusy(true)
    try {
      const result = await postJson<ClockResult>('/api/v1/timecards/clock', { pin: phase.pin, type })
      setPhase({ name: 'done', result })
    } catch (error) {
      setPhase({ ...phase, error: errorMessage(error) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title="Clock In/Out" onClose={busy ? undefined : onClose} closeOnBackdrop>
      <LiveClock />
      {phase.name === 'pin' && <PinEntry onSubmit={checkPin} lockStatus={clockLockStatus} />}

      {phase.name === 'choose' && (
        <div className="flex flex-col items-stretch gap-4 text-center">
          <div
            className="flex flex-col items-center gap-1"
            role="status"
            aria-label={phase.status.clockedIn ? 'Clocked in' : 'Not clocked in'}
          >
            <p className="text-xl font-bold tracking-tight">{phase.status.staffName}</p>
            <p className="text-sm text-zinc-500">{statusText(phase.status)}</p>
          </div>
          {/*
            A slide, not a tap, so nobody clocks in or out by mistake; a failed try starts it again. Its
            colour is where they are now (red: out, green: in), turning to where the slide takes them.
          */}
          <SlideToConfirm
            key={phase.error ?? 'ready'}
            label={phase.status.clockedIn ? 'Slide to Clock Out' : 'Slide to Clock In'}
            colors={phase.status.clockedIn ? { from: 'success', to: 'danger' } : { from: 'danger', to: 'success' }}
            disabled={busy}
            onConfirm={() => void clock(phase.status.clockedIn ? 'OUT' : 'IN')}
          />
          {phase.error && (
            <p className="text-sm font-medium text-red-600" role="alert">
              {phase.error}
            </p>
          )}
        </div>
      )}

      {phase.name === 'done' && (
        <div className="flex flex-col items-center gap-1 px-4 py-2 text-center" role="status">
          <span className="mb-2 flex size-12 items-center justify-center rounded-full bg-zinc-100 text-xl font-bold text-zinc-700 ring-1 ring-zinc-200" aria-hidden="true">✓</span>
          <p className="text-2xl font-bold tracking-tight">{phase.result.type === 'IN' ? 'Clocked in' : 'Clocked out'}</p>
          <p className="text-sm text-zinc-500">
            {phase.result.staffName} · {formatTime(phase.result.at)}
          </p>
        </div>
      )}

      <div className={dialogActions}>
        {phase.name !== 'pin' && (
          <span className="mr-auto text-xs font-medium text-zinc-400" aria-live="polite">
            Closing in {secondsLeft}s
          </span>
        )}
        {phase.name === 'done' && (
          <button type="button" className="btn" onClick={() => setPhase({ name: 'pin' })}>
            Clock another
          </button>
        )}
        <button type="button" className="btn" onClick={onClose}>
          {phase.name === 'done' ? 'Done' : 'Cancel'}
        </button>
      </div>
    </Dialog>
  )
}

/** Clock In/Out has a lock of its own at the outlet. */
const clockLockStatus = pinLockStatus.terminal('CLOCK')
