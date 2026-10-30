import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { CategoryItems, Tab } from '../catalog/types'
import { RegisterContext } from '../register/registerApi'
import { SellScreen } from './SellScreen'

const tabs: Tab[] = [
  {
    id: 1,
    name: 'Selling Items',
    folders: [
      { categoryId: 10, name: 'Popcorn Pouch', row: 1, col: 1, itemCount: 2 },
      { categoryId: 11, name: 'Seasonal Items', row: 5, col: 1, itemCount: 0 },
    ],
  },
  { id: 2, name: 'Promotional', folders: [{ categoryId: 20, name: 'Buy 3 Free 1', row: 1, col: 1, itemCount: 1 }] },
]

const pouchItems: CategoryItems = {
  categoryId: 10,
  name: 'Popcorn Pouch',
  items: [
    { id: 100, name: 'Pouch Caramel', price: 16, imageUrl: null },
    { id: 101, name: 'Pouch Cheese', price: 14.5, imageUrl: null },
  ],
}

/** The product grid only — the cart also has buttons named after items ("Remove …"). */
const products = () => within(screen.getByRole("region", { name: "Products" }))

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function stubApi(routes: Record<string, () => Response>) {
  // Typed as fetch so tests can inspect request bodies via fetchMock.mock.calls.
  const fetchMock = vi.fn<typeof fetch>((input) => {
    const handler = routes[String(input)]
    return Promise.resolve(handler ? handler() : jsonResponse({ title: 'Not found' }, 404))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('SellScreen', () => {
  it('tells the app while a sale is open, so the register can’t be closed under it', async () => {
    stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/10/items': () => jsonResponse(pouchItems),
    })
    const onSaleOpenChange = vi.fn()
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} onSaleOpenChange={onSaleOpenChange} />)
    await screen.findByRole('button', { name: /popcorn pouch/i })
    expect(onSaleOpenChange).toHaveBeenLastCalledWith(false)

    await user.click(screen.getByRole('button', { name: 'New Sale' }))
    expect(onSaleOpenChange).toHaveBeenLastCalledWith(true)
    await user.click(screen.getByRole('button', { name: 'Clear sale' }))
    expect(onSaleOpenChange).toHaveBeenLastCalledWith(false)

    // Items open a sale too.
    await user.click(screen.getByRole('button', { name: /popcorn pouch/i }))
    await user.click(await products().findByRole('button', { name: /pouch caramel/i }))
    expect(onSaleOpenChange).toHaveBeenLastCalledWith(true)
  })

  it('names the 123 tab by what it shows, and describes it as the custom amount', async () => {
    stubApi({ '/api/v1/catalog/tabs': () => jsonResponse(tabs) })
    render(<SellScreen onOpenMenu={() => {}} />)
    await screen.findByRole('button', { name: /popcorn pouch/i })

    const tab = screen.getByRole('button', { name: '123' })
    expect(tab).toHaveAccessibleDescription('Custom amount')
    expect(screen.queryByRole('button', { name: 'Custom amount' })).not.toBeInTheDocument()
  })

  it('builds a sale by opening a folder and tapping items', async () => {
    stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/10/items': () => jsonResponse(pouchItems),
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: /popcorn pouch/i }))
    await user.click(await products().findByRole('button', { name: /pouch caramel/i }))
    await user.click(products().getByRole('button', { name: /pouch caramel/i }))
    await user.click(products().getByRole('button', { name: /pouch cheese/i }))

    const cart = screen.getByRole('complementary', { name: 'Current sale' })
    const lines = within(cart).getAllByRole('listitem')
    expect(lines[0]).toHaveTextContent('2xPouch CaramelRM32.00')
    expect(lines[1]).toHaveTextContent('1xPouch CheeseRM14.50')
    expect(within(cart).getByText('3 Items')).toBeInTheDocument()
    expect(within(cart).getByTestId('cart-total')).toHaveTextContent('RM46.50')

    await user.click(within(cart).getByRole('button', { name: 'Remove Pouch Caramel' }))
    expect(within(cart).getByTestId('cart-total')).toHaveTextContent('RM14.50')

    // Clearing it closes the sale: "No Sale" until New Sale or the next item.
    await user.click(within(cart).getByRole('button', { name: 'Clear sale' }))
    expect(within(cart).queryAllByRole('listitem')).toHaveLength(0)
    expect(within(cart).getByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    await user.click(within(cart).getByRole('button', { name: 'New Sale' }))
    expect(within(cart).getByRole('heading', { name: 'New Sale' })).toBeInTheDocument()
    expect(within(cart).getByText('0 Items')).toBeInTheDocument()
  })

  it('switching tabs shows that tab’s folders and keeps the sale', async () => {
    stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/10/items': () => jsonResponse(pouchItems),
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: /popcorn pouch/i }))
    await user.click(await products().findByRole('button', { name: /pouch cheese/i }))
    await user.click(screen.getByRole('button', { name: 'Promotional' }))

    expect(screen.getByRole('button', { name: 'Promotional' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: /buy 3 free 1/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /popcorn pouch/i })).not.toBeInTheDocument()
    expect(screen.getByTestId('cart-total')).toHaveTextContent('RM14.50')
  })

  it('shows the API error and recovers on retry', async () => {
    let calls = 0
    stubApi({
      '/api/v1/catalog/tabs': () =>
        ++calls === 1 ? jsonResponse({ title: 'Internal Server Error', detail: 'Database unavailable' }, 500) : jsonResponse(tabs),
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Database unavailable')
    await user.click(screen.getByRole('button', { name: 'Retry' }))

    expect(await screen.findByRole('button', { name: /popcorn pouch/i })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows an empty state for a folder without items', async () => {
    stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/11/items': () => jsonResponse({ categoryId: 11, name: 'Seasonal Items', items: [] }),
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: /seasonal items/i }))

    expect(await screen.findByText('This folder has no items.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Back to folders' }))
    expect(screen.getByRole('button', { name: /popcorn pouch/i })).toBeInTheDocument()
  })

  it('a paid sale empties the cart and logs the staff member off', async () => {
    stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/10/items': () => jsonResponse(pouchItems),
      '/api/v1/payment-methods': () => jsonResponse([{ id: 2, name: 'Visa', kind: 'CASHLESS' }]),
      '/api/v1/orders': () => jsonResponse({
        id: 1, orderNumber: 'R1', receiptCode: '261012ABCDEFG', status: 'CLOSED', subtotal: 16, discountTotal: 0, total: 16,
        amountPaid: 16, note: null, staffName: 'Cashier', customer: null, createdAt: '2026-10-12T10:00:00Z', closedAt: '2026-10-12T10:00:00Z',
        lines: [], orderDiscounts: [], payments: [], voided: null, refund: null,
      }, 201),
    })
    const user = userEvent.setup()
    const onSaleComplete = vi.fn()
    render(<SellScreen onOpenMenu={() => {}} onSaleComplete={onSaleComplete} />)

    expect(screen.getByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New Sale' }))
    const pay = screen.getByRole('button', { name: 'Pay' })
    expect(pay).toBeDisabled()

    await user.click(await screen.findByRole('button', { name: /popcorn pouch/i }))
    await user.click(await products().findByRole('button', { name: /pouch caramel/i }))
    await user.click(pay)
    await user.click(await screen.findByRole('button', { name: 'Visa' }))
    await user.type(screen.getByLabelText('Reference'), '4389-0340')
    await user.click(screen.getByRole('button', { name: 'Confirm payment' }))
    expect(await screen.findByText('Closing in 5s')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Done' }))

    expect(onSaleComplete).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /popcorn pouch/i })).toBeInTheDocument()
  })

  it('starts no sale while the register is closed: New Sale and the products are blocked', async () => {
    stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/10/items': () => jsonResponse(pouchItems),
    })
    const onSaleOpenChange = vi.fn()
    const user = userEvent.setup()
    const closed = { open: false, openOrders: 0, saleOpen: false, refresh: () => {} }
    const { rerender } = render(
      <RegisterContext.Provider value={closed}>
        <SellScreen onOpenMenu={() => {}} onSaleOpenChange={onSaleOpenChange} />
      </RegisterContext.Provider>,
    )

    const cart = within(screen.getByRole('complementary', { name: 'Current sale' }))
    expect(cart.getByRole('button', { name: 'New Sale' })).toBeDisabled()
    expect(cart.getByRole('status')).toHaveTextContent('Register closed')
    const grid = screen.getByRole('region', { name: 'Products', hidden: true })
    expect(grid).toHaveAttribute('inert')
    // Even a tap that gets through (jsdom ignores inert) puts nothing in the cart.
    await screen.findByRole('button', { name: /popcorn pouch/i, hidden: true })
    fireEvent.click(screen.getByRole('button', { name: /popcorn pouch/i, hidden: true }))
    fireEvent.click(await screen.findByRole('button', { name: /pouch caramel/i, hidden: true }))
    expect(cart.getByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    expect(onSaleOpenChange).not.toHaveBeenCalledWith(true)

    // Opened: selling as usual.
    rerender(
      <RegisterContext.Provider value={{ ...closed, open: true }}>
        <SellScreen onOpenMenu={() => {}} onSaleOpenChange={onSaleOpenChange} />
      </RegisterContext.Provider>,
    )
    expect(screen.getByRole('region', { name: 'Products' })).not.toHaveAttribute('inert')
    await user.click(products().getByRole('button', { name: /pouch caramel/i }))
    expect(cart.getByRole('button', { name: 'Pay' })).toBeEnabled()
    expect(cart.getByRole('button', { name: 'Place Order' })).toBeEnabled()
    expect(cart.queryByText('Register closed')).not.toBeInTheDocument()
  })

  it('attaches a customer to the sale', async () => {
    const fetchMock = stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/10/items': () => jsonResponse(pouchItems),
      '/api/v1/customers?field=NAME&page=0&size=20': () =>
        jsonResponse({ items: [{ id: 5, fullName: 'Nur Aisyah', phone: '+60112345678', email: null, referenceId: null }], page: 0, size: 20, totalItems: 1, totalPages: 1 }),
      '/api/v1/orders': () => jsonResponse({ id: 13, orderNumber: 'R13', status: 'OPEN', total: 16 }, 201),
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: /popcorn pouch/i }))
    await user.click(await products().findByRole('button', { name: /pouch caramel/i }))
    await user.click(screen.getByRole('button', { name: '+ Add customer' }))
    await user.click(await screen.findByRole('button', { name: /Nur Aisyah/ }))

    const cart = within(screen.getByRole('complementary', { name: 'Current sale' }))
    expect(cart.getByText('Nur Aisyah')).toBeInTheDocument()
    await user.click(cart.getByRole('button', { name: 'Place Order' }))

    await cart.findByRole('heading', { name: 'No Sale' })
    const [, init] = fetchMock.mock.calls.find(([input]) => String(input) === '/api/v1/orders')!
    expect(JSON.parse(String(init!.body))).toMatchObject({ customerId: 5 })
    // The next sale starts without the customer.
    expect(cart.getByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    await user.click(cart.getByRole('button', { name: 'New Sale' }))
    expect(cart.getByRole('button', { name: '+ Add customer' })).toBeInTheDocument()
  })

  it('Place Order saves the sale unpaid and starts a new one', async () => {
    const fetchMock = stubApi({
      '/api/v1/catalog/tabs': () => jsonResponse(tabs),
      '/api/v1/catalog/categories/10/items': () => jsonResponse(pouchItems),
      '/api/v1/orders': () => jsonResponse({ id: 12, orderNumber: 'R12', status: 'OPEN', total: 16 }, 201),
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)

    await user.click(await screen.findByRole('button', { name: /popcorn pouch/i }))
    await user.click(await products().findByRole('button', { name: /pouch caramel/i }))
    await user.type(screen.getByLabelText('Order note'), 'Collect at 5pm')
    await user.click(screen.getByRole('button', { name: 'Place Order' }))

    // No notice: the open-orders badge counts it.
    expect(await screen.findByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    const [, init] = fetchMock.mock.calls.find(([input]) => String(input) === '/api/v1/orders')!
    expect(JSON.parse(String(init!.body))).toMatchObject({
      mode: 'PLACE_ORDER',
      note: 'Collect at 5pm',
      lines: [{ itemId: 100, quantity: 1 }],
      payments: [],
    })
  })
})
