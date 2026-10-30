import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { addDays, todayIso } from '../../lib/dates'
import { ReportsScreen } from './ReportsScreen'

function json(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function fakeApi() {
  const urls: URL[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://pos.test')
    urls.push(url)
    const range = { from: url.searchParams.get('from'), to: url.searchParams.get('to') }
    switch (url.pathname) {
      case '/api/v1/reports/sales-summary':
        return json({ ...range, orderCount: 2, grossSales: 76, discounts: 15, refundCount: 1, refunds: 16, netSales: 45,
          paymentMethods: [{ method: 'Visa', paymentCount: 2, collected: 41, refunded: 16, net: 25 },
            { method: 'Cash', paymentCount: 1, collected: 20, refunded: 0, net: 20 }],
          totalCollected: 45,
          orderStatuses: [{ status: 'Paid', count: 2, amount: 61 }, { status: 'Refunded', count: 1, amount: 16 }],
          diningOptions: [{ name: 'Retail', orderCount: 2, amount: 45, paxCount: 2, perPaxAverage: 22.5 }] })
      case '/api/v1/reports/hourly-sales':
        return json({ ...range, hours: [
          { hour: 9, orderCount: 1, sales: 32, refunds: 0, net: 32 },
          { hour: 14, orderCount: 0, sales: 0, refunds: 16, net: -16 },
          { hour: 23, orderCount: 1, sales: 29, refunds: 0, net: 29 },
        ] })
      case '/api/v1/reports/payment-methods':
        return json({ ...range, total: 45, methods: [
          { method: 'DuitNow QR', paymentCount: 8, collected: 25, refunded: 0, net: 25 },
          { method: 'Visa Card Payment', paymentCount: 1, collected: 20, refunded: 0, net: 20 },
        ] })
      case '/api/v1/reports/item-sales':
        return json({ ...range, items: [{ itemName: 'Pouch Cheese', quantity: 2, amount: 32 }] })
      case '/api/v1/reports/category-sales':
        return json({ ...range, categories: [{ categoryName: 'B3 F1 Promo Baby Can', quantity: 5, amount: 32.5, items: [
          { itemName: 'B3 F1 BC BBQ', quantity: 5, amount: 32.5, prices: [{ label: 'Unit Value', unitPrice: 6.5, quantity: 5, amount: 32.5 }] },
        ] }] })
      case '/api/v1/reports/discounts':
        return json({ ...range, discounts: [{ name: 'Staff 10%', count: 1, amount: 2.9 }] })
      case '/api/v1/reports/modifiers':
        return json({ ...range, options: [
          { groupName: 'General Gift Box Paper Can', optionName: 'GB PC Tomato', quantity: 3, amount: 0 },
          { groupName: 'General Gift Box Paper Can', optionName: 'GB PC Seaweed', quantity: 2, amount: 0 },
        ] })
      case '/api/v1/reports/loyalty-points':
        return json({ ...range, issued: 60, redeemed: 35, issuedReversed: 0, redeemedReturned: 0,
          redemptions: [{ name: 'Member Redemption', count: 5, points: 35 }] })
      default:
        return json({ ...range, entries: [], hours: [], categories: [] })
    }
  }))
  return urls
}

const report = (name: string) => screen.findByRole('region', { name })

describe('ReportsScreen', () => {
  it('shows today’s sales summary as printed: totals by method, order statuses and dining options', async () => {
    const urls = fakeApi()
    render(<ReportsScreen outletName="Counterline Bukit Bintang" onOpenMenu={() => {}} />)

    const summary = await report('Sales Summary')
    expect(await within(summary).findByText('Net Sales')).toBeInTheDocument()
    expect(within(summary).getByText('(RM15.00)')).toBeInTheDocument()
    expect(within(summary).getByText('(RM16.00)')).toBeInTheDocument()
    expect(within(summary).getByText('Total Collected').parentElement).toHaveTextContent('RM45.00')
    expect(within(summary).getByRole('list', { name: 'Order statuses' })).toHaveTextContent(/Paid\s*RM61.00\s*2/)
    expect(within(summary).getByRole('region', { name: 'Dining options' })).toHaveTextContent(
      /Retail\s*2\s*Totals\s*RM45.00\s*Pax Count\s*2\s*Per Pax Average\s*RM22.50/,
    )
    expect(Object.fromEntries(urls.find((url) => url.pathname.endsWith('sales-summary'))!.searchParams))
      .toEqual({ from: todayIso(), to: todayIso() })
  })

  it('prints with the outlet, the period and when it was generated', async () => {
    fakeApi()
    const print = vi.fn()
    vi.stubGlobal('print', print)
    const user = userEvent.setup()
    render(<ReportsScreen outletName="Counterline Bukit Bintang" onOpenMenu={() => {}} />)

    expect(screen.getByText('Counterline Bukit Bintang')).toBeInTheDocument()
    expect(await screen.findByText(/^Generated on /)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Print' }))
    expect(print).toHaveBeenCalled()
  })

  it('deducts refunds from Hourly Sales so the total is the day’s net sales', async () => {
    fakeApi()
    const user = userEvent.setup()
    render(<ReportsScreen outletName="Counterline Bukit Bintang" onOpenMenu={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Hourly Sales' }))

    expect(await screen.findByText((text) => text.includes('refund (RM16.00)'))).toBeInTheDocument()
    expect(screen.getByText('(RM16.00)')).toBeInTheDocument()
    expect(screen.getByTestId('hourly-total')).toHaveTextContent('Total Net Sales2 ordersRM45.00')
  })

  it('lists payments per method, category sales by unit value, grouped modifiers and loyalty points', async () => {
    fakeApi()
    const user = userEvent.setup()
    render(<ReportsScreen outletName="Counterline Bukit Bintang" onOpenMenu={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Payment Methods' }))
    expect(await screen.findByText('8 Payments')).toBeInTheDocument()
    expect(screen.getByText('Total').parentElement).toHaveTextContent('RM45.00')

    await user.click(screen.getByRole('button', { name: 'Category Sales' }))
    const promo = await screen.findByRole('region', { name: 'B3 F1 Promo Baby Can' })
    expect(promo).toHaveTextContent(/5 B3 F1 Promo Baby Can\s*RM32.50/)
    expect(promo).toHaveTextContent(/5 B3 F1 BC BBQ\s*RM32.50\s*5 Unit Value\s*RM32.50/)

    await user.click(screen.getByRole('button', { name: 'Modifiers' }))
    expect(await screen.findByRole('region', { name: 'General Gift Box Paper Can' }))
      .toHaveTextContent(/5 General Gift Box Paper Can.*3 GB PC Tomato.*2 GB PC Seaweed/)

    await user.click(screen.getByRole('button', { name: 'Loyalty Points' }))
    const points = await report('Loyalty Points')
    expect(await within(points).findByText('Issued Points')).toBeInTheDocument()
    expect(points).toHaveTextContent(/Issued Points\s*60\s*Redeemed Points\s*\(35\)\s*Member Redemption\s*5 items\s*\(35\)/)
  })

  it('says the item, category, discount and modifier reports are before refunds, and the net ones are not', async () => {
    fakeApi()
    const user = userEvent.setup()
    render(<ReportsScreen outletName="Counterline Bukit Bintang" onOpenMenu={() => {}} />)

    for (const name of ['Item Sales', 'Category Sales', 'Discounts', 'Modifiers']) {
      await user.click(screen.getByRole('button', { name }))
      expect(await screen.findByTestId('before-refunds-note')).toHaveTextContent(/Before refunds: a refunded sale is still counted here/)
    }
    for (const name of ['Sales Summary', 'Hourly Sales', 'Payment Methods', 'Loyalty Points']) {
      await user.click(screen.getByRole('button', { name }))
      await screen.findByText(/^Generated on /)
      expect(screen.queryByTestId('before-refunds-note')).not.toBeInTheDocument()
    }
  })

  it('steps through days and switches reports', async () => {
    const urls = fakeApi()
    const user = userEvent.setup()
    render(<ReportsScreen outletName="Counterline Bukit Bintang" onOpenMenu={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Previous period' }))
    const yesterday = addDays(todayIso(), -1)
    expect(Object.fromEntries(urls.at(-1)!.searchParams)).toEqual({ from: yesterday, to: yesterday })

    await user.click(screen.getByRole('button', { name: 'Item Sales' }))
    expect(await screen.findByText('2 Pouch Cheese')).toBeInTheDocument()
    expect(urls.at(-1)!.pathname).toBe('/api/v1/reports/item-sales')
  })
})
