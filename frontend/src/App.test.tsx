import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { setAuthToken } from './api/http'
import type { Tab } from './features/catalog/types'

const cashier = { id: 7, fullName: 'Aisyah Rahman', role: 'STAFF' }
const tabs: Tab[] = [{ id: 1, name: 'Selling Items', folders: [{ categoryId: 10, name: 'Popcorn Pouch', row: 1, col: 1, itemCount: 2 }] }]
const SESSION_KEY = 'counterline.session'
const TERMINAL_KEY = 'counterline.terminal'
const outlet = { id: 2, code: 'O2', name: 'Demo Outlet', address: null }
const loggedIn = () => json({ token: 'jwt-1', expiresAt: '2999-01-01T00:00:00Z', staff: cashier, outlet })

type Handler = (init: RequestInit) => Response

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** Routes keyed by "METHOD path"; records every request so headers and bodies can be asserted. */
function stubApi(routes: Record<string, Handler>) {
  const calls: { key: string; init: RequestInit }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init: RequestInit = {}) => {
      const key = `${init.method ?? 'GET'} ${String(input)}`
      calls.push({ key, init })
      const handler = routes[key]
      return Promise.resolve(handler ? handler(init) : json({ title: 'Not found' }, 404))
    }),
  )
  return calls
}

const baseRoutes: Record<string, Handler> = {
  'GET /api/v1/outlets': () => json([{ id: 1, code: 'O1', name: 'Other Outlet', address: null }, outlet]),
  'GET /api/v1/catalog/tabs': () => json(tabs),
}

async function enterPin(user: UserEvent, pin: string) {
  for (const digit of pin) {
    await user.click(screen.getByRole('button', { name: digit }))
  }
}

function authorizationOf(init: RequestInit) {
  return new Headers(init.headers).get('Authorization')
}

beforeEach(() => {
  sessionStorage.clear()
  // This iPad is already set up for the Demo Outlet, unless a test says otherwise.
  localStorage.setItem(TERMINAL_KEY, JSON.stringify({ id: outlet.id, name: outlet.name, deviceKey: 'key-1', label: 'iPad 1' }))
})

afterEach(() => {
  setAuthToken(null)
})

describe('staff PIN login', () => {
  it('logs in on the sixth digit, welcomes the staff member and authorises API calls', async () => {
    const calls = stubApi({
      ...baseRoutes,
      'POST /api/v1/auth/login': loggedIn,
    })
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Demo Outlet' })).toBeInTheDocument()
    await enterPin(user, '111111')

    expect(await screen.findByText('Welcome, Aisyah Rahman')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /popcorn pouch/i })).toBeInTheDocument()

    const loginCall = calls.find((call) => call.key === 'POST /api/v1/auth/login')!
    expect(JSON.parse(String(loginCall.init.body))).toEqual({ pin: '111111', deviceKey: 'key-1' })
    const catalogCall = calls.find((call) => call.key === 'GET /api/v1/catalog/tabs')!
    expect(authorizationOf(catalogCall.init)).toBe('Bearer jwt-1')
  })

  it('shows the error and clears the PIN when it is wrong', async () => {
    stubApi({
      ...baseRoutes,
      'POST /api/v1/auth/login': () => json({ title: 'Login failed', detail: 'Invalid PIN' }, 401),
    })
    const user = userEvent.setup()
    render(<App />)

    await screen.findByRole('heading', { name: 'Demo Outlet' })
    await enterPin(user, '000000')

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid PIN')
    expect(screen.getByRole('img', { name: '0 of 6 digits entered' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /popcorn pouch/i })).not.toBeInTheDocument()
  })

  it('accepts the PIN from a hardware keyboard, blocks it while locked out, and frees it once unlocked', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let locked = false
    let logins = 0
    const calls = stubApi({
      ...baseRoutes,
      'POST /api/v1/auth/pin-lock': () => json({ lockedForSeconds: locked ? 118 : 0 }),
      'POST /api/v1/auth/login': () => {
        logins += 1
        if (logins > 1) return loggedIn()
        locked = true
        return json({ title: 'Too many attempts', detail: 'Locked · 120s', retryAfterSeconds: 120 }, 429)
      },
    })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<App />)

    await screen.findByRole('heading', { name: 'Demo Outlet' })
    await user.keyboard('000000')

    const timer = await screen.findByRole('timer')
    expect(timer).toHaveTextContent('Locked · 2:00')
    // It ticks down by itself, and the keys do nothing meanwhile.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(timer).toHaveTextContent('1:59')
    expect(screen.getByRole('button', { name: '1' })).toBeDisabled()
    await user.keyboard('123')
    expect(screen.getByRole('img', { name: '0 of 6 digits entered' })).toBeInTheDocument()
    expect(calls.filter((call) => call.key === 'POST /api/v1/auth/login')).toHaveLength(1)

    // A manager unlocked it on the dashboard: within a few seconds the keys work again.
    locked = false
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(screen.queryByRole('timer')).not.toBeInTheDocument()
    await user.keyboard('123456')
    expect(await screen.findByRole('button', { name: /popcorn pouch/i })).toBeInTheDocument()
    vi.useRealTimers()
  })

  it('Log Off from the menu returns to the PIN screen and forgets the session', async () => {
    stubApi({
      ...baseRoutes,
      'POST /api/v1/auth/login': loggedIn,
    })
    const user = userEvent.setup()
    render(<App />)

    await screen.findByRole('heading', { name: 'Demo Outlet' })
    await enterPin(user, '111111')
    await user.click(await screen.findByRole('button', { name: 'Open menu' }))
    const menu = screen.getByRole('navigation', { name: 'Main menu' })
    expect(within(menu).getByText('Aisyah Rahman')).toBeInTheDocument()
    expect(within(menu).getByText('Demo Outlet')).toBeInTheDocument()

    await user.click(within(menu).getByRole('button', { name: 'Log Off' }))

    expect(await screen.findByText('Enter your staff PIN')).toBeInTheDocument()
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull()
  })
})

describe('terminal set-up', () => {
  it('asks a manager to bind a new iPad to an outlet before anyone logs in', async () => {
    localStorage.removeItem(TERMINAL_KEY)
    const calls = stubApi({
      ...baseRoutes,
      'POST /api/v1/auth/terminal-setup': (init) => JSON.parse(String(init.body)).pin === '999999'
        ? json({ deviceKey: 'key-2', label: 'iPad 2', outlet })
        : json({ title: 'Special access required', detail: 'Manager PIN · 3 tries left' }, 403),
    })
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Set up this iPad' })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: /Demo Outlet/ }))
    await enterPin(user, '111111')
    expect(await screen.findByRole('alert')).toHaveTextContent('Manager PIN · 3 tries left')

    await enterPin(user, '999999')
    expect(await screen.findByRole('heading', { name: 'Demo Outlet' })).toBeInTheDocument()
    expect(screen.getByText('Enter your staff PIN')).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem(TERMINAL_KEY)!)).toEqual({ id: 2, name: 'Demo Outlet', deviceKey: 'key-2', label: 'iPad 2', address: null })
    const setUp = calls.filter((call) => call.key === 'POST /api/v1/auth/terminal-setup').at(-1)!
    expect(JSON.parse(String(setUp.init.body))).toEqual({ pin: '999999', outletId: 2 })
  })

  it('can\'t be moved from the till: once reset from the dashboard, it is set up again', async () => {
    stubApi({
      ...baseRoutes,
      'POST /api/v1/auth/login': () =>
        json({ title: 'iPad not set up', detail: 'This iPad has been reset. A manager needs to set it up again.' }, 410),
    })
    const user = userEvent.setup()
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Demo Outlet' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change outlet' })).not.toBeInTheDocument()

    await enterPin(user, '111111')
    expect(await screen.findByRole('heading', { name: 'Set up this iPad' })).toBeInTheDocument()
    expect(screen.getByText('This iPad has been reset. A manager needs to set it up again.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
    expect(localStorage.getItem(TERMINAL_KEY)).toBeNull()
  })

  it('asks for set-up again when the stored outlet has no key (set up before iPads had keys)', async () => {
    localStorage.setItem(TERMINAL_KEY, JSON.stringify({ id: outlet.id, name: outlet.name }))
    stubApi(baseRoutes)
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Set up this iPad' })).toBeInTheDocument()
  })
})

describe('session', () => {
  function storeSession(token: string) {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({
      token, expiresAt: '2999-01-01T00:00:00Z', staff: cashier, outlet: { id: outlet.id, name: outlet.name },
    }))
  }

  it('restores a stored session after the server confirms it, without a welcome toast', async () => {
    storeSession('jwt-stored')
    const calls = stubApi({ ...baseRoutes, 'GET /api/v1/auth/me': () => json(cashier) })
    render(<App />)

    expect(await screen.findByRole('button', { name: /popcorn pouch/i })).toBeInTheDocument()
    expect(screen.queryByText(/welcome/i)).not.toBeInTheDocument()
    expect(authorizationOf(calls.find((call) => call.key === 'GET /api/v1/auth/me')!.init)).toBe('Bearer jwt-stored')
  })

  it('returns to the PIN screen with a notice when the server rejects the session', async () => {
    storeSession('jwt-revoked')
    stubApi({
      ...baseRoutes,
      'GET /api/v1/auth/me': () => json({ title: 'Authentication required' }, 401),
    })
    render(<App />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Session ended')
    await waitFor(() => expect(sessionStorage.getItem(SESSION_KEY)).toBeNull())
  })
})
