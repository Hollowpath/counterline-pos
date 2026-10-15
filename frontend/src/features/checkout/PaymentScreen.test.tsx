import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Cart } from '../sell/cart'
import { submitCheckout } from './checkoutApi'
import { cartItemCount, cartSubtotalSen } from '../sell/cart'
import { PaymentScreen } from './PaymentScreen'
import type { CheckoutRequest, Order } from './types'

const methods = [
  { id: 6, name: 'Cash', kind: 'CASH' },
  { id: 1, name: 'DuitNow QR', kind: 'CASHLESS' },
  { id: 2, name: 'Visa', kind: 'CASHLESS' },
]

function line(key: string, itemId: number, name: string, unitPriceSen: number, quantity: number) {
  return { key, itemId, name, unitPriceSen, quantity, modifiers: [], note: '', discounts: [] }
}

// 2 × 16.00 + 1 × 29.00 = 61.00
const cart: Cart = {
  open: true,
  note: 'Gift wrap',
  customer: null,
  orderDiscounts: [],
  editing: null,
  lines: [line('a', 1, 'Pouch Cheese', 1600, 2), line('b', 4, 'Tin Caramel', 2900, 1)],
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function order(overrides: Partial<Order> = {}): Order {
  return {
    id: 1, orderNumber: 'R355', receiptCode: '261012ABCDEFG', status: 'CLOSED', subtotal: 61, discountTotal: 0,
    total: 61, amountPaid: 61, note: null, staffName: 'Cashier', customer: null, createdAt: '2026-10-12T10:00:00Z',
    lines: [], orderDiscounts: [], payments: [], voided: null, refund: null, ...overrides,
  }
}

/** Stubs the API; `checkout` answers each POST /orders in turn and request bodies are captured. */
function stubApi(...checkoutResponses: (() => Response | Promise<Response>)[]) {
  const checkoutBodies: CheckoutRequest[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init: RequestInit = {}) => {
      const path = String(input)
      if (path === '/api/v1/payment-methods') return Promise.resolve(json(methods))
      if (path === '/api/v1/orders/1') return Promise.resolve(json(order()))
      if (path === '/api/v1/orders' && init.method === 'POST') {
        checkoutBodies.push(JSON.parse(String(init.body)))
        const next = checkoutResponses.shift()
        return next ? Promise.resolve(next()) : Promise.reject(new Error('unexpected checkout'))
      }
      return Promise.resolve(json({}, 404))
    }),
  )
  return checkoutBodies
}

/** The payment screen as the sell screen uses it: paying for the cart. */
function renderScreen(theCart: Cart = cart) {
  const handlers = { onCancel: vi.fn(), onDone: vi.fn() }
  render(
    <PaymentScreen
      amountDueSen={cartSubtotalSen(theCart)}
      totalLabel={`Total (${cartItemCount(theCart)} items)`}
      onSubmit={(payments, requestId) => submitCheckout(theCart, 'PAY', payments, requestId)}
      doneLabel="New Sale"
      {...handlers}
    />,
  )
  return handlers
}

/** Choose a method, optionally change the amount (cash: what was handed over), enter a reference, confirm. */
async function pay(user: UserEvent, method: string, { amount, reference }: { amount?: string; reference?: string } = {}) {
  await user.click(await screen.findByRole('button', { name: method }))
  if (amount !== undefined) {
    const input = screen.getByLabelText(method === 'Cash' ? 'Cash received (RM)' : 'Amount (RM)')
    await user.clear(input)
    await user.type(input, amount)
  }
  if (reference) await user.type(screen.getByLabelText('Reference'), reference)
  await user.click(screen.getByRole('button', { name: 'Confirm payment' }))
}

describe('PaymentScreen', () => {
  it('a card payment for the full amount, with the terminal reference, completes the sale', async () => {
    const bodies = stubApi(() => json(order(), 201))
    const user = userEvent.setup()
    const { onDone } = renderScreen()

    await user.click(await screen.findByRole('button', { name: 'Visa' }))
    expect(screen.getByLabelText('Amount (RM)')).toHaveValue('61.00')
    await user.type(screen.getByLabelText('Reference'), '4389-0340')
    await user.click(screen.getByRole('button', { name: 'Confirm payment' }))

    expect(await screen.findByRole('dialog', { name: 'Sale complete' })).toHaveTextContent('R355')
    expect(bodies).toHaveLength(1)
    expect(bodies[0]).toMatchObject({
      mode: 'PAY',
      note: 'Gift wrap',
      lines: [{ itemId: 1, quantity: 2 }, { itemId: 4, quantity: 1 }],
      payments: [{ paymentMethodId: 2, amount: 61, reference: '4389-0340', cashTendered: null }],
    })
    expect(bodies[0].clientRequestId).toMatch(/^[0-9a-f-]{36}$/)

    await user.click(screen.getByRole('button', { name: 'New Sale' }))
    expect(onDone).toHaveBeenCalled()
  })

  it('a cashless payment needs its reference', async () => {
    const bodies = stubApi()
    const user = userEvent.setup()
    renderScreen()

    await pay(user, 'DuitNow QR')

    expect(screen.getByText('Enter the DuitNow QR reference from the terminal')).toBeInTheDocument()
    expect(bodies).toHaveLength(0)
  })

  it('splits one card payment and cash, showing the change to give', async () => {
    const bodies = stubApi(() => json(order(), 201))
    const user = userEvent.setup()
    renderScreen()

    await pay(user, 'Visa', { amount: '40', reference: '4432-0330' })
    expect(screen.getByTestId('remaining')).toHaveTextContent('RM21.00')
    expect(bodies).toHaveLength(0)

    // Only cash is left: a sale takes one cashless method.
    expect(screen.getByRole('button', { name: 'DuitNow QR' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Cash' }))
    const received = screen.getByLabelText('Cash received (RM)')
    expect(received).toHaveValue('21.00')
    await user.clear(received)
    await user.type(received, '50')
    expect(screen.getByTestId('change')).toHaveTextContent('Change RM29.00')
    await user.click(screen.getByRole('button', { name: 'Confirm payment' }))

    expect(await screen.findByRole('dialog', { name: 'Sale complete' })).toHaveTextContent('Change RM29.00')
    expect(bodies[0].payments).toEqual([
      { paymentMethodId: 2, amount: 40, reference: '4432-0330', cashTendered: null },
      { paymentMethodId: 6, amount: 21, reference: null, cashTendered: 50 },
    ])
  })

  it('refuses a card amount above the remaining balance', async () => {
    const bodies = stubApi()
    const user = userEvent.setup()
    renderScreen()

    await pay(user, 'Visa', { amount: '70', reference: 'x' })

    expect(screen.getByText('Amount cannot exceed the remaining RM61.00')).toBeInTheDocument()
    expect(within(screen.getByRole('dialog')).queryByRole('list', { name: 'Payments' })).not.toBeInTheDocument()
    expect(bodies).toHaveLength(0)
  })

  it('retries after a network failure with the same request id, so the sale is recorded once', async () => {
    const bodies = stubApi(
      () => Promise.reject(new TypeError('Failed to fetch')),
      () => json(order(), 200),
    )
    const user = userEvent.setup()
    renderScreen()

    await pay(user, 'Visa', { reference: 'ref' })
    expect(await screen.findByRole('alert')).toHaveTextContent('Not confirmed')

    await user.click(screen.getByRole('button', { name: 'Retry' }))

    await screen.findByRole('dialog', { name: 'Sale complete' })
    expect(bodies).toHaveLength(2)
    expect(bodies[1].clientRequestId).toBe(bodies[0].clientRequestId)
  })

  it('shows why the server rejected the sale and offers only going back', async () => {
    stubApi(() => json({ title: 'Request cannot be completed', detail: 'Register closed' }, 422))
    const user = userEvent.setup()
    const { onCancel } = renderScreen()

    await pay(user, 'Cash')

    expect(await screen.findByRole('alert')).toHaveTextContent('Register closed')
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(onCancel).toHaveBeenCalled()
  })

  it('prints the receipt by itself, and each print gives Sale complete its full time again', async () => {
    const print = vi.spyOn(window, 'print')
    stubApi(() => json(order(), 201))
    const user = userEvent.setup()
    const { onDone } = renderScreen()

    await pay(user, 'Visa', { reference: 'ref' })
    const done = await screen.findByRole('dialog', { name: 'Sale complete' })
    await waitFor(() => expect(print).toHaveBeenCalledTimes(1))
    expect(document.getElementById('receipt-print')).toHaveTextContent('R355')

    await waitFor(() => expect(done).toHaveTextContent('Closing in 4s'))
    window.dispatchEvent(new Event('afterprint'))
    await waitFor(() => expect(done).toHaveTextContent('Closing in 5s'))

    // The customer wants a copy.
    await waitFor(() => expect(done).toHaveTextContent('Closing in 4s'))
    await user.click(within(done).getByRole('button', { name: 'Print Receipt' }))
    expect(done).toHaveTextContent('Closing in 5s')
    await waitFor(() => expect(print).toHaveBeenCalledTimes(2))
    expect(onDone).not.toHaveBeenCalled()
  })

  it('completes a free sale without taking a payment', async () => {
    const bodies = stubApi(() => json(order({ total: 0, amountPaid: 0 }), 201))
    const user = userEvent.setup()
    renderScreen({ open: true, note: '', customer: null, orderDiscounts: [], editing: null, lines: [line('c', 9, 'Sample Pouch', 0, 1)] })

    expect(screen.getByText('No payment required')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Complete sale' }))

    await screen.findByRole('dialog', { name: 'Sale complete' })
    expect(bodies[0]).toMatchObject({ note: null, payments: [] })
  })
})
