import { act, render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAuthToken } from '../api/http'
import { DisplayApp } from './DisplayApp'
import type { CartView, DisplayState } from './types'

const KEY = 'counterline.display'
const WAIT = { timeout: 4000 }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** A fake server: `state` is what the display polls; tests change it to play the POS. */
function stubServer() {
  const server = {
    state: { version: 1, outletName: 'Counterline Cheras', cart: null, member: { status: 'NONE', firstName: null } } as DisplayState,
    disconnected: false,
    calls: [] as { key: string; body: unknown }[],
  }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://pos.test')
    const key = `${init.method ?? 'GET'} ${url.pathname}`
    server.calls.push({ key, body: init.body ? JSON.parse(String(init.body)) : null })
    switch (key) {
      case 'POST /api/v1/display/connect':
        return (JSON.parse(String(init.body)) as { accessCode: string }).accessCode === '482913'
          ? json({ token: 'display-jwt', expiresAt: '2999-01-01T00:00:00Z', displayId: 4, outletName: 'Counterline Cheras' })
          : json({ title: 'Business rule', detail: 'Wrong access code. Check the code on the POS under Customer Display.' }, 422)
      case 'GET /api/v1/display/state': {
        if (server.disconnected) return json({ title: 'Authentication required' }, 401)
        const since = Number(url.searchParams.get('since'))
        return since === server.state.version ? new Response(null, { status: 204 }) : json(server.state)
      }
      case 'POST /api/v1/display/member-login':
        return (JSON.parse(String(init.body)) as { phone: string }).phone === '+60112345678'
          ? json({ status: 'WAITING', firstName: 'Nur' })
          : json({ title: 'Not found', detail: 'No member has this phone number. Tap Register Member to join.' }, 404)
      default:
        return json({ title: 'Not found' }, 404)
    }
  }))
  return server
}

const cart = (overrides: Partial<CartView>): CartView => ({
  stage: 'CART', lines: [], itemCount: 0, subtotal: 0, discount: 0, total: 0, memberName: null, ...overrides,
})

const CONNECTION = JSON.stringify({ token: 'display-jwt', expiresAt: '2999-01-01T00:00:00Z', displayId: 4, outletName: 'Counterline Cheras' })

async function type(user: UserEvent, digits: string) {
  for (const digit of digits) await user.click(screen.getByRole('button', { name: digit }))
}

beforeEach(() => localStorage.clear())
afterEach(() => setAuthToken(null))

describe('customer display', () => {
  it('pairs with the access code shown on the till and remembers it', async () => {
    const server = stubServer()
    const user = userEvent.setup()
    render(<DisplayApp />)

    await type(user, '000000')
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong access code')
    await type(user, '482913')

    // No sale open on the till: posters, full screen.
    expect(await screen.findByText('Counterline Cheras')).toBeInTheDocument()
    expect(screen.getByLabelText('Posters')).toBeInTheDocument()
    expect(screen.queryByText('Are you a member?')).not.toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem(KEY)!).token).toBe('display-jwt')
    expect(server.calls.some((call) => call.key === 'GET /api/v1/display/state')).toBe(true)
  })

  it('lets a member log in by phone while a sale is open, then shows the order without points', async () => {
    localStorage.setItem(KEY, CONNECTION)
    const server = stubServer()
    server.state = { ...server.state, cart: cart({}) }
    const user = userEvent.setup()
    render(<DisplayApp />)

    expect(await screen.findByText('Your items will appear here.')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Login' }))
    expect(screen.getByTestId('phone-country')).toHaveTextContent('+60')
    await type(user, '0199999999')
    await user.click(screen.getByRole('button', { name: 'Login' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No member has this phone number')

    await user.click(screen.getByRole('button', { name: 'Clear' }))
    await type(user, '0112345678')
    await user.click(screen.getByRole('button', { name: 'Login' }))
    expect(await screen.findByText('Thanks, Nur!')).toBeInTheDocument()
    expect(screen.getByText('Adding your membership to this order…')).toBeInTheDocument()

    // The till adds them, and rings up the order.
    server.state = {
      ...server.state,
      version: 2,
      member: { status: 'CONFIRMED', firstName: 'Nur' },
      cart: cart({
        lines: [
          { quantity: 2, name: 'WSP Caramel', detail: null, price: 32, total: 32, free: false },
          { quantity: 1, name: 'B3 F1 BC Cheese', detail: null, price: 6.5, total: 0, free: true },
        ],
        itemCount: 3, subtotal: 38.5, discount: 6.5, total: 32, memberName: 'Nur',
      }),
    }
    expect(await screen.findByText('Welcome, Nur!', {}, WAIT)).toBeInTheDocument()
    const order = screen.getByRole('list', { name: 'Your order' })
    expect(within(order).getByText('WSP Caramel')).toBeInTheDocument()
    // A free item shows what it would have cost, struck through, and "Free" (not RM0.00).
    const freeLine = within(order).getByText('B3 F1 BC Cheese').closest('li')!
    expect(freeLine).toHaveTextContent('RM6.50Free')
    expect(freeLine).not.toHaveTextContent('RM0.00')
    expect(screen.getByTestId('display-total')).toHaveTextContent('RM32.00')
    expect(screen.queryByText(/points/i)).not.toBeInTheDocument()
  })

  it('shows "Thank you" for 10 seconds after the sale, then the posters', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      localStorage.setItem(KEY, CONNECTION)
      const server = stubServer()
      server.state = { ...server.state, cart: cart({ stage: 'THANK_YOU', total: 32, memberName: 'Nur' }) }
      render(<DisplayApp />)

      expect(await screen.findByText('Thank you, Nur!')).toBeInTheDocument()
      expect(screen.queryByText(/points/i)).not.toBeInTheDocument()
      // The till moves on at once (its cart is empty again); the display keeps thanking.
      server.state = { ...server.state, version: 2, cart: cart({ stage: 'IDLE' }) }
      await act(() => vi.advanceTimersByTimeAsync(5000))
      expect(screen.getByText('Thank you, Nur!')).toBeInTheDocument()
      await act(() => vi.advanceTimersByTimeAsync(5500))
      expect(screen.queryByText('Thank you, Nur!')).not.toBeInTheDocument()
      expect(screen.getByLabelText('Posters')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('goes back to asking for a code when the till disconnects it', async () => {
    localStorage.setItem(KEY, CONNECTION)
    const server = stubServer()
    render(<DisplayApp />)

    expect(await screen.findByLabelText('Posters')).toBeInTheDocument()
    server.disconnected = true
    expect(await screen.findByText('Disconnected', {}, WAIT)).toBeInTheDocument()
    expect(localStorage.getItem(KEY)).toBeNull()
  })
})
