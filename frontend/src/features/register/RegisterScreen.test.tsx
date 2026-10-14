import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { RegisterScreen } from './RegisterScreen'

const emptySales = { from: '2026-10-12', to: '2026-10-12', orderCount: 0, grossSales: 0, discounts: 0, refundCount: 0, refunds: 0, netSales: 0, paymentMethods: [], totalCollected: 0, orderStatuses: [], diningOptions: [] }

function session(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, version: 0, openedAt: '2026-10-12T01:00:00Z', openedBy: 'Aisyah Rahman', closedAt: null, closedBy: null,
    closingNote: null, movements: [], payIns: 0, payOuts: 0, sales: emptySales, ...overrides,
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** In-memory register API; `closeResponse` overrides the close result (e.g. a conflict). */
function fakeApi(initiallyOpen: boolean, closeResponse?: () => Response) {
  let current: ReturnType<typeof session> | null = initiallyOpen ? session() : null
  const calls: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    const body = init.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ path, body })
    if (path === '/api/v1/register' && (init.method ?? 'GET') === 'GET') {
      return json({ open: current !== null, session: current, lastClosed: null })
    }
    if (path === '/api/v1/register/open') {
      current = session()
      return json(current, 201)
    }
    if (path === '/api/v1/register/movements') {
      const movement = { id: 1, ...(body as object), staffName: 'Aisyah Rahman', createdAt: '2026-10-12T02:00:00Z' }
      current = session({ movements: [movement], payIns: (body as { amount: number }).amount })
      return json(current)
    }
    if (path === '/api/v1/register/close') {
      if (closeResponse) return closeResponse()
      current = null
      return json(session({ closedAt: '2026-10-12T10:00:00Z', closedBy: 'Aisyah Rahman' }))
    }
    return json({}, 404)
  }))
  return calls
}

describe('RegisterScreen', () => {
  it('opens the register', async () => {
    const calls = fakeApi(false)
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Open Register' }))

    expect(await screen.findByText('OPEN')).toBeInTheDocument()
    expect(screen.getByText(/Opened at/)).toHaveTextContent(/2026 at .*by Aisyah Rahman/)
    expect(calls.some((call) => call.path === '/api/v1/register/open')).toBe(true)
  })

  it('records a pay in', async () => {
    const calls = fakeApi(true)
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Pay In / Out' }))
    expect(screen.queryByRole('list', { name: 'Pay In / Out this session' })).not.toBeInTheDocument()
    const dialog = screen.getByRole('dialog', { name: 'Pay In / Out' })
    await user.type(within(dialog).getByLabelText('Amount (RM)'), '50')
    await user.type(within(dialog).getByLabelText('Reason'), 'Float')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // Listed under the button once saved.
    expect(await screen.findByRole('list', { name: 'Pay In / Out this session' })).toHaveTextContent('Float: RM50.00')

    // The session's figures are behind the print button.
    await user.click(screen.getByRole('button', { name: 'Register summary' }))
    expect(within(screen.getByRole('dialog', { name: 'Register summary' })).getByText('Float')).toBeInTheDocument()
    expect(calls.find((call) => call.path === '/api/v1/register/movements')!.body).toEqual({ direction: 'IN', amount: 50, reason: 'Float' })
  })

  it('closes the register after a confirmation, with the version shown', async () => {
    const calls = fakeApi(true)
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Close Register' }))
    const dialog = screen.getByRole('dialog', { name: 'Close Register' })
    expect(within(dialog).getByText(/Close the register?/)).toBeInTheDocument()
    expect(within(dialog).queryByRole('textbox')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Close Register' }))

    expect(await screen.findByRole('button', { name: 'Open Register' })).toBeInTheDocument()
    expect(calls.find((call) => call.path === '/api/v1/register/close')!.body).toEqual({ version: 0, note: null })
  })

  it('keeps the dialog open and explains a conflicting close', async () => {
    fakeApi(true, () => json({ title: 'Conflicting change', detail: 'This record was changed by someone else at the same time. Reload it and try again.' }, 409))
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Close Register' }))
    const dialog = screen.getByRole('dialog', { name: 'Close Register' })
    await user.click(within(dialog).getByRole('button', { name: 'Close Register' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('changed by someone else')
  })
})
