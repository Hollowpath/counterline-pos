import { render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SellScreen } from './SellScreen'

const routes: Record<string, unknown> = {
  '/api/v1/catalog/tabs': [{ id: 1, name: 'Selling Items', folders: [{ categoryId: 10, name: 'Gifts', row: 1, col: 1, itemCount: 2 }] }],
  '/api/v1/catalog/categories/10/items': {
    categoryId: 10,
    name: 'Gifts',
    items: [
      { id: 7, name: 'Gift Box', price: 70, imageUrl: null, hasOptions: true },
      { id: 1, name: 'Pouch Cheese', price: 16, imageUrl: null, hasOptions: false },
    ],
  },
  '/api/v1/catalog/items/7/options': {
    itemId: 7, name: 'Gift Box', price: 70,
    groups: [{
      id: 1, name: 'Flavours', chooseExactly: 3,
      options: [{ id: 1, name: 'Caramel', price: 0 }, { id: 2, name: 'Cheese', price: 0 }, { id: 3, name: 'Matcha', price: 2 }],
    }],
  },
  '/api/v1/discounts?scope=ITEM': [
    { id: 1, name: 'Staff 30%', kind: 'PERCENT', value: 30 },
    { id: 2, name: 'Voucher RM10', kind: 'AMOUNT', value: 10 },
  ],
  '/api/v1/discounts?scope=ORDER': [{ id: 3, name: 'Order 10%', kind: 'PERCENT', value: 10 }],
}

function stubApi() {
  const fetchMock = vi.fn<typeof fetch>(async (input) => {
    const path = String(input)
    if (path === '/api/v1/orders') {
      return new Response(JSON.stringify({ id: 1, orderNumber: 'R1', status: 'OPEN', total: 0 }), { status: 201 })
    }
    return path in routes
      ? new Response(JSON.stringify(routes[path]), { status: 200 })
      : new Response(JSON.stringify({ title: 'Not found' }), { status: 404 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const cart = () => within(screen.getByRole('complementary', { name: 'Current sale' }))
const products = () => within(screen.getByRole('region', { name: 'Products' }))

async function openGifts(user: UserEvent) {
  render(<SellScreen onOpenMenu={() => {}} />)
  await user.click(await screen.findByRole('button', { name: /gifts/i }))
}

describe('item options and discounts', () => {
  it('requires exactly the number of picks, prices options and discounts, and adds the line', async () => {
    stubApi()
    const user = userEvent.setup()
    await openGifts(user)

    await user.click(await screen.findByRole('button', { name: /gift box/i }))
    // Wait until the options have loaded (the dialog first shows a loading state).
    const add = await screen.findByRole('button', { name: 'Add' })
    const dialog = screen.getByRole('dialog', { name: 'Gift Box' })
    expect(add).toBeDisabled()

    await user.click(within(dialog).getByRole('button', { name: 'One more Caramel' }))
    await user.click(within(dialog).getByRole('button', { name: 'One more Matcha' }))
    await user.click(within(dialog).getByRole('button', { name: 'One more Matcha' }))
    expect(within(dialog).getByRole('button', { name: 'One more Cheese' })).toBeDisabled() // group is full
    expect(add).toBeEnabled()

    await user.click(within(dialog).getByRole('button', { name: 'Increase quantity' }))
    await user.click(within(dialog).getByRole('button', { name: /Staff 30%/ }))
    // (70 + 2 × 2) × 2 = 148.00, − 30% (44.40) = 103.60
    expect(within(dialog).getByTestId('item-total')).toHaveTextContent('RM103.60')
    await user.click(add)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(cart().getByText('1 Caramel, 2 Matcha')).toBeInTheDocument()
    expect(cart().getByTestId('cart-total')).toHaveTextContent('RM103.60')
  })

  it('edits a line from the cart', async () => {
    stubApi()
    const user = userEvent.setup()
    await openGifts(user)
    await user.click(await screen.findByRole('button', { name: /pouch cheese/i }))

    await user.click(cart().getByRole('button', { name: 'Edit Pouch Cheese' }))
    const dialog = await screen.findByRole('dialog', { name: 'Pouch Cheese' })
    await user.type(within(dialog).getByLabelText('Line note'), 'No ribbon')
    await user.click(within(dialog).getByRole('button', { name: /Voucher RM10/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(cart().getByText('“No ribbon”')).toBeInTheDocument()
    expect(cart().getByTestId('cart-total')).toHaveTextContent('RM6.00')
  })

  it('allows one discount per sale, on a line or on the order', async () => {
    stubApi()
    const user = userEvent.setup()
    await openGifts(user)
    await user.click(await screen.findByRole('button', { name: /pouch cheese/i }))

    // Choosing another discount replaces the first.
    await user.click(cart().getByRole('button', { name: 'Edit Pouch Cheese' }))
    let dialog = await screen.findByRole('dialog', { name: 'Pouch Cheese' })
    await user.click(within(dialog).getByRole('button', { name: /Staff 30%/ }))
    await user.click(within(dialog).getByRole('button', { name: /Voucher RM10/ }))
    expect(within(dialog).getByRole('button', { name: /Staff 30%/ })).toHaveAttribute('aria-pressed', 'false')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    // The order discounts are locked while a line has one...
    await user.click(screen.getByRole('button', { name: 'Order discounts' }))
    expect(await screen.findByRole('button', { name: /Order 10%/ })).toBeDisabled()
    expect(screen.getByText(/already has Voucher RM10 on Pouch Cheese/)).toBeInTheDocument()

    // ...and so are the other lines'.
    await user.click(screen.getByRole('button', { name: 'Selling Items' }))
    await user.click(await screen.findByRole('button', { name: /gifts/i }))
    await user.click(await products().findByRole('button', { name: /pouch cheese/i }))
    await user.click(cart().getAllByRole('button', { name: 'Edit Pouch Cheese' })[1])
    dialog = await screen.findByRole('dialog', { name: 'Pouch Cheese' })
    expect(within(dialog).getByRole('button', { name: /Staff 30%/ })).toBeDisabled()
    expect(within(dialog).getByRole('note')).toHaveTextContent('Only one discount can be applied to a sale')
  })

  it('applies order discounts from the % tab and sends everything to checkout', async () => {
    const fetchMock = stubApi()
    const user = userEvent.setup()
    await openGifts(user)
    await user.click(await screen.findByRole('button', { name: /pouch cheese/i }))

    await user.click(screen.getByRole('button', { name: 'Order discounts' }))
    await user.click(await screen.findByRole('button', { name: /Order 10%/ }))
    expect(cart().getByTestId('cart-total')).toHaveTextContent('RM14.40')

    await user.click(screen.getByRole('button', { name: 'Custom amount' }))
    for (const key of ['1', '2', '5', '0']) await user.click(screen.getByRole('button', { name: key }))
    expect(screen.getByLabelText('Amount entered')).toHaveTextContent('RM12.50')
    await user.click(screen.getByRole('button', { name: 'Add custom amount' }))
    // (16.00 + 12.50) − 10% = 25.65
    expect(cart().getByTestId('cart-total')).toHaveTextContent('RM25.65')

    await user.click(cart().getByRole('button', { name: 'Place Order' }))
    await cart().findByRole('status') // the "Order R1 placed" notice
    const [, init] = fetchMock.mock.calls.find(([input]) => String(input) === '/api/v1/orders')!
    expect(JSON.parse(String(init!.body))).toMatchObject({
      discountIds: [3],
      lines: [
        { itemId: 1, quantity: 1, modifiers: [], discountIds: [] },
        { customAmount: 12.5, quantity: 1, discountIds: [] },
      ],
    })
  })
})
