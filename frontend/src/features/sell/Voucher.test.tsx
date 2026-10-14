import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SellScreen } from './SellScreen'

const routes: Record<string, unknown> = {
  '/api/v1/catalog/tabs': [{ id: 1, name: 'Selling Items', folders: [{ categoryId: 10, name: 'Tins', row: 1, col: 1, itemCount: 1 }] }],
  '/api/v1/catalog/categories/10/items': {
    categoryId: 10, name: 'Tins', items: [{ id: 4, name: 'Tin Caramel', price: 29, imageUrl: null, hasOptions: false }],
  },
  '/api/v1/discounts?scope=ORDER': [{ id: 3, name: 'Order 10%', kind: 'PERCENT', value: 10 }],
  '/api/v1/vouchers/K7MPQ2XW9A': { code: 'K7MPQ2XW9A', name: 'RM10 Voucher', value: 10, expiresOn: '2026-12-31', status: 'UNUSED' },
  '/api/v1/vouchers/USEDCODE22': { code: 'USEDCODE22', name: 'RM10 Voucher', value: 10, expiresOn: '2026-12-31', status: 'USED' },
}

function stubApi() {
  const fetchMock = vi.fn<typeof fetch>(async (input) => {
    const path = String(input)
    if (path === '/api/v1/orders') {
      return new Response(JSON.stringify({ id: 1, orderNumber: 'R1', status: 'OPEN', total: 19 }), { status: 201 })
    }
    return path in routes
      ? new Response(JSON.stringify(routes[path]), { status: 200 })
      : new Response(JSON.stringify({ title: 'Not found', detail: 'No voucher has the code NOPE' }), { status: 404 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const cart = () => within(screen.getByRole('complementary', { name: 'Current sale' }))

describe('a physical voucher', () => {
  it('is keyed in on the % tab, comes off the order, and is sent by its code', async () => {
    const fetchMock = stubApi()
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)
    await user.click(await screen.findByRole('button', { name: /tins/i }))
    await user.click(await screen.findByRole('button', { name: /tin caramel/i }))

    await user.click(screen.getByRole('button', { name: 'Order discounts' }))
    await user.click(await screen.findByRole('button', { name: /Voucher/ }))
    const dialog = screen.getByRole('dialog', { name: 'Voucher' })
    const code = within(dialog).getByLabelText('Voucher code')

    await user.type(code, 'USEDCODE22')
    await user.click(within(dialog).getByRole('button', { name: 'Check' }))
    expect(await within(dialog).findByText(/this voucher has already been used/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Apply voucher' })).toBeDisabled()

    await user.clear(code)
    await user.type(code, 'K7MPQ2XW9A')
    await user.click(within(dialog).getByRole('button', { name: 'Check' }))
    expect(await within(dialog).findByText(/RM10 Voucher \(RM10\.00 off\)/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Apply voucher' }))

    // 29.00 − RM10 voucher; it is the sale's one discount, so the others are off.
    expect(cart().getByTestId('cart-total')).toHaveTextContent('RM19.00')
    expect(cart().getByText('RM10 Voucher · K7MPQ2XW9A')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Order 10%/ })).toBeDisabled()

    await user.click(cart().getByRole('button', { name: 'Place Order' }))
    await cart().findByText(/Order R1 placed/)
    const [, init] = fetchMock.mock.calls.find(([input]) => String(input) === '/api/v1/orders')!
    expect(JSON.parse(String(init!.body))).toMatchObject({ discountIds: [], voucherCode: 'K7MPQ2XW9A' })
  })
})
