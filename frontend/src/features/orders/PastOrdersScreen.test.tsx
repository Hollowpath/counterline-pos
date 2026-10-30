import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setAuthToken } from '../../api/http'
import type { Staff } from '../../auth/types'
import type { Order, OrderStatus } from '../checkout/types'
import type { OrderSummary } from './ordersApi'
import { PastOrdersScreen } from './PastOrdersScreen'
import { RegisterContext } from '../register/registerApi'

const cashier: Staff = { id: 7, fullName: 'Aisyah Rahman', role: 'STAFF' }
const manager: Staff = { id: 8, fullName: 'Daniel Lim', role: 'MANAGER' }

function makeOrder(id: number, status: OrderStatus, overrides: Partial<Order> = {}): Order {
  const paid = status === 'CLOSED'
  return {
    id,
    orderNumber: `R${id}`,
    receiptCode: `261012AAAAA${String(id).padStart(2, '0')}`,
    status,
    subtotal: 29,
    discountTotal: 0,
    total: 29,
    amountPaid: paid ? 29 : 0,
    note: null,
    staffName: 'Aisyah Rahman',
    customer: null,
    createdAt: '2026-10-12T10:00:00Z',
    closedAt: paid ? '2026-10-12T10:01:00Z' : null,
    lines: [{
      lineNumber: 1, itemId: 4, itemName: 'Tin Caramel', note: null, unitPrice: 29, modifierUnitPrice: 0, quantity: 1,
      modifiers: [], lineSubtotal: 29, discounts: [], discountTotal: 0, lineTotal: 29,
    }],
    orderDiscounts: [],
    payments: paid
      ? [{ id: 1, method: 'Visa', kind: 'CASHLESS', amount: 29, reference: '4389-0340', cashTendered: null, change: null, createdAt: '2026-10-12T10:01:00Z' }]
      : [],
    voided: null,
    refund: null,
    ...overrides,
  }
}

function summary(order: Order): OrderSummary {
  return {
    id: order.id,
    orderNumber: order.orderNumber,
    receiptCode: order.receiptCode,
    status: order.status,
    refunded: order.refund !== null,
    total: order.total,
    itemSummary: order.lines.map((line) => line.itemName).join(', '),
    customerName: order.customer?.name ?? null,
    createdAt: order.createdAt,
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface Call {
  method: string
  url: URL
  body: unknown
  authorization: string | null
}

/**
 * A small in-memory API: searches filter `orders` by status/q, and void/refund/pay mutate them,
 * so the screen can be exercised end to end. Every request is recorded.
 */
function fakeApi(orders: Order[], options: { managerPin?: string; pageSize?: number } = {}) {
  const calls: Call[] = []
  const byId = new Map(orders.map((order) => [order.id, order]))
  const pageSize = options.pageSize ?? 20

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = new URL(String(input), 'http://pos.test')
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, url, body, authorization: new Headers(init.headers).get('Authorization') })
      const path = url.pathname

      if (method === 'GET' && path === '/api/v1/orders') {
        const page = Number(url.searchParams.get('page'))
        const q = url.searchParams.get('q')
        const matches = [...byId.values()]
          .filter((order) => order.status === url.searchParams.get('status'))
          .filter((order) => !q || order.orderNumber === q)
        return json({
          items: matches.slice(page * pageSize, (page + 1) * pageSize).map(summary),
          page,
          size: pageSize,
          totalItems: matches.length,
          totalPages: Math.ceil(matches.length / pageSize),
        })
      }
      if (method === 'GET' && path === '/api/v1/payment-methods') return json([{ id: 6, name: 'Cash', kind: 'CASH' }, { id: 2, name: 'Visa', kind: 'CASHLESS' }])
      if (method === 'POST' && path === '/api/v1/auth/special-access') {
        return (body as { pin: string }).pin === options.managerPin
          ? json({ token: 'manager-token', expiresAt: '2999-01-01T00:00:00Z', staff: manager })
          : json({ title: 'Special access required', detail: 'Manager PIN · 3 tries left' }, 403)
      }

      const match = /^\/api\/v1\/orders\/(\d+)(?:\/(void|refund|payments))?$/.exec(path)
      const order = match ? byId.get(Number(match[1])) : undefined
      if (!order) return json({ title: 'Not found' }, 404)
      const reason = (body as { reason?: string } | undefined)?.reason ?? ''
      switch (method === 'GET' ? 'get' : match![2]) {
        case 'get':
          return json(order)
        case 'void':
          byId.set(order.id, { ...order, status: 'VOIDED', voided: { reason, voidedBy: manager.fullName, voidedAt: '2026-10-12T11:00:00Z' } })
          return json(byId.get(order.id))
        case 'refund':
          byId.set(order.id, { ...order, refund: { amount: order.amountPaid, reason, refundedBy: manager.fullName, refundedAt: '2026-10-12T11:00:00Z' } })
          return json(byId.get(order.id))
        case 'payments':
          byId.set(order.id, { ...makeOrder(order.id, 'CLOSED') })
          return json(byId.get(order.id))
      }
      return json({}, 404)
    }),
  )
  return calls
}

function renderScreen(staff: Staff = cashier, onEditOrder: (orderId: number) => void = () => {}) {
  render(<PastOrdersScreen staff={staff} onOpenMenu={() => {}} onEditOrder={onEditOrder} />)
}

const list = () => within(screen.getByRole('region', { name: 'Orders' }))
const detail = () => within(screen.getByRole('region', { name: 'Order detail' }))

async function openTab(user: UserEvent, name: 'Open' | 'Closed' | 'Voided') {
  await user.click(screen.getByRole('tab', { name }))
}

afterEach(() => setAuthToken(null))

describe('PastOrdersScreen', () => {
  it('lists closed orders by default and shows the selected order', async () => {
    fakeApi([makeOrder(1, 'CLOSED'), makeOrder(2, 'OPEN')])
    const user = userEvent.setup()
    renderScreen()

    await user.click(await list().findByRole('button', { name: /R1/ }))

    expect(screen.getByRole('tab', { name: 'Closed' })).toHaveAttribute('aria-selected', 'true')
    expect(list().queryByRole('button', { name: /R2/ })).not.toBeInTheDocument()
    expect(await detail().findByText('Order Closed')).toBeInTheDocument()
    expect(detail().getByText('Tin Caramel')).toBeInTheDocument()
    expect(detail().getByText(/Ref 4389-0340/)).toBeInTheDocument()
    expect(detail().getByRole('button', { name: 'Issue Refund' })).toBeInTheDocument()
  })

  it('switches status tabs and searches by order number', async () => {
    const calls = fakeApi([makeOrder(1, 'OPEN'), makeOrder(2, 'OPEN'), makeOrder(3, 'CLOSED')])
    const user = userEvent.setup()
    renderScreen()

    await openTab(user, 'Open')
    expect(await list().findByRole('button', { name: /R2/ })).toBeInTheDocument()

    await user.type(screen.getByRole('searchbox', { name: 'Search orders' }), 'R1')

    await waitFor(() => expect(list().queryByRole('button', { name: /R2/ })).not.toBeInTheDocument())
    expect(list().getByRole('button', { name: /R1/ })).toBeInTheDocument()
    const lastSearch = calls.filter((call) => call.url.pathname === '/api/v1/orders').at(-1)!
    expect(Object.fromEntries(lastSearch.url.searchParams)).toMatchObject({ status: 'OPEN', q: 'R1', page: '0' })
  })

  it('loads further pages on demand', async () => {
    fakeApi([makeOrder(1, 'CLOSED'), makeOrder(2, 'CLOSED'), makeOrder(3, 'CLOSED')], { pageSize: 2 })
    const user = userEvent.setup()
    renderScreen()

    await list().findByRole('button', { name: /R1/ })
    expect(list().queryByRole('button', { name: /R3/ })).not.toBeInTheDocument()

    await user.click(list().getByRole('button', { name: 'Load more' }))

    expect(await list().findByRole('button', { name: /R3/ })).toBeInTheDocument()
    expect(list().queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('a cashier voids an unpaid order once a manager enters their PIN', async () => {
    setAuthToken('cashier-session')
    const calls = fakeApi([makeOrder(5, 'OPEN')], { managerPin: '999999' })
    const user = userEvent.setup()
    renderScreen(cashier)

    await openTab(user, 'Open')
    await user.click(await list().findByRole('button', { name: /R5/ }))
    await user.click(await detail().findByRole('button', { name: 'Void Order' }))

    const approval = screen.getByRole('dialog', { name: 'Special Access Required' })
    // The manager sees which action they are approving while they enter the PIN.
    expect(approval).toHaveTextContent('Manager PIN · Void R5')
    for (const digit of '999999') await user.click(within(approval).getByRole('button', { name: digit }))

    const dialog = await screen.findByRole('dialog', { name: 'Void R5' })
    expect(within(dialog).getByText('Approved by Daniel Lim')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason'), 'Customer left')
    await user.click(within(dialog).getByRole('button', { name: 'Void Order' }))

    expect(await detail().findByText('Order Cancelled')).toBeInTheDocument()
    expect(detail().getByText(/Customer left/)).toBeInTheDocument()
    const approvalCall = calls.find((call) => call.url.pathname === '/api/v1/auth/special-access')!
    expect(approvalCall.body).toEqual({ pin: '999999', operation: 'VOID' })
    const voidCall = calls.find((call) => call.url.pathname.endsWith('/void'))!
    expect(voidCall.body).toEqual({ reason: 'Customer left' })
    expect(voidCall.authorization).toBe('Bearer manager-token')
    // The voided order leaves the Open tab.
    await waitFor(() => expect(list().queryByRole('button', { name: /R5/ })).not.toBeInTheDocument())
  })

  it('rejects a PIN that is not a manager’s and does not void', async () => {
    const calls = fakeApi([makeOrder(5, 'OPEN')], { managerPin: '999999' })
    const user = userEvent.setup()
    renderScreen(cashier)

    await openTab(user, 'Open')
    await user.click(await list().findByRole('button', { name: /R5/ }))
    await user.click(await detail().findByRole('button', { name: 'Void Order' }))
    const approval = screen.getByRole('dialog', { name: 'Special Access Required' })
    for (const digit of '111111') await user.click(within(approval).getByRole('button', { name: digit }))

    expect(await within(approval).findByRole('alert')).toHaveTextContent('Manager PIN · 3 tries left')
    expect(calls.some((call) => call.url.pathname.endsWith('/void'))).toBe(false)
  })

  it('a logged-in manager refunds a paid order without a PIN prompt', async () => {
    setAuthToken('manager-session')
    const calls = fakeApi([makeOrder(3, 'CLOSED')])
    const user = userEvent.setup()
    renderScreen(manager)

    await user.click(await list().findByRole('button', { name: /R3/ }))
    await user.click(await detail().findByRole('button', { name: 'Issue Refund' }))

    const dialog = screen.getByRole('dialog', { name: 'Refund R3' })
    expect(dialog).toHaveTextContent('RM29.00 will be refunded to the original payment method: Visa')
    await user.type(within(dialog).getByLabelText('Reason'), 'Damaged tin')
    await user.click(within(dialog).getByRole('button', { name: 'Issue Refund' }))

    expect(await detail().findByText('Order Refunded')).toBeInTheDocument()
    expect(detail().queryByRole('button', { name: 'Issue Refund' })).not.toBeInTheDocument()
    const refundCall = calls.find((call) => call.url.pathname.endsWith('/refund'))!
    expect(refundCall.authorization).toBe('Bearer manager-session')
  })

  it('a cashier’s refund asks a manager to approve a refund, and the approval is for a refund', async () => {
    setAuthToken('cashier-session')
    const calls = fakeApi([makeOrder(3, 'CLOSED')], { managerPin: '999999' })
    const user = userEvent.setup()
    renderScreen(cashier)

    await user.click(await list().findByRole('button', { name: /R3/ }))
    await user.click(await detail().findByRole('button', { name: 'Issue Refund' }))
    const approval = screen.getByRole('dialog', { name: 'Special Access Required' })
    expect(approval).toHaveTextContent('Manager PIN · Refund R3')
    for (const digit of '999999') await user.click(within(approval).getByRole('button', { name: digit }))

    const dialog = await screen.findByRole('dialog', { name: 'Refund R3' })
    await user.type(within(dialog).getByLabelText('Reason'), 'Damaged tin')
    await user.click(within(dialog).getByRole('button', { name: 'Issue Refund' }))

    expect(await detail().findByText('Order Refunded')).toBeInTheDocument()
    const approvalCall = calls.find((call) => call.url.pathname === '/api/v1/auth/special-access')!
    expect(approvalCall.body).toEqual({ pin: '999999', operation: 'REFUND' })
    expect(calls.find((call) => call.url.pathname.endsWith('/refund'))!.authorization).toBe('Bearer manager-token')
  })

  it('sends a placed order back to the cart with Edit Order, instead of paying it here', async () => {
    fakeApi([makeOrder(9, 'OPEN')])
    const onEditOrder = vi.fn()
    const user = userEvent.setup()
    renderScreen(cashier, onEditOrder)

    await openTab(user, 'Open')
    await user.click(await list().findByRole('button', { name: /R9/ }))
    expect(await detail().findByRole('button', { name: 'Void Order' })).toBeInTheDocument()
    expect(detail().queryByRole('button', { name: 'Pay' })).not.toBeInTheDocument()
    await user.click(detail().getByRole('button', { name: 'Edit Order' }))

    expect(onEditOrder).toHaveBeenCalledWith(9)
  })

  it('counts the open orders on the Open tab', async () => {
    fakeApi([makeOrder(1, 'OPEN'), makeOrder(2, 'OPEN'), makeOrder(3, 'CLOSED')])
    render(
      <RegisterContext.Provider value={{ open: true, openOrders: 2, saleOpen: false, refresh: () => {} }}>
        <PastOrdersScreen staff={cashier} onOpenMenu={() => {}} onEditOrder={() => {}} />
      </RegisterContext.Provider>,
    )

    expect(await screen.findByLabelText('2 open')).toHaveTextContent('2')
    expect(screen.getByRole('tab', { name: /^Open/ })).toContainElement(screen.getByLabelText('2 open'))
  })
})
