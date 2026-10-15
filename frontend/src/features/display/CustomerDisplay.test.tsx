import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SellScreen } from '../sell/SellScreen'
import { CustomerDisplayScreen } from './CustomerDisplayScreen'
import type { DisplayStatus } from './displayApi'

const WAIT = { timeout: 4000 }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const routes: Record<string, unknown> = {
  '/api/v1/catalog/tabs': [{ id: 1, name: 'Selling Items', folders: [{ categoryId: 10, name: 'White Stand Pouch', row: 1, col: 1, itemCount: 1 }] }],
  '/api/v1/catalog/categories/10/items': {
    categoryId: 10, name: 'White Stand Pouch',
    items: [{ id: 1, name: 'WSP Caramel', price: 16, imageUrl: null, promotion: null }],
  },
}

/** `status` answers GET /displays/4; every request is recorded. */
function stubApi(status: () => DisplayStatus) {
  const calls: { key: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    const key = `${init.method ?? 'GET'} ${path}`
    calls.push({ key, body: init.body ? JSON.parse(String(init.body)) : null })
    if (key === 'POST /api/v1/displays') return json(status(), 201)
    if (key === 'GET /api/v1/displays/4') return json(status())
    if (key.startsWith('PUT /api/v1/displays/4/cart') || key.startsWith('POST /api/v1/displays/4/member')) {
      return new Response(null, { status: 204 })
    }
    if (key === 'POST /api/v1/orders/quote') return json({ subtotal: 0, discountTotal: 0, total: 0, problems: [], loyalty: null })
    return path in routes ? json(routes[path]) : json({ title: 'Not found' }, 404)
  }))
  return calls
}

const waiting: DisplayStatus = { id: 4, accessCode: '482913', connected: false, displaySeenAt: null, waitingMember: null }

beforeEach(() => localStorage.clear())

describe('Customer Display on the till', () => {
  it('sets up a display: shows the access code, then that it connected', async () => {
    let current = waiting
    stubApi(() => current)
    const changed = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(<CustomerDisplayScreen displayId={null} onDisplayChange={changed} onOpenMenu={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Set up a customer display' }))
    expect(changed).toHaveBeenCalledWith(4)
    expect(localStorage.getItem('counterline.customerDisplay')).toBe('4')

    rerender(<CustomerDisplayScreen displayId={4} onDisplayChange={changed} onOpenMenu={() => {}} />)
    expect(await screen.findByLabelText('Access code')).toHaveTextContent('482913')
    expect(screen.getByText(/display\.html/)).toBeInTheDocument()

    current = { ...waiting, accessCode: null, connected: true, displaySeenAt: '2026-10-13T01:00:00Z' }
    expect(await screen.findByText('Connected', { exact: false }, WAIT)).toBeInTheDocument()
  })

  it('shows posters until a sale is open, then the cart, and adds a member who logged in there by itself', async () => {
    let current: DisplayStatus = { ...waiting, accessCode: null, connected: true }
    const calls = stubApi(() => current)
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} displayId={4} />)
    const lastPublished = () => calls.filter((call) => call.key === 'PUT /api/v1/displays/4/cart').at(-1)?.body

    // No sale: the display shows its posters.
    const sale = screen.getByRole('complementary', { name: 'Current sale' })
    expect(within(sale).getByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    await waitFor(() => expect(lastPublished()).toMatchObject({ stage: 'IDLE' }))
    // New Sale opens one (member login on the display), before anything is added.
    await user.click(within(sale).getByRole('button', { name: 'New Sale' }))
    await waitFor(() => expect(lastPublished()).toMatchObject({ stage: 'CART', lines: [] }))

    await user.click(await screen.findByRole('button', { name: /white stand pouch/i }))
    await user.click(await screen.findByRole('button', { name: /WSP Caramel/ }))
    await waitFor(() => {
      expect(lastPublished()).toMatchObject({
        stage: 'CART', lines: [{ quantity: 1, name: 'WSP Caramel', price: 16, total: 16, free: false }], total: 16,
      })
    })
    expect(lastPublished()).not.toHaveProperty('points')

    // No Accept button: the member is on the sale as soon as the till sees them.
    current = { ...current, waitingMember: { customerId: 5, fullName: 'Nur Aisyah', phone: '+60112345678', newMember: true } }
    const cartPanel = screen.getByRole('complementary', { name: 'Current sale' })
    expect(await within(cartPanel).findByText('Nur Aisyah', {}, WAIT)).toBeInTheDocument()
    expect(within(cartPanel).getByText('Nur Aisyah signed up')).toBeInTheDocument()
    expect(within(cartPanel).queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument()
    current = { ...current, waitingMember: null }
    expect(calls.some((call) => call.key === 'POST /api/v1/displays/4/member/confirm' &&
      (call.body as { customerId: number }).customerId === 5)).toBe(true)
    // The display now greets the member by first name.
    await waitFor(() => expect(lastPublished()).toMatchObject({ memberName: 'Nur' }))

    // Clearing the sale puts the posters back.
    await user.click(within(cartPanel).getByRole('button', { name: 'Clear sale' }))
    await waitFor(() => expect(lastPublished()).toMatchObject({ stage: 'IDLE', memberName: null }))
  })
})
