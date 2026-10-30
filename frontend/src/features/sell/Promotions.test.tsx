import { render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { MemberPerks, Quote } from '../checkout/types'
import { SellScreen } from './SellScreen'

const b3f1 = { id: 1, name: 'Buy 3 Free 1 Promo', type: 'BUY_X_GET_FREE', pointsCost: 0 }
const memberRedemption = { id: 3, name: 'Member Redemption', type: 'POINTS_REDEMPTION', pointsCost: 7 }

const routes: Record<string, unknown> = {
  '/api/v1/catalog/tabs': [{ id: 2, name: 'Promotional', folders: [{ categoryId: 20, name: 'Promo', row: 1, col: 1, itemCount: 3 }] }],
  '/api/v1/catalog/categories/20/items': {
    categoryId: 20,
    name: 'Promo',
    items: [
      { id: 1, name: 'PC Caramel', price: 10, imageUrl: null, promotion: null },
      { id: 21, name: 'B3 F1 BC Caramel', price: 6.5, imageUrl: null, promotion: b3f1 },
      { id: 23, name: 'MFOC PC BBQ', price: 10, imageUrl: null, promotion: memberRedemption },
    ],
  },
  '/api/v1/customers?field=NAME&page=0&size=20&q=nur': {
    items: [{ id: 5, fullName: 'Nur Aisyah', phone: '+60112345678', email: null, referenceId: null }],
    page: 0, size: 20, totalItems: 1, totalPages: 1,
  },
  '/api/v1/discounts?scope=ORDER': [
    { id: 9, name: 'Marketing Department Special Approved', kind: 'PERCENT', value: 100, requiresApproval: true },
  ],
}

/** `quote` answers POST /orders/quote; `extra` adds or overrides routes (e.g. the member's perks). */
function stubApi(quote: () => Quote, extra: Record<string, unknown> = {}) {
  const all = { ...routes, ...extra }
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const path = String(input)
    if (path === '/api/v1/orders/quote') return new Response(JSON.stringify(quote()), { status: 200 })
    if (path === '/api/v1/auth/special-access') {
      return JSON.parse(String(init!.body)).pin === '999999'
        ? new Response(JSON.stringify({ token: 'manager-token', expiresAt: '2999-01-01T00:00:00Z', staff: { id: 2, fullName: 'Daniel Lim', role: 'MANAGER' } }), { status: 200 })
        : new Response(JSON.stringify({ title: 'Special access required', detail: 'Manager PIN · 3 tries left' }), { status: 403 })
    }
    if (path === '/api/v1/orders' && init?.method === 'POST') {
      return new Response(JSON.stringify({ id: 1, orderNumber: 'R1' }), { status: 201 })
    }
    return path in all
      ? new Response(JSON.stringify(all[path]), { status: 200 })
      : new Response(JSON.stringify({ title: 'Not found' }), { status: 404 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const cart = () => within(screen.getByRole('complementary', { name: 'Current sale' }))
const products = () => within(screen.getByRole('region', { name: 'Products' }))

async function addItems(user: UserEvent, ...names: RegExp[]) {
  render(<SellScreen onOpenMenu={() => {}} />)
  await user.click(await products().findByRole('button', { name: /^promo/i }))
  for (const name of names) await user.click(await products().findByRole('button', { name }))
}

async function addMember(user: UserEvent) {
  const newSale = cart().queryByRole('button', { name: 'New Sale' })
  if (newSale) await user.click(newSale)
  await user.click(cart().getByRole('button', { name: '+ Add customer' }))
  await user.type(await screen.findByRole('searchbox', { name: 'Search customers' }), 'nur')
  await user.click(await screen.findByRole('button', { name: /Nur Aisyah/ }))
}

const quote = (overrides: Partial<Quote>): Quote => ({ subtotal: 0, discountTotal: 0, total: 0, problems: [], loyalty: null, ...overrides })

const perks = (overrides: Partial<MemberPerks>): MemberPerks => ({
  points: 0, redemptionCost: 7, birthdayMonth: false, newMemberGifts: [], ...overrides,
})

describe('free recycle bags', () => {
  it('need no member, and are listed after the items bought', async () => {
    const bag = { id: 7, name: 'Spend RM50 Free Bag (S)', type: 'MIN_SPEND', pointsCost: 0, membersOnly: false }
    stubApi(() => quote({}), {
      '/api/v1/catalog/categories/20/items': {
        categoryId: 20, name: 'Promo', items: [
          { id: 26, name: 'FOC Recycle Bag (S)', price: 0.5, imageUrl: null, promotion: bag },
          { id: 1, name: 'PC Caramel', price: 10, imageUrl: null, promotion: null, countsForPromotions: true },
        ],
      },
    })
    const user = userEvent.setup()
    await addItems(user, /FOC Recycle Bag/, /PC Caramel/)

    expect(products().getByRole('button', { name: /FOC Recycle Bag/ })).not.toHaveTextContent('member')
    expect(cart().queryByRole('alert', { name: /can’t be paid yet/ })).not.toBeInTheDocument()
    expect(cart().getAllByRole('button', { name: /^Edit / }).map((line) => line.textContent)).toEqual([
      expect.stringContaining('PC Caramel'), expect.stringContaining('FOC Recycle Bag (S)'),
    ])
  })
})

describe('the staff discount', () => {
  it('can’t be given on a member’s sale', async () => {
    stubApi(() => quote({}), {
      '/api/v1/discounts?scope=ORDER': [{ id: 4, name: 'Staff Discount', kind: 'PERCENT', value: 30, notForMembers: true }],
    })
    const user = userEvent.setup()
    await addItems(user, /PC Caramel/)
    await user.click(screen.getByRole('button', { name: 'Order discounts' }))
    await user.click(await screen.findByRole('button', { name: /Staff Discount/ }))
    expect(cart().getByRole('button', { name: 'Pay' })).toBeEnabled()

    await addMember(user)
    expect(cart().getByRole('alert', { name: /can’t be paid yet/ }))
      .toHaveTextContent('Staff Discount: not for members')
    expect(cart().getByRole('button', { name: 'Pay' })).toBeDisabled()
  })
})

describe('Edit Order', () => {
  it('loads a placed order into the cart and saves it back to the same order', async () => {
    const fetchMock = stubApi(() => quote({}), {
      '/api/v1/orders/9/cart': {
        id: 9, orderNumber: 'R9', note: null, customer: null, orderDiscounts: [],
        lines: [{
          itemId: 1, name: 'PC Caramel', unitPrice: 10, quantity: 2, note: null, modifiers: [], discounts: [],
          promotion: null, countsForPromotions: true,
        }],
      },
      '/api/v1/orders/9': { id: 9, orderNumber: 'R9', status: 'OPEN', total: 30 },
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} editRequest={{ orderId: 9, seq: 1 }} />)

    expect(await cart().findByRole('heading', { name: 'Editing R9' })).toBeInTheDocument()
    expect(cart().getByTestId('cart-total')).toHaveTextContent('RM20.00')
    await user.click(await products().findByRole('button', { name: /^promo/i }))
    await user.click(await products().findByRole('button', { name: /PC Caramel/ }))
    await user.click(cart().getByRole('button', { name: 'Place Order' }))

    expect(await cart().findByRole('heading', { name: 'No Sale' })).toBeInTheDocument()
    const [, init] = fetchMock.mock.calls.find(([input, request]) => String(input) === '/api/v1/orders/9' && request?.method === 'PUT')!
    expect(JSON.parse(String(init!.body))).toMatchObject({ mode: 'PLACE_ORDER', lines: [{ itemId: 1, quantity: 3 }] })
  })
})

describe('promotional items', () => {
  it('are marked on the tile and given free, but need a member before payment', async () => {
    stubApi(() => quote({}))
    const user = userEvent.setup()
    await addItems(user, /PC Caramel/, /B3 F1 BC Caramel/)

    expect(products().getByRole('button', { name: /MFOC PC BBQ/ })).toHaveTextContent('7 points · member')
    expect(cart().getByText('Buy 3 Free 1 Promo −RM6.50')).toBeInTheDocument()
    expect(cart().getByTestId('cart-total')).toHaveTextContent('RM10.00')

    expect(cart().getByRole('alert', { name: 'Why this sale can’t be paid yet' })).toHaveTextContent('Add member')
    expect(cart().getByRole('button', { name: 'Pay' })).toBeDisabled()
    // Placing the order is never blocked: the rules apply when it is paid.
    expect(cart().getByRole('button', { name: 'Place Order' })).toBeEnabled()
  })

  it('show the server’s reasons for a member’s sale and keep payment blocked', async () => {
    const fetchMock = stubApi(() => quote({
      total: 10,
      problems: ['Buy 3 Free 1 Promo: 1 free item for every 3 items bought. This sale has 1 bought, so 0 free, but 1 given.'],
      loyalty: { before: 0, earned: 1, redeemed: 0, after: 1 },
    }))
    const user = userEvent.setup()
    await addItems(user, /PC Caramel/, /B3 F1 BC Caramel/)
    await addMember(user)

    // The quote is debounced, then fetched: allow for a busy test run.
    expect(await cart().findByText(/This sale has 1 bought/, {}, { timeout: 4000 })).toBeInTheDocument()
    expect(cart().getByRole('button', { name: 'Pay' })).toBeDisabled()

    // The latest quote: an earlier one may have gone out before the member was added.
    const [, init] = fetchMock.mock.calls.filter(([input]) => String(input) === '/api/v1/orders/quote').at(-1)!
    expect(JSON.parse(String(init!.body))).toMatchObject({
      customerId: 5,
      lines: [{ itemId: 1, quantity: 1 }, { itemId: 21, quantity: 1 }],
      payments: [],
    })
  })

  it('preview the member’s points and allow payment when the sale qualifies', async () => {
    stubApi(() => quote({ total: 10, loyalty: { before: 7, earned: 1, redeemed: 7, after: 1 } }))
    const user = userEvent.setup()
    await addItems(user, /PC Caramel/, /MFOC PC BBQ/)
    await addMember(user)

    expect(await cart().findByTestId('points-preview')).toHaveTextContent(
      /Current points\s*7\s*\+\s*Earned from this cart\s*1\s*−\s*Redeemed\s*7\s*=\s*Total after this purchase\s*1/,
    )
    expect(cart().queryByRole('alert')).not.toBeInTheDocument()
    expect(cart().getByRole('button', { name: 'Pay' })).toBeEnabled()
  })

  it('cannot take a discount', async () => {
    stubApi(() => quote({}))
    const user = userEvent.setup()
    await addItems(user, /B3 F1 BC Caramel/)

    await user.click(cart().getByRole('button', { name: 'Edit B3 F1 BC Caramel' }))
    const dialog = await screen.findByRole('dialog', { name: 'B3 F1 BC Caramel' })
    expect(await within(dialog).findByRole('region', { name: 'Notes and quantity' })).toBeInTheDocument()
    expect(within(dialog).queryByRole('region', { name: 'Discounts' })).not.toBeInTheDocument()
  })

})

describe('a member on the sale', () => {
  it('shows a birthday cake, a new member’s gift and the points as soon as they are added', async () => {
    stubApi(() => quote({}), {
      '/api/v1/customers/5/perks': perks({
        points: 5,
        birthdayMonth: true,
        newMemberGifts: [{ promotion: 'Registration Reward Promo', items: ['MRG MBP Classic Mix'], availableUntil: '2026-10-14T07:05:00Z' }],
      }),
    })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)
    await addMember(user)

    expect(await cart().findByRole('img', { name: 'Birthday month' })).toBeInTheDocument()
    // The label shows its gift on a tap (a touchscreen has no hover).
    expect(cart().queryByText(/MRG MBP Classic Mix/)).not.toBeInTheDocument()
    await user.click(cart().getByRole('button', { name: 'New Member' }))
    expect(cart().getByText(/MRG MBP Classic Mix · until/)).toBeInTheDocument()
    expect(cart().getByTestId('points-preview')).toHaveTextContent(
      /Current points\s*5\s*\+\s*Earned from this cart\s*0\s*=\s*Total after this purchase\s*5/,
    )
  })

  it('shows no cake outside the birthday month', async () => {
    stubApi(() => quote({}), { '/api/v1/customers/5/perks': perks({ points: 2 }) })
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} />)
    await addMember(user)

    expect(await cart().findByTestId('points-preview')).toHaveTextContent(/Total after this purchase\s*2/)
    expect(cart().queryByRole('img', { name: 'Birthday month' })).not.toBeInTheDocument()
    expect(cart().queryByText('New Member')).not.toBeInTheDocument()
  })
})

describe('a department comp', () => {
  it('is placed without a PIN, and needs a manager’s PIN before payment', async () => {
    const fetchMock = stubApi(() => quote({}))
    const user = userEvent.setup()
    await addItems(user, /PC Caramel/)
    await user.click(screen.getByRole('button', { name: 'Order discounts' }))
    await user.click(await screen.findByRole('button', { name: /Marketing Department Special Approved/ }))

    // Place Order is never held up: the approval is asked for when the order is paid.
    await user.click(cart().getByRole('button', { name: 'Place Order' }))
    await cart().findByRole('heading', { name: 'No Sale' })
    expect(screen.queryByRole('dialog', { name: 'Special Access Required' })).not.toBeInTheDocument()
    const [, init] = fetchMock.mock.calls.find(([input, request]) => String(input) === '/api/v1/orders' && request?.method === 'POST')!
    expect(JSON.parse(String(init!.body))).toMatchObject({ discountIds: [9] })
    expect(JSON.parse(String(init!.body)).approvalToken).toBeUndefined()

    await user.click(screen.getByRole('button', { name: 'Promotional' }))
    await user.click(await products().findByRole('button', { name: /^promo/i }))
    await user.click(await products().findByRole('button', { name: /PC Caramel/ }))
    await user.click(screen.getByRole('button', { name: 'Order discounts' }))
    await user.click(await screen.findByRole('button', { name: /Marketing Department Special Approved/ }))
    await user.click(cart().getByRole('button', { name: 'Pay' }))
    const approval = await screen.findByRole('dialog', { name: 'Special Access Required' })
    expect(approval).toHaveTextContent('Manager PIN · Discount: Marketing Department Special Approved')
    for (const digit of '999999') await user.click(within(approval).getByRole('button', { name: digit }))
    expect(await screen.findByText(/Total \(1 items\)/)).toBeInTheDocument()
    // The approval is asked for this one action.
    const [, request] = fetchMock.mock.calls.find(([input]) => String(input) === '/api/v1/auth/special-access')!
    expect(JSON.parse(String(request!.body))).toEqual({ pin: '999999', operation: 'DISCOUNT' })
  })

  it('needs no PIN when a manager is at the till', async () => {
    stubApi(() => quote({}))
    const user = userEvent.setup()
    render(<SellScreen onOpenMenu={() => {}} isManager />)
    await user.click(await products().findByRole('button', { name: /^promo/i }))
    await user.click(await products().findByRole('button', { name: /PC Caramel/ }))
    await user.click(screen.getByRole('button', { name: 'Order discounts' }))
    await user.click(await screen.findByRole('button', { name: /Marketing Department Special Approved/ }))

    await user.click(cart().getByRole('button', { name: 'Place Order' }))
    await cart().findByRole('heading', { name: 'No Sale' })
    expect(screen.queryByRole('dialog', { name: 'Special Access Required' })).not.toBeInTheDocument()
  })
})
