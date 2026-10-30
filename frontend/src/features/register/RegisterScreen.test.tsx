import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { RegisterContext } from './registerApi'
import { RegisterScreen } from './RegisterScreen'

const emptySales = { from: '2026-10-12', to: '2026-10-12', orderCount: 0, grossSales: 0, discounts: 0, refundCount: 0, refunds: 0, netSales: 0, paymentMethods: [], totalCollected: 0, orderStatuses: [], diningOptions: [] }

function session(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, version: 0, openedAt: '2026-10-12T01:00:00Z', openedBy: 'Aisyah Rahman', closedAt: null, closedBy: null,
    closingNote: null, movements: [], payIns: 0, payOuts: 0, sales: emptySales, openingFloat: 0, expectedCash: 0, ...overrides,
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/**
 * In-memory register API; `closeResponse` overrides the close result (e.g. a conflict), `responses` the
 * result of opening or of a pay in / out (e.g. the server's refusal), `initial` the session already open.
 */
function fakeApi(initiallyOpen: boolean, closeResponse?: () => Response,
  responses: { open?: () => Response | Promise<Response> | undefined; movement?: () => Response | Promise<Response> } = {}, initial: Record<string, unknown> = {}) {
  let current: ReturnType<typeof session> | null = initiallyOpen ? session(initial) : null
  const calls: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    const body = init.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ path, body })
    if (path === '/api/v1/register' && (init.method ?? 'GET') === 'GET') {
      return json({ open: current !== null, session: current, lastClosed: null })
    }
    if (path === '/api/v1/register/open') {
      const answer = responses.open?.()
      if (answer) return answer
      const openingFloat = (body as { openingFloat?: number } | undefined)?.openingFloat ?? 0
      current = session({ openingFloat, expectedCash: openingFloat })
      return json(current, 201)
    }
    if (path === '/api/v1/register/movements') {
      if (responses.movement) return responses.movement()
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
    // The float is declared in a dialog of its own, RM0.00 unless said otherwise.
    const dialog = screen.getByRole('dialog', { name: 'Open Register' })
    expect(within(dialog).getByLabelText('Opening float (RM)')).toHaveValue('0.00')
    await user.click(within(dialog).getByRole('button', { name: 'Open Register' }))

    expect(await screen.findByText('OPEN')).toBeInTheDocument()
    expect(screen.getByText(/Opened at/)).toHaveTextContent(/2026 at .*by Aisyah Rahman/)
    expect(calls.find((call) => call.path === '/api/v1/register/open')!.body).toEqual({ openingFloat: 0 })
    expect(screen.getByText(/Opening float:/)).toHaveTextContent('Opening float: RM0.00Expected cash: RM0.00')
  })

  it('opens with the declared float and shows it with the expected cash', async () => {
    const calls = fakeApi(false)
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Open Register' }))
    const dialog = screen.getByRole('dialog', { name: 'Open Register' })
    await user.clear(within(dialog).getByLabelText('Opening float (RM)'))
    await user.type(within(dialog).getByLabelText('Opening float (RM)'), '150.50')
    await user.click(within(dialog).getByRole('button', { name: 'Open Register' }))

    expect(await screen.findByText(/Opening float:/)).toHaveTextContent('Opening float: RM150.50Expected cash: RM150.50')
    expect(calls.find((call) => call.path === '/api/v1/register/open')!.body).toEqual({ openingFloat: 150.5 })
  })

  it('does not accept a float that is not an amount of money', async () => {
    const calls = fakeApi(false)
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Open Register' }))
    const dialog = screen.getByRole('dialog', { name: 'Open Register' })
    for (const text of ['-5', '1.234', 'abc', '']) {
      await user.clear(within(dialog).getByLabelText('Opening float (RM)'))
      if (text) await user.type(within(dialog).getByLabelText('Opening float (RM)'), text)
      expect(within(dialog).getByRole('button', { name: 'Open Register' })).toBeDisabled()
    }
    expect(calls.some((call) => call.path === '/api/v1/register/open')).toBe(false)
  })

  it('opens once however often the button is pressed, and keeps the float after a refusal', async () => {
    let release: (response: Response) => void = () => {}
    let attempt = 0
    const calls = fakeApi(false, undefined, {
      open: () => {
        attempt += 1
        return attempt === 1 ? new Promise<Response>((resolve) => { release = resolve }) : undefined
      },
    })
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Open Register' }))
    const dialog = screen.getByRole('dialog', { name: 'Open Register' })
    await user.clear(within(dialog).getByLabelText('Opening float (RM)'))
    await user.type(within(dialog).getByLabelText('Opening float (RM)'), '75')
    const confirm = within(dialog).getByRole('button', { name: 'Open Register' })
    await user.dblClick(confirm)
    expect(calls.filter((call) => call.path === '/api/v1/register/open')).toHaveLength(1)
    expect(confirm).toBeDisabled()

    // Refused (someone opened it first): the dialog stays, the float is still there, and it can be tried again.
    release(json({ title: 'Conflict', detail: 'The register is already open' }, 409))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('The register is already open')
    expect(within(dialog).getByLabelText('Opening float (RM)')).toHaveValue('75')
    await waitFor(() => expect(confirm).toBeEnabled())
    await user.click(confirm)
    expect(await screen.findByText(/Opening float:/)).toHaveTextContent('Opening float: RM75.00')
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

  it('shows the server’s refusal of a pay out, keeps what was typed and does not double submit', async () => {
    let release: (response: Response) => void = () => {}
    const calls = fakeApi(true, undefined, {
      movement: () => new Promise<Response>((resolve) => { release = resolve }),
    })
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Pay In / Out' }))
    const dialog = screen.getByRole('dialog', { name: 'Pay In / Out' })
    await user.click(within(dialog).getByRole('tab', { name: 'Pay Out' }))
    await user.type(within(dialog).getByLabelText('Amount (RM)'), '60')
    await user.type(within(dialog).getByLabelText('Reason'), 'Supplies')
    await user.dblClick(within(dialog).getByRole('button', { name: 'Save' }))
    expect(calls.filter((call) => call.path === '/api/v1/register/movements')).toHaveLength(1)
    release(json({ title: 'Conflict', detail: "Pay-out of RM60.00 is more than the register's expected cash of RM40.00" }, 409))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent("Pay-out of RM60.00 is more than the register's expected cash of RM40.00")
    expect(calls.filter((call) => call.path === '/api/v1/register/movements')).toHaveLength(1)
    expect(within(dialog).getByLabelText('Amount (RM)')).toHaveValue('60')
    expect(within(dialog).getByLabelText('Reason')).toHaveValue('Supplies')
    expect(within(dialog).getByRole('tab', { name: 'Pay Out' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Save' })).toBeEnabled())
  })

  it('shows the opening float and the expected cash on the register and in its summary, below zero after a refund', async () => {
    fakeApi(true, undefined, {}, { openingFloat: 20, expectedCash: -9 })
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    expect(await screen.findByText(/Opening float:/)).toHaveTextContent('Opening float: RM20.00Expected cash: (RM9.00)Below zero')

    await user.click(screen.getByRole('button', { name: 'Register summary' }))
    const drawer = within(screen.getByRole('dialog', { name: 'Register summary' })).getByRole('list', { name: 'Cash drawer' })
    expect(within(drawer).getAllByRole('listitem').map((row) => row.textContent)).toEqual(['Opening floatRM20.00', 'Expected cash(RM9.00)'])
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

  it('can’t be closed while a sale is open in the cart', async () => {
    fakeApi(true)
    render(
      <RegisterContext.Provider value={{ open: true, openOrders: 0, saleOpen: true, refresh: () => {} }}>
        <RegisterScreen onOpenMenu={() => {}} />
      </RegisterContext.Provider>,
    )

    expect(await screen.findByRole('button', { name: 'Close Register' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Sale open')
  })

  it('keeps the dialog open and explains a conflicting close', async () => {
    fakeApi(true, () => json({ title: 'Conflicting change', detail: 'Changed elsewhere. Reload.' }, 409))
    const user = userEvent.setup()
    render(<RegisterScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Close Register' }))
    const dialog = screen.getByRole('dialog', { name: 'Close Register' })
    await user.click(within(dialog).getByRole('button', { name: 'Close Register' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Changed elsewhere')
  })
})
