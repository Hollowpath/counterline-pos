import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SellScreen } from './SellScreen'

const longName = 'Nur Aisyah Binti Muhammad Zulkarnain Abdul Rahman Al-Hakim Ibrahim'

const routes: Record<string, unknown> = {
  '/api/v1/catalog/tabs': [{ id: 1, name: 'Selling Items', folders: [{ categoryId: 10, name: 'Pouch', row: 1, col: 1, itemCount: 1 }] }],
  '/api/v1/catalog/categories/10/items': { categoryId: 10, name: 'Pouch', items: [{ id: 100, name: 'Pouch Caramel', price: 16, imageUrl: null }] },
  '/api/v1/customers?field=NAME&page=0&size=20&q=nur': {
    items: [{ id: 5, fullName: longName, phone: '+601234567891', email: null, referenceId: null }],
    page: 0, size: 20, totalItems: 1, totalPages: 1,
  },
}

describe('the selected customer in the cart', () => {
  it('stays inside its bordered summary: the box keeps its full height, a long name wraps and the phone number stays on one line', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      return path in routes
        ? new Response(JSON.stringify(routes[path]), { status: 200 })
        : new Response(JSON.stringify({ title: 'Not found' }), { status: 404 })
    }))
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)
    await user.click(await screen.findByRole('button', { name: /pouch/i }))
    await user.click(await within(screen.getByRole('region', { name: 'Products' })).findByRole('button', { name: /pouch caramel/i }))
    const cart = within(screen.getByRole('complementary', { name: 'Current sale' }))
    await user.click(cart.getByRole('button', { name: '+ Add customer' }))
    await user.type(await screen.findByRole('searchbox', { name: 'Search customers' }), 'nur')
    await user.click(await screen.findByRole('button', { name: new RegExp(longName) }))

    const name = await cart.findByText(longName)
    const box = name.closest('div')!
    // A short cart column squeezes its rows; this one never shrinks below its content, so nothing spills out of the border.
    expect(box).toHaveClass('shrink-0')
    expect(name).toHaveClass('[overflow-wrap:anywhere]')
    expect(cart.getByText(/\+60 12/)).toHaveClass('whitespace-nowrap')
    expect(within(box).getByRole('button', { name: 'Remove customer' })).toBeInTheDocument()
  })
})
