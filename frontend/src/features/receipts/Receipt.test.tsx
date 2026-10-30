import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { saveTerminal } from '../../auth/terminalStore'
import { formatDateTime } from '../../lib/dates'
import type { Order, OrderStatus } from '../checkout/types'
import { PrintReceiptButton } from './PrintReceiptButton'
import { printScreen } from './printReceipt'
import { Receipt } from './Receipt'

const outlet = { name: 'Counterline Bukit Bintang', address: 'Kiosk, Level 3, Bukit Bintang' }

function order(status: OrderStatus, overrides: Partial<Order> = {}): Order {
  const paid = status === 'CLOSED'
  return {
    id: 12,
    orderNumber: 'R12',
    receiptCode: '261015ABCDEFG',
    status,
    subtotal: 54,
    discountTotal: 6,
    total: 48,
    amountPaid: paid ? 48 : 0,
    note: null,
    staffName: 'Aisyah Rahman',
    customer: { id: 5, name: 'Nur Aisyah', phone: '+60112345678', email: null },
    createdAt: '2026-10-15T02:30:00Z',
    closedAt: paid ? '2026-10-15T02:31:00Z' : null,
    lines: [
      {
        lineNumber: 1, itemId: 4, itemName: 'WSP Caramel', note: null, unitPrice: 16, modifierUnitPrice: 0, quantity: 3,
        modifiers: [], lineSubtotal: 48, discounts: [], discountTotal: 0, lineTotal: 48,
      },
      {
        lineNumber: 2, itemId: 21, itemName: 'B3 F1 BC Caramel', note: null, unitPrice: 6, modifierUnitPrice: 0, quantity: 1,
        modifiers: [], lineSubtotal: 6, discounts: [{ name: 'Buy 3 Free 1 Promo', amount: 6, promotion: true }], discountTotal: 6,
        lineTotal: 0,
      },
    ],
    orderDiscounts: [],
    payments: paid
      ? [{ id: 1, method: 'Visa', kind: 'CASHLESS', amount: 48, reference: '4432-0330', cashTendered: null, change: null, createdAt: '2026-10-15T02:31:00Z' }]
      : [],
    voided: null,
    refund: null,
    loyalty: paid ? { before: 2, earned: 4, redeemed: 0, after: 6 } : null,
    ...overrides,
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
})

describe('the customer receipt', () => {
  it('lists the sale, its promotion, payment, member and points, and marks nothing on a paid one', () => {
    render(<Receipt order={order('CLOSED')} outlet={outlet} />)
    const receipt = screen.getByRole('article', { name: 'Receipt 261015ABCDEFG' })

    expect(receipt).toHaveTextContent('Counterline Bukit Bintang')
    expect(receipt).toHaveTextContent('3x WSP CaramelRM48.00')
    expect(receipt).toHaveTextContent('BUY 3 FREE 1 PROMO-RM6.00')
    expect(receipt).toHaveTextContent('TotalRM48.00')
    expect(receipt).toHaveTextContent('REFERENCE: 4432-0330')
    expect(receipt).toHaveTextContent('Payment StatusPaid')
    expect(receipt).toHaveTextContent('Phone: +60 11•••5678')
    expect(receipt).not.toHaveTextContent('+60112345678')
    expect(receipt).toHaveTextContent(/Previous points\s*2\s*Points earned\s*4\s*Current points\s*6/)
    expect(screen.queryByTestId('receipt-status-banner')).not.toBeInTheDocument()
  })

  // Placed 23:50 on the 14th (outlet time), paid 00:10 on the 15th: reports count it on the 15th, so does the slip.
  it('is dated by when the sale was paid, not when it was placed', () => {
    const placed = '2026-10-14T15:50:00Z'
    const paidAt = '2026-10-14T16:10:00Z'
    render(<Receipt order={order('CLOSED', { createdAt: placed, closedAt: paidAt })} outlet={outlet} />)
    const receipt = screen.getByRole('article')

    expect(receipt).toHaveTextContent(formatDateTime(paidAt))
    expect(receipt).not.toHaveTextContent(formatDateTime(placed))
  })

  it('is dated by a zero-total sale’s paid time too, and an unpaid order by when it was placed', () => {
    const free = order('CLOSED', { total: 0, amountPaid: 0, payments: [], createdAt: '2026-10-14T15:50:00Z', closedAt: '2026-10-14T16:10:00Z' })
    const { unmount } = render(<Receipt order={free} outlet={outlet} />)
    expect(screen.getByRole('article')).toHaveTextContent(formatDateTime('2026-10-14T16:10:00Z'))
    expect(screen.getByRole('article')).toHaveTextContent('Payment StatusPaid')
    unmount()

    render(<Receipt order={order('OPEN', { createdAt: '2026-10-14T15:50:00Z' })} outlet={outlet} />)
    expect(screen.getByRole('article')).toHaveTextContent(formatDateTime('2026-10-14T15:50:00Z'))
  })

  it.each([
    ['OPEN', {}, 'UNPAID', 'Unpaid'],
    ['VOIDED', { voided: { reason: 'Wrong item', voidedBy: 'Daniel Lim', voidedAt: '2026-10-15T03:00:00Z' } }, 'VOIDED', 'Voided'],
    ['CLOSED', { refund: { amount: 48, reason: 'Stale', refundedBy: 'Daniel Lim', refundedAt: '2026-10-15T04:00:00Z' } }, 'REFUNDED', 'Refunded'],
  ] as const)('marks a %s order across the top', (status, overrides, banner, paymentStatus) => {
    render(<Receipt order={order(status, overrides)} outlet={outlet} />)

    expect(screen.getByTestId('receipt-status-banner')).toHaveTextContent(`*** ${banner} ***`)
    expect(screen.getByRole('article')).toHaveTextContent(`Payment Status${paymentStatus}`)
  })
})

describe('printing a receipt', () => {
  function stubApi() {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(String(input), 'http://localhost').pathname
      const body = path === '/api/v1/orders/12' ? order('OPEN') : { id: 1, code: 'O1', ...outlet }
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('loads the order, prints only the slip, and keeps it after the button goes away', async () => {
    saveTerminal({ id: 1, name: 'Counterline Bukit Bintang', deviceKey: 'key', label: 'Till', address: outlet.address })
    const fetchMock = stubApi()
    let printed: HTMLElement | null = null
    const print = vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.getElementById('receipt-print')
    })
    const onPrint = vi.fn()
    const user = userEvent.setup()
    const { unmount } = render(<PrintReceiptButton orderId={12} onPrint={onPrint} />)

    await user.click(screen.getByRole('button', { name: 'Print Receipt' }))

    await waitFor(() => expect(print).toHaveBeenCalledTimes(1))
    expect(onPrint).toHaveBeenCalled()
    expect(within(printed!).getByTestId('receipt-status-banner')).toHaveTextContent('UNPAID')
    expect(printed!).toHaveTextContent('Kiosk, Level 3, Bukit Bintang')
    // The address came with the iPad's set-up: only the order was fetched.
    expect(fetchMock).toHaveBeenCalledTimes(1)
    // An iPad may still be preparing the print when Sale complete closes: the slip stays until the next print.
    unmount()
    expect(document.getElementById('receipt-print')).toHaveTextContent('UNPAID')
  })

  it('takes the slip away before printing a screen, so a report prints itself', async () => {
    saveTerminal({ id: 1, name: 'Counterline Bukit Bintang', deviceKey: 'key', label: 'Till', address: outlet.address })
    stubApi()
    const print = vi.spyOn(window, 'print')
    const user = userEvent.setup()
    render(<PrintReceiptButton orderId={12} />)
    await user.click(screen.getByRole('button', { name: 'Print Receipt' }))
    await waitFor(() => expect(print).toHaveBeenCalledTimes(1))

    printScreen()

    expect(document.getElementById('receipt-print')).toBeNull()
    expect(print).toHaveBeenCalledTimes(2)
  })

  it('looks up the outlet for an iPad set up before its address was kept', async () => {
    saveTerminal({ id: 1, name: 'Counterline Bukit Bintang', deviceKey: 'key', label: 'Till' })
    const fetchMock = stubApi()
    let printed: HTMLElement | null = null
    vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.getElementById('receipt-print')
    })
    const user = userEvent.setup()
    render(<PrintReceiptButton orderId={12} />)

    await user.click(screen.getByRole('button', { name: 'Print Receipt' }))

    await waitFor(() => expect(printed).not.toBeNull())
    expect(printed!).toHaveTextContent('Kiosk, Level 3, Bukit Bintang')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
