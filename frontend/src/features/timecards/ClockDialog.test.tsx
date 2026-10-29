import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ClockDialog } from './ClockDialog'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/**
 * Answers the status check with `status`, and a clock request with the type it asked for. The
 * Clock In/Out PIN pad is not locked.
 */
function stubClock(status: { clockedIn: boolean; since: string | null }) {
  const fetch = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    if (String(input).endsWith('/pin-lock/CLOCK')) return json({ lockedForSeconds: 0 })
    if (String(input).endsWith('/status')) return json({ staffName: 'Aisyah Rahman', ...status })
    const { type } = JSON.parse(String(init.body)) as { type: string }
    return json({ staffName: 'Aisyah Rahman', type, at: '2026-10-12T01:02:00Z' })
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

/** `fakeTimers`: the test uses vi.useFakeTimers, so user-event must advance them while it waits. */
function setUpUser(fakeTimers = false) {
  return userEvent.setup(fakeTimers ? { advanceTimers: vi.advanceTimersByTime } : {})
}

async function enterPin(user: ReturnType<typeof userEvent.setup>, pin: string) {
  const dialog = screen.getByRole('dialog', { name: 'Clock In/Out' })
  for (const digit of pin) await user.click(within(dialog).getByRole('button', { name: digit }))
}

/** The timecard requests, without the PIN pad's lock checks. */
function clockCalls(fetch: ReturnType<typeof stubClock>) {
  return fetch.mock.calls.filter(([input]) => !String(input).includes('/pin-lock/'))
}

/** Drags the slide all the way across, as far as a keyboard goes: End. */
async function slide(user: ReturnType<typeof userEvent.setup>, name: string) {
  screen.getByRole('slider', { name }).focus()
  await user.keyboard('{End}')
}

describe('ClockDialog', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('records nothing for the PIN alone: the staff member slides to Clock In, then it counts down and closes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const fetch = stubClock({ clockedIn: false, since: null })
    const onClose = vi.fn()
    const user = setUpUser(true)
    render(<ClockDialog onClose={onClose} />)

    await enterPin(user, '111111')
    const status = await screen.findByRole('status', { name: 'Not clocked in' })
    expect(status).toHaveTextContent('Aisyah RahmanNot clocked in yet')
    // Red slide: not clocked in.
    expect(screen.getByRole('slider', { name: 'Slide to Clock In' }).closest('[data-from]')).toHaveAttribute('data-from', 'danger')
    expect(screen.queryByRole('slider', { name: 'Slide to Clock Out' })).not.toBeInTheDocument()
    expect(clockCalls(fetch)).toHaveLength(1)

    // A tap, or a slide let go halfway, does nothing.
    const slider = screen.getByRole('slider', { name: 'Slide to Clock In' })
    fireEvent.pointerDown(slider, { clientX: 10, pointerId: 1 })
    fireEvent.pointerUp(slider, { clientX: 10, pointerId: 1 })
    slider.focus()
    await user.keyboard('{ArrowRight}{ArrowRight}')
    expect(slider).toHaveAttribute('aria-valuenow', '50')
    expect(clockCalls(fetch)).toHaveLength(1)

    await slide(user, 'Slide to Clock In')
    const result = await screen.findByRole('status')
    expect(result).toHaveTextContent(/Clocked in.*Aisyah Rahman ·/)
    expect(JSON.parse(String(clockCalls(fetch)[1][1]!.body))).toEqual({ pin: '111111', type: 'IN' })
    // It counts down from 5 seconds and closes itself (the exact timing is useCountdown's test: here
    // the clock also follows real time, which a busy test run can stretch).
    expect(screen.getByText(/^Closing in [1-5]s$/)).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(5000))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('cannot be closed with Escape or the backdrop while the clock request is in flight', async () => {
    let finish: (response: Response) => void = () => {}
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input).endsWith('/pin-lock/CLOCK')) return Promise.resolve(json({ lockedForSeconds: 0 }))
      if (String(input).endsWith('/status')) return Promise.resolve(json({ staffName: 'Aisyah Rahman', clockedIn: false, since: null }))
      return new Promise<Response>((resolve) => { finish = resolve })
    }))
    const onClose = vi.fn()
    const user = setUpUser()
    render(<ClockDialog onClose={onClose} />)

    await enterPin(user, '111111')
    await screen.findByRole('status', { name: 'Not clocked in' })
    await slide(user, 'Slide to Clock In')
    await user.keyboard('{Escape}')
    await user.pointer({ keys: '[MouseLeft]', target: screen.getByRole('dialog', { name: 'Clock In/Out' }).parentElement! })
    expect(onClose).not.toHaveBeenCalled()

    finish(json({ staffName: 'Aisyah Rahman', type: 'IN', at: '2026-10-12T01:02:00Z' }))
    await vi.waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/Clocked in.*Aisyah Rahman ·/))
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('shows someone clocked in with a green slide to Clock Out, then shows it in red', async () => {
    stubClock({ clockedIn: true, since: '2026-10-12T01:00:00Z' })
    const user = setUpUser()
    render(<ClockDialog onClose={() => {}} />)

    await enterPin(user, '111111')
    const status = await screen.findByRole('status', { name: 'Clocked in' })
    expect(status).toHaveTextContent(/Clocked in since/)
    expect(screen.getByRole('slider', { name: 'Slide to Clock Out' }).closest('[data-from]')).toHaveAttribute('data-from', 'success')
    expect(screen.queryByRole('slider', { name: 'Slide to Clock In' })).not.toBeInTheDocument()
    await slide(user, 'Slide to Clock Out')

    const result = await screen.findByRole('status')
    expect(result).toHaveTextContent('Clocked out')
    expect(screen.queryByText(/CLOCKED/)).not.toBeInTheDocument()
  })

  it('shows an invalid PIN and lets the staff member try again', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ title: 'Request cannot be completed', detail: 'Invalid PIN' }, 422)))
    const user = setUpUser()
    render(<ClockDialog onClose={() => {}} />)

    await enterPin(user, '000000')

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid PIN')
    expect(screen.getByRole('img', { name: '0 of 6 digits entered' })).toBeInTheDocument()
  })

  it('blocks the keypad while the pad is locked, and frees it once a manager unlocks it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let lockedFor = 120
    vi.stubGlobal('fetch', vi.fn(async () => json({ lockedForSeconds: lockedFor })))
    render(<ClockDialog onClose={() => {}} />)

    // Locked before the dialog opened: it says so at once, and the keys do nothing.
    expect(await screen.findByRole('timer')).toHaveTextContent('Locked · 2:00')
    const dialog = screen.getByRole('dialog', { name: 'Clock In/Out' })
    expect(within(dialog).getByRole('button', { name: '1' })).toBeDisabled()

    // Unlocked on the dashboard: the next check frees the pad.
    lockedFor = 0
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '1' })).toBeEnabled())
    expect(screen.queryByRole('timer')).not.toBeInTheDocument()
  })
})
